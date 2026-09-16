#!/usr/bin/env bash
#
# Restores a database and storage pair into a scratch schema and a scratch
# volume, so a restore can be rehearsed without touching live data.
#
# Usage:
#   fnph-restore.sh 2026-09-20_0215
#
# This deliberately does NOT restore over the live database. A restore script
# that overwrites production is one typo away from being the incident it exists
# to recover from. To go live with a restored copy: verify it here, stop the
# app, then rename the schemas by hand, as a decision somebody makes.
#
set -euo pipefail

STAMP=${1:?give a backup timestamp, for example 2026-09-20_0215}
BACKUP_ROOT=/opt/fnph/backups
DB_CONTAINER=fnph-mysql
SCRATCH_DB=telepsychiatric_restore
SCRATCH_VOLUME=fnph_storage_restore

DB_FILE="$BACKUP_ROOT/db_${STAMP}.sql.gz"
STORAGE_FILE="$BACKUP_ROOT/storage_${STAMP}.tar.gz"

# Both halves or nothing. Restoring a database without its files produces rows
# pointing at prescriptions that do not exist.
[ -f "$DB_FILE" ]      || { echo "no database backup for $STAMP"; exit 1; }
[ -f "$STORAGE_FILE" ] || { echo "no storage backup for $STAMP"; exit 1; }

echo "restoring database into $SCRATCH_DB"
docker exec -i "$DB_CONTAINER" mysql -u root -p"$DB_ROOT_PASSWORD" \
    -e "DROP DATABASE IF EXISTS $SCRATCH_DB; CREATE DATABASE $SCRATCH_DB;"

# The dump was taken with --databases, so it carries its own USE statement
# naming the original schema. Stripping it is what lets the same dump restore
# into a scratch name.
gzip -dc "$DB_FILE" \
    | sed '/^USE `/d; /^CREATE DATABASE/d' \
    | docker exec -i "$DB_CONTAINER" mysql -u root -p"$DB_ROOT_PASSWORD" "$SCRATCH_DB"

echo "restoring storage into $SCRATCH_VOLUME"
docker volume rm -f "$SCRATCH_VOLUME" >/dev/null 2>&1 || true
docker volume create "$SCRATCH_VOLUME" >/dev/null
docker run --rm -i -v "${SCRATCH_VOLUME}:/data" alpine:3.20 \
    tar -xzf - -C /data < "$STORAGE_FILE"

echo
echo "--- verification ---"
docker exec -i "$DB_CONTAINER" mysql -u root -p"$DB_ROOT_PASSWORD" "$SCRATCH_DB" <<SQL
SELECT version AS schema_version, installed_on
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
SELECT COUNT(*) AS users FROM users;
SELECT COUNT(*) AS centres FROM centres;
SELECT COUNT(*) AS appointments FROM appointments;
SELECT COUNT(*) AS issued_documents FROM issued_documents;
SQL

FILES=$(docker run --rm -v "${SCRATCH_VOLUME}:/data:ro" alpine:3.20 \
    sh -c 'find /data -type f | wc -l')
echo "storage files restored: $FILES"

echo
echo "Schema version 27 and plausible counts mean the backup is good."
echo "Scratch copies left in place for inspection. To clear them:"
echo "  docker exec -i $DB_CONTAINER mysql -u root -p\$DB_ROOT_PASSWORD -e 'DROP DATABASE $SCRATCH_DB;'"
echo "  docker volume rm $SCRATCH_VOLUME"