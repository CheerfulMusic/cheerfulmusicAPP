# Logto OSS deployment

This directory runs the open-source Logto identity service and its own PostgreSQL database. The application's MySQL database is separate. The PostgreSQL named volume persists through container restarts and `docker compose down`; do not run `docker compose down -v` on an instance with user data.

The image versions are pinned in `compose.yml`. Logto listens on host loopback ports 3001 (authentication) and 3002 (Admin Console). PostgreSQL port 5432 is available only to containers on this Compose network.

## Local setup

1. Start Docker Desktop. Copy `.env.example` to `.env` in this directory. Replace `LOGTO_DB_PASSWORD` with a long random alphanumeric value and `LOGTO_SECRET_VAULT_KEK` with an independently generated Base64-encoded 32-byte key. Do not commit `.env`. Keep the KEK stable and include it in private backups; changing it can make encrypted Logto connector secrets unreadable.
2. Run `docker compose --env-file .env -f compose.yml up -d` from this directory. The Logto container runs the official database seed command before starting. First start may take several minutes.
3. Open `http://localhost:3002` and create the initial administrator. The authentication endpoint is `http://localhost:3001`.
4. In the Logto Console, create a **Traditional web app** for the Spring Boot backend. Register `http://127.0.0.1:5173/api/public/auth/logto/callback` as its redirect URI. Store its App ID and App Secret only in `Qingfeng_AI_Music/config/local.properties`; use `http://localhost:3001/oidc` as the issuer. The exact application property names are in `Qingfeng_AI_Music/config/local.properties.example`.
5. Configure the sign-in experience for email and phone verification codes. Add an email connector and an SMS connector with working delivery provider credentials. Add a Google social connector with Google OAuth credentials if Google sign-in is needed through Logto. Test each method end to end before enabling it for users.

Useful checks: `docker compose --env-file .env -f compose.yml ps` and `docker compose --env-file .env -f compose.yml logs --tail=100 logto`. Stop with `docker compose --env-file .env -f compose.yml down`; the named PostgreSQL volume remains.

### Optional local email-flow test

To test Logto's email-code flow without an external mail provider, start the separate development-only Mailpit service from this directory:

```bash
docker compose --env-file .env -f compose.yml -f compose.dev-mail.yml --profile dev-mail up -d mailpit
```

In the local Logto Admin Console, configure its SMTP connector with host `mailpit`, port `1025`, no TLS or authentication, and a test sender such as `noreply@cheerfulmusic.test`. The Logto container and Mailpit share the Compose network; SMTP port 1025 is **not** published to the host. Open `http://127.0.0.1:8025` to read captured test codes. Use disposable test addresses only. Mailpit captures messages locally and **does not deliver mail**. This verifies the OIDC and verification-code flow, not real email delivery; do not copy this connector to production or enable the production `qingfeng.auth.logto.email-enabled` flag based on this test. Stop just the mail sink with `docker compose --env-file .env -f compose.yml -f compose.dev-mail.yml --profile dev-mail stop mailpit`.

## Production prerequisites

- Create separate HTTPS domains for authentication and administration, for example `auth.cheerfulmusic.com` and `auth-admin.cheerfulmusic.com`, with DNS records and valid certificates. In the private `.env`, set `LOGTO_ENDPOINT` and `LOGTO_ADMIN_ENDPOINT` to those exact URLs and `LOGTO_TRUST_PROXY_HEADER=1`.
- Configure Nginx to proxy the two domains to `127.0.0.1:3001` and `127.0.0.1:3002` respectively. Preserve the original `Host`, `X-Forwarded-Host`, `X-Forwarded-For`, and `X-Forwarded-Proto` headers. The Admin Console should have restricted access appropriate for administrators. Do not expose the Compose ports or PostgreSQL directly to the public network.
- Create a separate production Traditional web app in Logto with redirect URI `https://cheerfulmusic.com/api/public/auth/logto/callback`. Keep the App Secret in `/etc/qingfeng-music/application.properties` or another private server configuration file. Configure the backend issuer to the `issuer` in `https://auth.cheerfulmusic.com/oidc/.well-known/openid-configuration` (normally `https://auth.cheerfulmusic.com/oidc`); `LOGTO_ENDPOINT` is the root URL without `/oidc`.
- Back up the Logto PostgreSQL database and private deployment configuration (including `LOGTO_SECRET_VAULT_KEK`) separately from the music application's MySQL database. The existing `Qingfeng_AI_Music/deploy/backup.sh` does not cover Logto. Verify a restore before production rollout. Check available server memory and disk space; Logto recommends at least 2 vCPU, 8 GiB RAM, and 256 GiB disk for hosting.
- Before changing the pinned Logto image version, back up PostgreSQL and follow the Logto database alteration procedure for that version. Do not use the upstream demonstration Compose file for production; its database is not persistent.

Email and SMS delivery require provider accounts and credentials; Google social login requires Google OAuth credentials and the connector redirect URI to be added to the Google client. This Compose file does not create those external accounts.

## Production rollout

1. Create A records for `auth.cheerfulmusic.com` and `auth-admin.cheerfulmusic.com`, both pointing to the current site server `47.84.5.172`; verify the address still matches the production server before changing DNS. Keep Docker's 3001 and 3002 bindings on `127.0.0.1`; leave PostgreSQL unpublished. Confirm Docker, Nginx, Certbot, free disk, and memory on the server before starting.
2. Copy `compose.yml` and `.env.example` to a private directory such as `/opt/qingfeng-logto`, and edit `.env` there as root. Set distinct random `LOGTO_DB_PASSWORD` and Base64-encoded 32-byte `LOGTO_SECRET_VAULT_KEK`, the two public HTTPS endpoint URLs, and `LOGTO_TRUST_PROXY_HEADER=1`. Protect `.env` with mode `600` and include it in root-only backups. Never copy the local development `.env` or local user database into production.
3. Install only the two **port 80** server blocks from `logto.nginx.conf` first, then run `nginx -t && systemctl reload nginx`. With both DNS records pointing to this server and the ACME webroot available, request one certificate covering both hosts:

   ```bash
   install -d -m 755 /var/lib/qingfeng-acme
   certbot certonly --webroot -w /var/lib/qingfeng-acme \
     --cert-name cheerfulmusic-logto \
     -d auth.cheerfulmusic.com -d auth-admin.cheerfulmusic.com
   ```

