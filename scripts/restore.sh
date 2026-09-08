#!/usr/bin/env bash
# Restore a backup into a named database, verify the checksum first, and
# report how long it took.
#
# The elapsed time is the point. The recovery time target is two hours; the
# only way to know whether that is real is to measure a restore on the
# hardware you actually run.
#
#   ./scripts/restore.sh backups/telepsychiatric_20260906T120000Z.sql.gz
#   ./scripts/restore.sh <file> --into telepsychiatric_restore_test

set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_BACKUP_USER:-root}"
DB_PASS="${DB_BACKUP_PASSWORD:-${DB_ROOT_PASSWORD:-}}"

BACKUP_FILE="${1:-}"
TARGET_DB=""
shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --into) TARGET_DB="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$BACKUP_FILE" || ! -f "$BACKUP_FILE" ]]; then
  echo "Usage: $0 <backup.sql.gz> [--into <database>]" >&2
  exit 2
fi
if [[ -z "$DB_PASS" ]]; then
  echo "ERROR: set DB_BACKUP_PASSWORD or DB_ROOT_PASSWORD" >&2
  exit 1
fi

if [[ -f "${BACKUP_FILE}.sha256" ]]; then
  echo "Verifying checksum"
  sha256sum -c "${BACKUP_FILE}.sha256" || { echo "CHECKSUM FAILED. Do not restore this file." >&2; exit 1; }
else
  echo "WARNING: no .sha256 alongside this backup; integrity unverified"
fi

START=$(date +%s)

if [[ -n "$TARGET_DB" ]]; then
  # Restoring into a different name: strip the CREATE/USE lines from the dump
  # so it lands where we asked rather than overwriting production.
  echo "Restoring into ${TARGET_DB}"
  mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password="$DB_PASS" \
    -e "DROP DATABASE IF EXISTS \`${TARGET_DB}\`; CREATE DATABASE \`${TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  zcat "$BACKUP_FILE" \
    | grep -v '^CREATE DATABASE' \
    | grep -v '^USE ' \
    | mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password="$DB_PASS" "$TARGET_DB"
else
  echo "Restoring into the database named in the dump"
  zcat "$BACKUP_FILE" | mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password="$DB_PASS"
fi

ELAPSED=$(( $(date +%s) - START ))
echo "Restore completed in ${ELAPSED}s"
if (( ELAPSED > 7200 )); then
  echo "WARNING: exceeded the two-hour recovery time target"
fi

VERIFY_DB="${TARGET_DB:-$(zcat "$BACKUP_FILE" | grep -m1 '^USE ' | sed "s/USE \`\(.*\)\`;/\1/")}"
echo "Verifying ${VERIFY_DB}"
mysql --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --password="$DB_PASS" -N -e "
SELECT CONCAT('  tables: ', COUNT(*)) FROM information_schema.TABLES WHERE TABLE_SCHEMA='${VERIFY_DB}';
SELECT CONCAT('  centres: ', COUNT(*)) FROM \`${VERIFY_DB}\`.centres;
SELECT CONCAT('  flyway version: ', MAX(version)) FROM \`${VERIFY_DB}\`.flyway_schema_history WHERE success=1;"
