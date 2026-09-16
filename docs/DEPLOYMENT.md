# Deploying the backend to the Hostinger VPS with Dokploy

Target: Hostinger KVM 2 (8 GB RAM, 2 vCPU, 100 GB), Ubuntu 24.04, Dokploy pre-installed, IPv4 `89.116.229.219`.

The whole backend runs as one Dokploy **Compose** service built from `docker-compose.prod.yml`:

| Container | Purpose | Reachable from the internet |
|---|---|---|
| `backend` | Spring Boot API | Yes, only through Dokploy's proxy on HTTPS |
| `mysql` | Database | No |
| `redis` | Configuration cache | No |
| `rabbitmq` | Background jobs | No |

Replace `api.example.org` below with the real API address.

---

## 1. Before you start

1. **Push the code.** Dokploy builds from GitHub, not from your laptop. Every file you copied in by hand has to be committed and pushed first:
   ```bash
   git status
   git add -A
   git commit -m "Production-ready backend"
   git push
   ```
   Check that `git status` does **not** list `.env` or any `.env.production`. They must never be committed.
2. **Check your admin bootstrap migration.** Your local `V28__bootstrap_administrator.sql` must be committed, or production has no administrator. If it contains a password or password hash, treat that password as known: change it at first sign-in.
3. **Run the tests locally:** `./mvnw clean test`. Fix anything red before deploying.
4. **Generate fresh secrets.** Never reuse development or previously leaked values. On any machine with openssl:
   ```bash
   openssl rand -base64 64   # JWT_SECRET
   openssl rand -base64 32   # ENCRYPTION_KEY (set once, never change it)
   openssl rand -hex 24      # run 6 times: DB_PASSWORD, DB_ROOT_PASSWORD, REDIS_PASSWORD, RABBITMQ_PASSWORD, REMITA_WEBHOOK_SECRET, spare
   ```
   Store them in a password manager. Losing `ENCRYPTION_KEY` locks every staff member out of their authenticator.

## 2. Point the domain at the server

In your DNS provider (Cloudflare for `adeolaforgehq.com`, or FNPH's provider):

| Type | Name | Value | Proxy |
|---|---|---|---|
| A | `api` | `89.116.229.219` | **DNS only** (grey cloud) |

Keep it DNS only until the certificate is issued in step 6. Check it resolves: `nslookup api.example.org` shows `89.116.229.219`.

## 3. Open the firewall

In Hostinger hPanel, go to VPS, then **Firewall**, and allow inbound TCP **22**, **80**, **443** and **3000**. Port 3000 is the Dokploy dashboard; close it again in step 9.

## 4. Connect Dokploy to GitHub

1. Open `http://89.116.229.219:3000` and sign in (create the admin account on first visit).
2. **Settings**, then **Git**, then **GitHub**, then **Create GitHub App**. Follow the prompts.
3. Install the app on the GitHub account that owns `fnph-telepsychiatric` and give it access to that repository. If the repository belongs to someone else, they must install it, or you work from a fork you own.

## 5. Create the service

1. **Projects**, then **Create Project**, name it `FNPH`.
2. Inside it: **Create Service**, then **Compose**. Name: `backend-stack`.
3. **General** tab:
   - Provider: **GitHub**
   - Repository: `fnph-telepsychiatric`
   - Branch: the branch you pushed
   - Compose Path: `./docker-compose.prod.yml`
   - **Save**
4. **Environment** tab: paste the contents of `.env.production.example`, replace every `change-me` and every `example.org` with real values, and **Save**.
   - `APP_BASE_URL=https://api.example.org`
   - `APP_PORTAL_URL` and `CORS_ALLOWED_ORIGINS`: the web app's address. If the web app isn't deployed yet, use its planned address and update it later.
5. **Deploy**. Open **Logs**. The first build takes 5 to 10 minutes. The first start runs every database migration; look for:
   ```
   Successfully applied ... migrations to schema `telepsychiatric`
   Started TelepsychiatricApplication
   ```

## 6. Attach the domain and HTTPS

1. **Domains** tab, then **Add Domain**:
   - Service Name: `backend`
   - Host: `api.example.org`
   - Path: `/`
   - Container Port: `8080`
   - HTTPS: **on**
   - Certificate: **Let's Encrypt**
2. **Save**, then **Deploy** again so the routing is applied.
3. Test from your computer:
   ```bash
   curl https://api.example.org/actuator/health
   # {"status":"UP"}
   curl https://api.example.org/api/v1/public/settings
   # JSON with the emergency number and fee
   ```
4. Once HTTPS works, you may switch the Cloudflare record to **Proxied** (orange cloud). If you do, set Cloudflare SSL/TLS mode to **Full (strict)**.

## 7. First sign-in

1. Sign in with the administrator from your bootstrap migration.
2. Change the password and enrol the authenticator (MFA is required).
3. In the app: publish the consent text and safety questions, upload and activate the EHR export, create rooms and consultation days.

## 8. Backups

The database and uploaded files must be backed up together. On the VPS, as root:

```bash
ssh root@89.116.229.219
# Dokploy keeps the repository checkout under /etc/dokploy/compose
find /etc/dokploy/compose -name backup-production.sh | head -1
cp <that path> /usr/local/bin/fnph-backup
chmod 700 /usr/local/bin/fnph-backup
/usr/local/bin/fnph-backup           # run once now and check the output
crontab -e                           # add the line below, then save
15 1 * * * /usr/local/bin/fnph-backup >> /var/log/fnph-backup.log 2>&1
```

Backups land in `/var/backups/fnph` and are kept 14 days. Copy them off the server regularly. Hostinger's weekly snapshot alone is not enough for clinical records.

To restore:
```bash
gunzip -c /var/backups/fnph/db-<stamp>.sql.gz | docker exec -i <mysql container> sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
docker exec -i <backend container> tar -C /var/lib/fnph -xzf - < /var/backups/fnph/storage-<stamp>.tar.gz
```

## 9. Lock down

1. In Dokploy **Settings**, attach a domain to the dashboard itself (for example `deploy.example.org`) with HTTPS.
2. Then remove port **3000** from the Hostinger firewall.
3. Use SSH keys and disable password login for SSH.

## 10. Updating

Push to the branch, then **Deploy** in Dokploy (or enable **Auto Deploy** on the General tab). New migrations run on start. Never edit an applied migration; add a new one.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Build fails at `mvn dependency:go-offline` | Network or a plugin that can't resolve offline | Redeploy. If it repeats, send the log |
| `Could not resolve placeholder 'X'` | A required variable is missing | Add `X` in the Environment tab, redeploy |
| `Found more than one migration with version N` | Two migrations share a number | Rename the newer one to the next free number |
| Backend restarts repeatedly | Out of memory or a startup error | Logs tab; the first `ERROR` line names it |
| `404 page not found` on the domain | Domain not applied | Check the Domains tab (service `backend`, port `8080`), redeploy |
| Certificate not issued | DNS not pointing here, or Cloudflare proxy on | Grey cloud, wait for DNS, redeploy |
| Browser shows CORS errors | Web app address not allowed | Set `CORS_ALLOWED_ORIGINS` exactly, redeploy |

## Not ready for real patients until

- **Payments:** real Remita credentials. With placeholders the app starts but cannot take a payment.
- **Video:** a Daily.co API key and domain. Without them, consultations cannot open a room.
- **SMS:** patients whose hospital record has only a phone number have their enrolment code written to the server log. Anyone with log access could enrol as them. Add an SMS provider, or enrol those patients at a desk, before launch.
- **The web app:** deployed at the address in `APP_PORTAL_URL`.
