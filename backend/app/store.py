"""Durable local signal journal. SQLite is suitable for a single API instance;
use managed PostgreSQL before horizontal scaling or multi-instance deployment."""
import json, os, sqlite3, threading, time
from pathlib import Path

class SignalStore:
    def __init__(self, path: str | None = None):
        self.path = path or os.getenv('SIGNAL_DB_PATH', './data/spled_signals.sqlite3')
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        with self._connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS signals (
              id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL,
              direction TEXT NOT NULL, created_at REAL NOT NULL, payload TEXT NOT NULL)''')
            db.execute('CREATE INDEX IF NOT EXISTS idx_signals_created ON signals(created_at DESC)')

    def _connect(self):
        db=sqlite3.connect(self.path, timeout=10)
        db.execute('PRAGMA journal_mode=WAL')
        return db

    def append(self, signal: dict):
        with self.lock, self._connect() as db:
            db.execute('INSERT INTO signals(symbol,direction,created_at,payload) VALUES(?,?,?,?)',
                       (signal['symbol'],signal['direction'],signal['created_at'],json.dumps(signal)))

    def recent(self, limit: int = 250):
        with self.lock, self._connect() as db:
            rows=db.execute('SELECT payload FROM signals ORDER BY created_at DESC LIMIT ?', (limit,)).fetchall()
        return [json.loads(x[0]) for x in rows]

    def latest_by_signal(self, limit: int = 1000):
        rows=self.recent(min(5000,limit*5)); out={}
        for item in rows:
            key=f"{item['symbol']}:{item.get('mode','UNKNOWN')}:{item['direction']}"
            out.setdefault(key, item)
            if len(out)>=limit: break
        return out

    def latest_by_symbol(self, limit: int = 500):
        rows=self.recent(limit*3); out={}
        for item in rows:
            out.setdefault(item['symbol'], item)
            if len(out)>=limit: break
        return out
