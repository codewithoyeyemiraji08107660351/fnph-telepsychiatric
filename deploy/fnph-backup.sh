                    #!/usr/bin/env bash
                    #
                    # Nightly backup of the FNPH telepsychiatry database and uploaded files.
                    #
                    # Written for the Docker Compose stack on the Hostinger VPS. Two things differ
                    # from a bare-metal backup and both matter:
                    #
                    #   mysqldump runs inside the container, because MySQL publishes no port. That
                    #   is deliberate: a database holding psychiatric records must not be reachable
                    #   from the internet, so it is reachable only through `docker exec`.
                    #
                    #   Storage is a named Docker volume, not a host directory, so it is archived
                    #   through a throwaway container that mounts it. Reaching into
                    #   /var/lib/docker/volumes directly works until Docker changes its layout, and
                    #   then fails silently at 02:15.
                    #
                    # ON WHY BOTH, TOGETHER
                    #   A database dump alone restores issued_documents and file_uploads rows
                    #   pointing at files that do not exist. That looks like a working system until
                    #   a patient opens a prescription. Both are taken in one run under one
                    #   timestamp so a restore can pick a matching pair.
                    #
                    # ON WHAT THIS DOES NOT DO
                    #   These copies are on the same disk as the data. That covers a bad migration,
                    #   a mistaken delete, a botched deploy. It does not cover disk failure, the VPS
                    #   being lost, or ransomware, because all three take the backups with them.
                    #   Off-site copying is a separate decision and is not yet made: it needs FNPH
                    #   to say where Nigerian patient health data may be stored, which is the same
                    #   question the server's Manchester location already raises.
                    #
                    set -euo pipefail

                    STAMP=$(date +%Y-%m-%d_%H%M)
                    BACKUP_ROOT=/opt/fnph/backups
                    DB_CONTAINER=fnph-mysql
                    APP_CONTAINER=fnph-app
                    STORAGE_VOLUME=fnph_storage
                    DB_NAME=telepsychiatric
                    KEEP_DAYS=14
                    # Under this and the dump is not a database. An empty schema dumps to roughly
                    # 40 KB gzipped; 27 migrations' worth of structure is well past 100 KB.
                    MIN_DUMP_BYTES=100000

                    mkdir -p "$BACKUP_ROOT"

                    log() { echo "$(date -Is) $*"; }

                    fail() {
                        log "FNPH BACKUP FAILED: $*"
                        exit 1
                    }

                    # ---------------------------------------------------------------------------
                    # Database
                    # ---------------------------------------------------------------------------

                    # --single-transaction takes a consistent snapshot without locking, so the
                    # application keeps serving during the dump. InnoDB only, which is every table
                    # here.
                    #
                    # --routines --triggers --events because a schema restored without them is not
                    # the schema the application expects.
                    #
                    # Credentials come from a defaults file inside the container, never the command
                    # line: anything on a command line is visible to every user on the box through
                    # `ps`, and this morning's warnings showed exactly how MySQL feels about that.
                    log "dumping $DB_NAME"
                    docker exec "$DB_CONTAINER" \
                        mysqldump --defaults-extra-file=/etc/mysql/backup.cnf \
                            --single-transaction \
                            --routines --triggers --events \
                            --databases "$DB_NAME" \
                        | gzip > "$BACKUP_ROOT/db_${STAMP}.sql.gz"

                    DB_SIZE=$(stat -c%s "$BACKUP_ROOT/db_${STAMP}.sql.gz")
                    if [ "$DB_SIZE" -lt "$MIN_DUMP_BYTES" ]; then
                        # Removed rather than left behind. A truncated dump sitting next to good
                        # ones is worse than no dump, because someone restores it.
                        rm -f "$BACKUP_ROOT/db_${STAMP}.sql.gz"
                        fail "database dump was only ${DB_SIZE} bytes"
                    fi

                    # ---------------------------------------------------------------------------
                    # Storage
                    # ---------------------------------------------------------------------------

                    # Storage second, after the dump, so any file referenced by a row in the dump
                    # already exists in the archive. The other order can produce a dump referencing
                    # a file uploaded moments later and not captured.
                    #
                    # A throwaway alpine container mounts the volume read-only and tars it to
                    # stdout. Read-only because a backup has no business writing to the thing it is
                    # reading, and --rm because a stopped container a night is 400 of them a year.
                    log "archiving $STORAGE_VOLUME"
                    docker run --rm \
                        -v "${STORAGE_VOLUME}:/data:ro" \
                        alpine:3.20 \
                        tar -czf - -C /data . \
                        > "$BACKUP_ROOT/storage_${STAMP}.tar.gz"

                    STORAGE_SIZE=$(stat -c%s "$BACKUP_ROOT/storage_${STAMP}.tar.gz")
                    # No minimum on storage: an empty storage volume is legitimate before the first
                    # consultation, and failing the backup for it would mean the first two weeks
                    # have no database backup either.
                    log "storage archive $(numfmt --to=iec "$STORAGE_SIZE")"

                    # ---------------------------------------------------------------------------
                    # Retention
                    # ---------------------------------------------------------------------------

                    # Both files or neither. A retention pass that deletes one half of a pair
                    # leaves a backup that cannot be restored, which is the same as no backup
                    # except it looks like one in a directory listing.
                    find "$BACKUP_ROOT" -name 'db_*.sql.gz'      -mtime +${KEEP_DAYS} -delete
                    find "$BACKUP_ROOT" -name 'storage_*.tar.gz' -mtime +${KEEP_DAYS} -delete

                    # ---------------------------------------------------------------------------
                    # Disk guard
                    # ---------------------------------------------------------------------------

                    # 100 GB total, and the database, the images and fourteen days of backups share
                    # it. A full disk stops MySQL writing, which stops consultations, and the cause
                    # is a backup script nobody was watching.
                    USED=$(df --output=pcent /opt/fnph | tail -1 | tr -dc '0-9')
                    if [ "$USED" -gt 80 ]; then
                        log "WARNING: disk is ${USED}% full. Reduce KEEP_DAYS or move backups off the box."
                    fi

                    log "backup ok: db_${STAMP}.sql.gz ($(numfmt --to=iec "$DB_SIZE")), storage_${STAMP}.tar.gz ($(numfmt --to=iec "$STORAGE_SIZE"))"