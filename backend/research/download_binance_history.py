"""Download public Binance USD-M Futures 1m klines (no API key)."""
import argparse, csv, json, time
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

BASE = 'https://fapi.binance.com/fapi/v1/klines'
FIELDS = ['timestamp','symbol','open','high','low','close','volume','close_time']

def fetch_page(symbol, start_ms, end_ms, limit=1500):
    url = f"{BASE}?symbol={symbol}&interval=1m&startTime={start_ms}&endTime={end_ms}&limit={limit}"
    req = Request(url, headers={'User-Agent':'SPLED-INTELLIGENCE-research/0.7'})
    with urlopen(req, timeout=25) as response:
        payload = json.loads(response.read().decode())
    if isinstance(payload, dict):
        raise RuntimeError(f"Binance API error for {symbol}: {payload}")
    return payload

def download(symbols, days, out, pause=0.15):
    end_ms = int(datetime.now(timezone.utc).timestamp()*1000)
    start_ms = end_ms - days*24*60*60*1000
    path=Path(out); path.parent.mkdir(parents=True, exist_ok=True)
    total=0
    with path.open('w', newline='', encoding='utf-8') as f:
        w=csv.DictWriter(f, fieldnames=FIELDS); w.writeheader()
        for symbol in symbols:
            cursor=start_ms
            while cursor < end_ms:
                rows=fetch_page(symbol, cursor, end_ms)
                if not rows: break
                for r in rows:
                    w.writerow({'timestamp':int(r[0]),'symbol':symbol,'open':r[1],'high':r[2],
                      'low':r[3],'close':r[4],'volume':r[5],'close_time':int(r[6])})
                total += len(rows)
                next_cursor=int(rows[-1][0])+60_000
                if next_cursor <= cursor: raise RuntimeError('Binance pagination did not advance')
                cursor=next_cursor
                if len(rows)<1500: break
                time.sleep(pause)
            print(f'{symbol}: downloaded through {cursor}')
            time.sleep(pause)
    print(f'Wrote {total} candles to {path}')

def main():
    p=argparse.ArgumentParser(); p.add_argument('--symbols',required=True,help='Comma-separated symbols, e.g. BTCUSDT,ETHUSDT')
    p.add_argument('--days',type=int,default=90); p.add_argument('--out',default='data/binance_1m.csv')
    a=p.parse_args()
    if not 1 <= a.days <= 365: raise SystemExit('--days must be between 1 and 365')
    download([s.strip().upper() for s in a.symbols.split(',') if s.strip()],a.days,a.out)
if __name__=='__main__': main()
