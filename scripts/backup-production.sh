#!/usr/bin/env bash
#
# Nightly backup of the FNPH production stack: database and stored files
# together. A database restored without its files leaves every document row
# pointing at nothing, and the reverse leaves files nobody can find.
#
# Install on the VPS (as root):
#   cp scripts/backup-production.sh /usr/local/bin/fnph-backup && chmod 700 /usr/local/bin/fnph-backup
#   crontab -e   and add:   15 1 * * * /usr/local/bin/fnph-backup >> /var/log/fnph-backup.log 2>&1
#
# Copy /var/backups/fnph off the server as well (Hostinger's weekly snapshot
# is not enough on its own for clinical records).

set -euo pipefail

BACKUP_DIR=/var/backups/fnph
KEEP_DAYS=14
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

# Dokploy names containers <project>-<service>-1; match by service name.
MYSQL=$(docker ps --format '{{.Names}}' | grep -E -- '-mysql-[0-9]+$' | head -n1)
BACKEND=$(docker ps --format '{{.Names}}' | grep -E -- '-backend-[0-9]+$' | head -n1)
if [ -z "$MYSQL" ] || [ -z "$BACKEND" ]; then
  echo "$(date -u) could not find the mysql or backend container" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# Consistent InnoDB snapshot without locking the tables.
docker exec "$MYSQL" sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  | gzip > "$BACKUP_DIR/db-$STAMP.sql.gz"

docker exec "$BACKEND" tar -C /var/lib/fnph -czf - storage > "$BACKUP_DIR/storage-$STAMP.tar.gz"

find "$BACKUP_DIR" -type f -mtime +"$KEEP_DAYS" -delete
echo "$(date -u) backup $STAMP done: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR"