4. Replace the temporary port 80 blocks with all of `logto.nginx.conf`. Its Admin Console host denies non-loopback clients by default. Add an `allow <trusted administrator IP>/32;` rule ahead of `deny all;`, or use a VPN or SSH tunnel. Run `nginx -t && systemctl reload nginx`. Check certificate renewal with `certbot renew --dry-run`. The reverse proxy forwards the original host and scheme and does not log authorization URLs or one-time callback codes.

   To reach the default loopback-only Admin Console by SSH tunnel, temporarily map `auth-admin.cheerfulmusic.com` to `127.0.0.1` in your **local** hosts file (Windows: `C:\Windows\System32\drivers\etc\hosts`; Linux/macOS: `/etc/hosts`), then run the following from your computer and open `https://auth-admin.cheerfulmusic.com` in its browser:

   ```bash
   ssh -N -L 127.0.0.1:443:127.0.0.1:443 <ssh-user>@47.84.5.172
   ```

   The local port 443 must be free and may need elevated privileges. This preserves the HTTPS hostname and certificate while Nginx sees a loopback client. Remove the temporary hosts entry when finished. An administrator-IP allow rule or VPN is simpler when port 443 is unavailable.
5. Start the pinned containers from `/opt/qingfeng-logto`:

   ```bash
   docker compose --env-file .env -f compose.yml up -d
   docker compose --env-file .env -f compose.yml ps
   curl -fsS https://auth.cheerfulmusic.com/oidc/.well-known/openid-configuration
   ```

   Confirm that the discovery document's `issuer` is `https://auth.cheerfulmusic.com/oidc`. The Admin Console is reachable only from the allowed administrator address. Create a production Logto Traditional web app with `https://cheerfulmusic.com/api/public/auth/logto/callback` as its redirect URI. Place its App ID and App Secret in the existing server-private Spring configuration; set `qingfeng.auth.logto.issuer` to the verified issuer. Keep the existing Google OAuth path working while testing Logto identities.
   Set Logto's production company logo URL to `https://cheerfulmusic.com/brand/logo-symbol.svg`; the local `127.0.0.1` logo URL is only reachable from the developer machine.
6. Configure and test the email and SMS delivery connectors before enabling those sign-in methods. In Logto Sign-in & account, use **Email address or phone number** as the sign-up identifier and enable each verification-code sign-in method. Test fresh account creation and returning sign-in for each channel, the callback to `/account`, creator/work retention, and logout. Enable the corresponding application feature flags only after these tests pass.

### Apple login

Apple's web flow cannot return to `http://localhost` or any HTTP callback. The public `auth.cheerfulmusic.com` DNS record, valid HTTPS certificate, and reachable Logto endpoint must be live **before** configuring or testing the Apple return URL. The Apple Developer Program account needs an App ID with Sign in with Apple enabled and a Services ID. In the Services ID, enter `auth.cheerfulmusic.com` as the domain and the **exact** HTTPS Return URL displayed by the Logto Apple connector, normally `https://auth.cheerfulmusic.com/callback/<connector-id>`. That URL is different from the Spring application's Logto callback. Set the Apple scope to include `email` before the first real sign-in; users may still choose a relay email address. Enable the Apple connector in Logto's sign-in settings only after an end-to-end HTTPS test.

### Back up and recover

The existing music-app backup timer does not cover this PostgreSQL database. From `/opt/qingfeng-logto` as root, take a dedicated backup before rollout and before every Logto upgrade. Do not print or upload `.env` contents:

```bash
set -euo pipefail
umask 077
BACKUP_DIR="/var/backups/qingfeng-logto/$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 700 "$BACKUP_DIR"
docker compose --env-file .env -f compose.yml exec -T postgres \
  pg_dump -U logto -d logto -Fc > "$BACKUP_DIR/logto.dump"
test -s "$BACKUP_DIR/logto.dump"
cp .env compose.yml "$BACKUP_DIR/"
docker compose --env-file .env -f compose.yml exec -T postgres \
  pg_restore --list < "$BACKUP_DIR/logto.dump" > /dev/null
sha256sum "$BACKUP_DIR/logto.dump" "$BACKUP_DIR/.env" > "$BACKUP_DIR/SHA256SUMS"
```

Rehearse a real restore into a disposable database or isolated staging instance, then verify its `tenants` and `users` tables before relying on the backup. For an application-only rollback, restore the previous Spring JAR and disable `qingfeng.auth.logto.enabled` if necessary; leave the Logto volume intact. For a Logto image or schema rollback, stop the service and restore a matching **database dump, image version, and `.env` including the KEK** together. Do not run `docker compose down -v`. Follow Logto's version-specific alteration instructions before upgrading the pinned image.

Official references: [Logto OSS setup](https://docs.logto.io/logto-oss/get-started-with-oss), [deployment and reverse proxy](https://docs.logto.io/logto-oss/deployment-and-configuration), [Apple connector](https://docs.logto.io/integrations/apple), [upgrading](https://docs.logto.io/logto-oss/upgrading-oss-version).
