# Cloud deployment

## Architecture

`Binance USD-M public feeds → FastAPI async feed service → scanner + SQLite signal journal → HTTPS/WSS reverse proxy → Android app`

Once deployed on an always-on host, cloud scanning is independent of the phone. Switching off the phone, force-closing the app or losing mobile connectivity does not stop the backend. The app receives a fresh snapshot and recent signal journal when it reconnects. If the cloud host, its network or Binance streams fail, scanning pauses.

## Deploy with Docker Compose

1. Provision an always-on Linux VM/container host and configure billing yourself.
2. Install Docker Engine and Docker Compose plugin.
3. From the repository root, run `docker compose -f infra/docker-compose.yml up -d --build`.
4. Verify `/health` from a trusted network. Do not expose port 8000 publicly as-is.
5. Configure a TLS reverse proxy for HTTPS/WSS and authentication/authorization before public access.
6. Set the Android build variable to `wss://YOUR_DOMAIN/ws` and build/install the APK.
7. Monitor feed freshness, reconnects, symbol count, CPU, memory, disk, SQLite WAL growth, Binance request weight and logs. Back up the named `spled-data` volume.

## Important production hardening

This is a single-instance engineering baseline. Before customer/public deployment, add robust user authentication and authorization, TLS termination, strict origin policy, rate limiting, secrets management, PostgreSQL/TimescaleDB, Redis/pub-sub for horizontal fanout, signal lifecycle/outcome tracking, FCM push, metrics/log aggregation and paging, backups, restore drills, integration/load tests, failover and independent security review. The app must not contain exchange trading credentials.

## Android endpoints

- Emulator development: `ws://10.0.2.2:8000/ws` with `-PALLOW_CLEARTEXT=true` only for local testing.
- Cloud production: `wss://YOUR_DOMAIN/ws` (default; cleartext disabled).

No cloud VM, DNS, TLS certificate, credentials or billing account is created by the source package.
