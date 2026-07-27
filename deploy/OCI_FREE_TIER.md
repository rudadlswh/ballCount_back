# OCI Free Tier deployment runbook

Use this runbook only after Oracle restores the existing tenancy or explicitly permits a new Free Tier
registration. Do not upgrade the account to Pay As You Go.

## 1. Create the instance

- Select an Always Free-eligible Ampere A1 shape and Ubuntu 24.04 LTS ARM64 image.
- Allocate only resources marked Always Free in the console.
- Add a dedicated SSH public key during instance creation. Never upload its private key.
- Give the instance a public IP and record the SSH username and address.
- In the OCI security list or network security group, allow TCP `22` from the administrator's public IP
  only, and TCP `80`/`443` from the internet. Do not add an ingress rule for `8088`.

## 2. Bootstrap the host

After confirming the SSH host fingerprint through the OCI console, connect and run:

```bash
chmod +x deploy/bootstrap-ubuntu.sh
./deploy/bootstrap-ubuntu.sh
exit
```

Reconnect once so membership in the `docker` group applies. The script installs Docker Engine and the
Compose plugin from Docker's official Ubuntu repository, plus Nginx and Certbot.

## 3. Stage the application

Clone the intended commit, then prepare secrets without printing them to the terminal or shell history:

```bash
cp .env.example .env
mkdir -p secrets
chmod 700 secrets
chmod 600 .env
```

Fill `.env` with the existing production Supabase, administrator, and APNs values. Keep these values for
the first boot:

```dotenv
SPRING_PROFILES_ACTIVE=production
SERVER_PORT=8088
APP_DB_SCHEMA=kbo_crawler_api
APP_RUNTIME_ROLE=writer
APP_SYNC_ENABLED=false
KBO_PUSH_ENABLED=false
```

Copy the APNs key over SSH directly into `secrets/`. On the Linux server, make it readable only by the
container identity:

```bash
sudo chown 10001:10001 secrets/AuthKey_<key-id>.p8
sudo chmod 0400 secrets/AuthKey_<key-id>.p8
```

Set `APNS_PRIVATE_KEY_PATH=/run/secrets/AuthKey_<key-id>.p8` in `.env`. Never use a host path in this value.

## 4. First boot

The verification script refuses to start if synchronization or push is enabled:

```bash
chmod +x deploy/verify-first-boot.sh
./deploy/verify-first-boot.sh
docker compose logs --tail=300
```

This boot can run Flyway against Supabase, but it cannot run the scheduler or send APNs notifications.
Do not stop Render yet.

## 5. DNS, Nginx, and HTTPS

Point the API domain's `A` record to the instance public IP. Replace `api.example.com` in
`deploy/nginx/kbo-back.conf.example`, install it as `/etc/nginx/sites-available/kbo-back`, enable the site,
and validate before reloading:

```bash
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d api.example.com
sudo certbot renew --dry-run
```

Verify through HTTPS:

```bash
curl -i https://api.example.com/healthz
curl -i https://api.example.com/api/v1/games
curl -iN https://api.example.com/api/v1/games/<game-id>/stream
```

The dedicated SSE location disables proxy buffering and permits the application's six-hour connection.

## 6. Cut over the single writer

Only after REST, SSE, Flyway, Supabase, and container health checks pass:

1. Suspend the Render service; do not delete it yet.
2. Confirm Render has stopped before proceeding.
3. Change `APP_SYNC_ENABLED=true` and `KBO_PUSH_ENABLED=true` in the Docker server's `.env`.
4. Recreate and inspect the container:

```bash
docker compose up -d --force-recreate
docker compose ps
docker compose logs --tail=300
curl -i https://api.example.com/healthz
```

If the writer fails, set both switches back to `false`, recreate the container, and resume Render. Never
run the Render and Docker schedulers simultaneously.
