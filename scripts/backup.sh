#!/usr/bin/env bash
# Full logical backup with a recorded binary log position.
#
# The position matters more than the dump. Restoring the dump alone rewinds
# to last night; replaying the binary log from the recorded position brings
# it forward to any chosen moment. That is what makes a 15-minute recovery
# point achievable rather than aspirational.
#
#   ./scripts/backup.sh
#   ./scripts/backup.sh --output /mnt/backups

set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-telepsychiatric}"
DB_USER="${DB_BACKUP_USER:-root}"
DB_PASS="${DB_BACKUP_PASSWORD:-${DB_ROOT_PASSWORD:-}}"
OUT_DIR="./backups"
RETAIN_DAYS="${BACKUP_RETAIN_DAYS:-30}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUT_DIR="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$DB_PASS" ]]; then
  echo "ERROR: set DB_BACKUP_PASSWORD or DB_ROOT_PASSWORD" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="$OUT_DIR/${DB_NAME}_${STAMP}.sql"

echo "Backing up ${DB_NAME} to ${DUMP}"

# --single-transaction  consistent snapshot without locking the application out
# --source-data=2       writes the binary log position into the dump as a comment
# --routines --triggers --events  schema objects a plain dump would silently drop
# --hex-blob            binary-safe for checksums and stored keys
mysqldump \
  --host="$DB_HOST" --port="$DB_PORT" \
  --user="$DB_USER" --password="$DB_PASS" \
  --single-transaction \
  --source-data=2 \
  --routines --triggers --events \
  --hex-blob \
  --default-character-set=utf8mb4 \
  --databases "$DB_NAME" > "$DUMP"

gzip -9 "$DUMP"
DUMP="${DUMP}.gz"

# A checksum turns "the file exists" into "the file is intact".
sha256sum "$DUMP" > "${DUMP}.sha256"

SIZE=$(du -h "$DUMP" | cut -f1)
echo "Done: ${DUMP} (${SIZE})"
echo "Checksum: $(cut -d' ' -f1 < "${DUMP}.sha256")"

echo "Binary log position recorded in the dump:"
zcat "$DUMP" | grep -m1 "CHANGE REPLICATION SOURCE\|CHANGE MASTER" || echo "  (none found; check that log-bin is enabled)"

echo "Pruning backups older than ${RETAIN_DAYS} days"
find "$OUT_DIR" -name "${DB_NAME}_*.sql.gz*" -mtime "+${RETAIN_DAYS}" -delete 2>/dev/null || true

cat <<'REMINDER'

A backup on the same host as the database is not a backup. Copy this file
off-box, encrypted, before you consider the job done.
REMINDER
