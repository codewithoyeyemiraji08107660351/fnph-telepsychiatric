# Docker setup, start to finish

Everything needed to go from a machine with nothing installed to a running FNPH
Telepsychiatry backend, and back again. Written for Windows PowerShell first,
because that is the workstation this project is built on, with Linux and macOS
equivalents beside each step.

If you only want the short version, jump to [Quick start](#8-quick-start-once-installed).

---

## Contents

1. [What you are installing and why](#1-what-you-are-installing-and-why)
2. [Install Docker](#2-install-docker)
3. [Get the code](#3-get-the-code)
4. [Create your environment file](#4-create-your-environment-file)
5. [Start the stack](#5-start-the-stack)
6. [Verify each service](#6-verify-each-service)
7. [Run the application](#7-run-the-application)
8. [Quick start, once installed](#8-quick-start-once-installed)
9. [Working day to day](#9-working-day-to-day)
10. [Backup and restore](#10-backup-and-restore)
11. [Running the tests](#11-running-the-tests)
12. [Troubleshooting](#12-troubleshooting)
13. [Resetting everything](#13-resetting-everything)
14. [What changes for production](#14-what-changes-for-production)

---

## 1. What you are installing and why

Four services. Each one exists because the application genuinely needs it, not
because it is fashionable.

| Service | Image | Port | Why it is here |
|---|---|---|---|
| MySQL | `mysql:8.4` | 3306 | The database. 8.4 is the current long-term support release, so it is supported until 2032. |
| Redis | `redis:7.4-alpine` | 6379 | Slot holds, distributed locks, rate limiting. Two patients claiming the same appointment slot at the same moment is an acceptance-gate test, and it cannot be solved correctly in a single application process once there are two. |
| RabbitMQ | `rabbitmq:3.13-management` | 5672, 15672 | Payment webhooks, notifications, document generation. Anything that must survive a restart and retry on failure. |
| The application | built locally | 8080 | Spring Boot. Runs on your machine, not in a container, so the debugger attaches normally. |

The application deliberately stays outside Docker during development.
Containerising it costs a rebuild on every code change and buys nothing while
you are the only person running it. Production is different; see section 14.

**A note on why the MySQL settings are explicit.** `docker/mysql/fnph.cnf`
overrides several server defaults. Each one prevents a specific defect:

- `sql-mode=STRICT_TRANS_TABLES,...` — without it, MySQL silently truncates an
  oversized value and writes a warning nobody reads. A clinical note quietly cut
  short is a patient safety issue, not a formatting problem.
- `character-set-server=utf8mb4` — MySQL's legacy `utf8` is three-byte and
  mangles anything outside the basic multilingual plane. Patient names here come
  from Hausa, Yoruba and Igbo among others.
- `default-time-zone='+00:00'` — the server stores UTC. Clients render West
  Africa Time. A server storing local time produces appointments that move when
  the host is rebuilt elsewhere.
- `log-bin` and `sync-binlog=1` — point-in-time recovery. A nightly dump alone
  gives a recovery point of up to 24 hours; the target is 15 minutes.

---

## 2. Install Docker

### Windows

Docker Desktop needs virtualisation. Check it is on first:

```powershell
Get-ComputerInfo -Property "HyperV*"
```

If `HyperVRequirementVirtualizationFirmwareEnabled` is `False`, enable
virtualisation in your BIOS or UEFI before continuing. It is usually called
Intel VT-x, AMD-V or SVM Mode.

Then install WSL 2, which Docker Desktop uses as its Linux kernel:

```powershell
wsl --install
```

**Restart your machine.** This step genuinely requires it.

After the restart, install Docker Desktop:

```powershell
winget install Docker.DockerDesktop
```

Launch Docker Desktop from the Start menu and wait for the whale icon in the
system tray to stop animating. First launch takes a few minutes.

Confirm:

```powershell
docker --version
docker compose version
docker run --rm hello-world
```

The last command should print a welcome message. If it does, Docker works.

### macOS

```bash
brew install --cask docker
open -a Docker
```

Wait for the menu bar icon to settle, then `docker run --rm hello-world`.

### Linux (Ubuntu or Debian)

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker
docker run --rm hello-world
```

The `usermod` line lets you run Docker without `sudo`. Log out and back in if
`newgrp` does not take effect.

### You also need a JDK

The application runs outside Docker, so it needs Java 21 or later locally.

```powershell
# Windows
winget install Microsoft.OpenJDK.21
```

```bash
# macOS
brew install openjdk@21

# Ubuntu
sudo apt install openjdk-21-jdk
```

Close and reopen your terminal, then:

```powershell
java -version
```

It must report 21 or higher. Anything lower and the build fails before it
starts.

---

## 3. Get the code

```powershell
git clone https://github.com/codewithoyeyemiraji08107660351/fnph-telepsychiatric.git
cd fnph-telepsychiatric
```

---

## 4. Create your environment file

Nothing has a default password in this project. Every secret is an environment
variable with no fallback, which means a missing value fails loudly at startup
rather than quietly running with `password123`.

Copy the template:

```powershell
Copy-Item .env.example .env
```

```bash
cp .env.example .env          # macOS, Linux
```

Now fill in the values that have none. Three matter for local development.

### Database passwords

Any value works locally. Generate rather than type:

```powershell
# Windows
$root = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | % {[char]$_})
$app  = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | % {[char]$_})
Write-Host "DB_ROOT_PASSWORD=$root"
Write-Host "DB_PASSWORD=$app"
```

```bash
# macOS, Linux
echo "DB_ROOT_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')"
echo "DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')"
```

Paste both into `.env`.

### JWT signing key

This one has a constraint: it is base64-decoded and fed to HMAC-SHA256, so it
must decode to at least 32 bytes. A short key throws at startup rather than
producing weak tokens, which is the correct behaviour but confusing if you do
not know why.

```powershell
# Windows
$bytes = New-Object byte[] 64
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
Write-Host "JWT_SECRET=$([Convert]::ToBase64String($bytes))"
```

```bash
# macOS, Linux
echo "JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')"
```

### Everything else

Leave Remita, S3, video and mail blank for now. Those services are not called
until their milestones land, and blank values are clearer than fake ones that
look real in a log.

### Confirm .env is ignored

```powershell
git check-ignore -v .env
```

It must print a line naming `.gitignore`. If it prints nothing, stop and fix
that before you commit anything. A signing key in git history is a signing key
you have to rotate.

---

## 5. Start the stack

```powershell
docker compose up -d
```

`-d` runs it in the background. First run pulls roughly 600 MB of images.

Watch it come up:

```powershell
docker compose ps
```

Wait until all three report `healthy`. MySQL takes 30 to 60 seconds on first
start because it initialises its data directory.

```
NAME            IMAGE                              STATUS
fnph-mysql      mysql:8.4                          Up (healthy)
fnph-rabbitmq   rabbitmq:3.13-management-alpine    Up (healthy)
fnph-redis      redis:7.4-alpine                   Up (healthy)
```

If something says `unhealthy` or `restarting`, go to section 12.

---

## 6. Verify each service

Do not skip this. Five commands now saves an hour of confusing application
errors later.

### MySQL is up and configured correctly

```powershell
docker exec -it fnph-mysql mysql -uroot -p"$env:DB_ROOT_PASSWORD" -e "SELECT VERSION();"
```

```bash
docker exec -it fnph-mysql mysql -uroot -p"$DB_ROOT_PASSWORD" -e "SELECT VERSION();"
```

Then confirm the settings the application depends on actually took effect. The
config file is mounted read-only, and a typo in it is silently ignored by MySQL:

```powershell
docker exec -it fnph-mysql mysql -uroot -p"$env:DB_ROOT_PASSWORD" -e "
SELECT @@character_set_server   AS charset,
       @@collation_server       AS collation,
       @@time_zone              AS timezone,
       @@sql_mode               AS sql_mode,
       @@log_bin                AS binlog_on;"
```

Expected:

| Setting | Must be |
|---|---|
| `charset` | `utf8mb4` |
| `collation` | `utf8mb4_unicode_ci` |
| `timezone` | `+00:00` |
| `sql_mode` | contains `STRICT_TRANS_TABLES` |
| `binlog_on` | `1` |

If any of these is wrong the application will still run, and you will find out
months later when a name is mangled or an appointment moves by an hour.

### The application account can connect

```powershell
docker exec -it fnph-mysql mysql -u"$env:DB_USERNAME" -p"$env:DB_PASSWORD" -e "SHOW DATABASES;"
```

It should list `telepsychiatric`. If it lists nothing, the credentials in `.env`
do not match what MySQL was initialised with. See section 12.

### Redis

```powershell
docker exec -it fnph-redis redis-cli ping
```

Expect `PONG`.

### RabbitMQ

```powershell
docker exec -it fnph-rabbitmq rabbitmq-diagnostics -q ping
```

Expect `Ping succeeded`. The management interface is at
<http://localhost:15672> with the credentials from `.env`, defaulting to
`guest` / `guest`.

---

## 7. Run the application

```powershell
.\mvnw.cmd spring-boot:run
```

```bash
./mvnw spring-boot:run
```

On the first run Flyway applies every migration in order. Watch for this in the
log:

```
Successfully validated 5 migrations
Migrating schema `telepsychiatric` to version "1 - baseline schema"
Migrating schema `telepsychiatric` to version "2 - seed centres"
Migrating schema `telepsychiatric` to version "3 - schema conventions"
Migrating schema `telepsychiatric` to version "4 - denormalise tenant key"
Migrating schema `telepsychiatric` to version "5 - role permission model"
Successfully applied 5 migrations
```

Then Hibernate validates every entity against the migrated schema. If an entity
and a column have drifted apart, startup fails here with the exact mismatch.
That is deliberate: it is the earliest and cheapest moment to find out.

Confirm the service is alive:

```powershell
curl.exe http://localhost:8080/actuator/health
```

Expect `{"status":"UP"}`.

Then check the data landed:

```powershell
docker exec -it fnph-mysql mysql -uroot -p"$env:DB_ROOT_PASSWORD" telepsychiatric -e "
SELECT (SELECT COUNT(*) FROM centres)         AS centres,
       (SELECT COUNT(*) FROM wallets)         AS wallets,
       (SELECT COUNT(*) FROM roles)           AS roles,
       (SELECT COUNT(*) FROM permissions)     AS permissions,
       (SELECT COUNT(*) FROM role_permission) AS grants;"
```

Expect 23 centres, 23 wallets, 16 roles, 126 permissions and 394 grants.

The API documentation is at <http://localhost:8080/swagger-ui.html>. Every
operation names the permission it requires.

---

## 8. Quick start, once installed

Everything above is one-time. Day to day it is two commands:

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

And to stop:

```powershell
docker compose stop
```

`stop` keeps your data. `down` removes the containers but keeps the volumes.
Only `down -v` destroys data, which is why it is in its own section.

---

## 9. Working day to day

### Watch the logs

```powershell
docker compose logs -f mysql
docker compose logs -f          # everything, interleaved
docker compose logs --tail=100 mysql
```

### Open a database shell

```powershell
docker exec -it fnph-mysql mysql -uroot -p"$env:DB_ROOT_PASSWORD" telepsychiatric
```

### Inspect the permission matrix

Useful when you are working out why a role cannot do something:

```sql
-- What can a Pharmacist actually do?
SELECT p.module, p.code, p.description
FROM role_permission rp
  JOIN roles r       ON r.id = rp.role_id
  JOIN permissions p ON p.id = rp.permission_id
WHERE r.code = 'PHARMACIST'
ORDER BY p.module, p.code;

-- Which roles can approve an appointment?
SELECT r.code, r.name
FROM role_permission rp
  JOIN roles r       ON r.id = rp.role_id
  JOIN permissions p ON p.id = rp.permission_id
WHERE p.code = 'appointment.approve';

-- Everything one account can do, across all its roles
SELECT DISTINCT p.code
FROM user_role ur
  JOIN role_permission rp ON rp.role_id = ur.role_id
  JOIN permissions p      ON p.id = rp.permission_id
WHERE ur.user_id = 1
ORDER BY p.code;
```

### Redis

```powershell
docker exec -it fnph-redis redis-cli
# then: KEYS *      DBSIZE      FLUSHALL
```

`FLUSHALL` is safe here. Redis holds slot holds, rate limit counters and cache;
nothing authoritative. If it ever holds something you would miss, that is a
design defect, not a reason to be careful with this command.

### Restart one service

```powershell
docker compose restart mysql
```

### Apply a config change

`docker/mysql/fnph.cnf` is read at startup only. After editing it:

```powershell
docker compose restart mysql
```

Then re-run the settings check from section 6. MySQL ignores directives it does
not recognise rather than refusing to start, so a typo looks like nothing
happened.

---

## 10. Backup and restore

```powershell
.\scripts\backup.ps1
```

```bash
./scripts/backup.sh
```

Writes a compressed dump to `backups/` with a `.sha256` alongside it. The dump
records the binary log position, which is what makes point-in-time recovery
possible rather than just restoring to last night.

To restore into a scratch database, so you can check a backup without touching
your working one:

```powershell
.\scripts\restore.ps1 -BackupFile .\backups\telepsychiatric_20260907T090000Z.sql.zip -Into telepsychiatric_restore_test
```

```bash
./scripts/restore.sh backups/telepsychiatric_20260907T090000Z.sql.gz --into telepsychiatric_restore_test
```

The script verifies the checksum before restoring and refuses if it does not
match. It reports elapsed time, because the recovery time target is two hours
and the only way to know whether that is real is to measure it.

**Do this monthly and write down the time.** A backup that has never been
restored is an assumption.

---

## 11. Running the tests

The schema and permission tests start their own throwaway MySQL container
through Testcontainers, so Docker has to be running but the compose stack does
not.

```powershell
.\mvnw.cmd test
```

Just the schema and matrix suites:

```powershell
.\mvnw.cmd test "-Dtest=MigrationReplayTest,SchemaConventionsTest,PermissionMatrixTest,PublicIdTest"
```

What each one holds you to:

- **MigrationReplayTest** — every migration applies to an empty database,
  checksums match the files on disk, seed data landed, no version renumbered.
  This is the automated form of "drop the database and replay from zero".
- **SchemaConventionsTest** — every table carries the convention columns, is
  InnoDB and utf8mb4, uses `DATETIME` not `TIMESTAMP`, never stores money as a
  float, indexes every foreign key, and carries an indexed `centre_id` on every
  tenant-owned table.
- **PermissionMatrixTest** — every boundary from the specification. A pharmacist
  cannot read a clinical note, HIM cannot approve, a centre cannot see wallet
  amounts or another centre, the Central Administrator cannot write in a
  clinician's name.
- **PublicIdTest** — identifiers are 26 characters, do not collide across
  200,000 generations, sort by creation time, and contain no character that can
  be misread.

Testcontainers pulls `mysql:8.4` on first run. It reuses the layers the compose
stack already downloaded, so it is quick after that.

---

## 12. Troubleshooting

### Port 3306 is already in use

Something else, usually a locally installed MySQL, holds the port.

```powershell
Get-NetTCPConnection -LocalPort 3306 | Select-Object OwningProcess
Get-Process -Id <pid>
```

```bash
sudo lsof -i :3306
```

Either stop it, or move this project to another port. In `.env`:

```
DB_PORT=3307
```

Then `docker compose down` and `docker compose up -d`. The application reads
`DB_PORT` too, so nothing else needs changing.

### MySQL keeps restarting

```powershell
docker compose logs mysql --tail=50
```

Two usual causes.

**A typo in `fnph.cnf`.** MySQL refuses to start on an unparseable config file.
The log names the line.

**Stale volume with different credentials.** MySQL only reads
`MYSQL_ROOT_PASSWORD` when it initialises an empty data directory. If you
changed the password in `.env` after the first run, the volume still has the old
one. Fix by destroying the volume, which loses local data:

```powershell
docker compose down -v
docker compose up -d
```

### Application cannot connect to the database

Check the container is healthy first:

```powershell
docker compose ps
```

If it is healthy, the problem is credentials or host. Two things catch people:

- Your `.env` is read by Docker Compose, but `./mvnw spring-boot:run` does not
  read `.env` automatically. Either export the variables into your shell, or use
  an IDE run configuration that loads the file, or install a dotenv plugin.
- The application connects to `localhost`, not to the container name. Container
  names like `fnph-mysql` only resolve from inside the Docker network.

Load `.env` into a PowerShell session:

```powershell
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}
```

```bash
set -a && source .env && set +a
```

### Flyway reports a checksum mismatch

```
Migration checksum mismatch for migration version 3
```

A migration file was edited after being applied. Migrations are forward-only for
exactly this reason: the same version now means two different things in two
environments.

Locally, wipe and replay:

```powershell
docker compose down -v
docker compose up -d
.\mvnw.cmd spring-boot:run
```

Anywhere shared, do not do that. Revert the edit and write a new migration
instead.

### Hibernate says a column is missing or has the wrong type

```
Schema-validation: missing column [public_id] in table [patients]
```

The entity and the schema have drifted. `ddl-auto: validate` catches it at
startup rather than at the first query, which is the point.

Either the migration was not written, or it was not applied. Check what actually
ran:

```sql
SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank;
```

### JWT error at startup

```
The signing key's size is N bits which is not secure enough
```

`JWT_SECRET` is too short. It is base64-decoded before use, so it must decode to
at least 32 bytes. Regenerate it with the command in section 4.

### Docker Desktop will not start on Windows

Usually WSL. In order:

```powershell
wsl --status
wsl --update
wsl --shutdown
```

Then restart Docker Desktop. If it still fails, virtualisation is probably off
in BIOS. Recheck with the `Get-ComputerInfo` command in section 2.

### Everything is slow on Windows

Keep the repository on the Windows filesystem, not inside `\\wsl$`. Cross-boundary
file access between Windows and WSL is slow enough to be noticeable on every
build.

In Docker Desktop settings, give it at least 4 GB of memory. MySQL alone reserves
512 MB for its buffer pool.

---

## 13. Resetting everything

Three levels, in increasing severity.

**Stop, keep everything:**

```powershell
docker compose stop
```

**Remove containers, keep data:**

```powershell
docker compose down
```

**Destroy data and start clean:**

```powershell
docker compose down -v
docker compose up -d
```

`-v` removes the volumes. Every row in the database is gone: centres, roles,
permissions, anything you created. Locally that is fine, because the migrations
rebuild all of it in seconds. Never run it against anything shared.

**Reclaim disk from old images:**

```powershell
docker system prune -a --volumes
```

That affects every Docker project on the machine, not just this one.

---

## 14. What changes for production

This compose file is for development. It is not a production topology and should
not be adapted into one.

| Concern | Development | Production |
|---|---|---|
| Database | Container, single node | Dedicated host, primary plus replica, off-box encrypted backups with monitored restore tests |
| Application | On your machine, outside Docker | Two nodes behind Nginx, so a deploy does not drop connections |
| Object storage | Not configured | External S3-compatible storage. Never local disk: uploads must survive a redeploy and be readable by both nodes |
| Video | Not configured | Managed provider. Never self-hosted media on the clinical VPS |
| Secrets | `.env` on disk | Injected by the platform. No secret in a file that could be committed |
| Ports | Published to localhost | Only 443 exposed. Database, Redis and RabbitMQ on a private network |
| TLS | None | Enforced, HSTS on, certificates auto-renewed |
| Audit grants | Not applied | `db/ops/append_only_grants.sql` run by a DBA, so the application cannot alter the audit trail |
| Swagger | Enabled | Disabled. An unauthenticated map of every endpoint and its guard helps only an attacker |
| Backups | Manual, local | Scheduled, encrypted, off-site, restore tested monthly and timed |

The one rule that carries over unchanged: heavy media processing never runs on
the same host as the clinical portal.
