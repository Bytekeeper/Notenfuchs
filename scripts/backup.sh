#!/usr/bin/env bash
# Nightly backup of a Notenfuchs instance: the database and, with Pocket ID, its logins.
# See the README section "Datensicherung" for cron, restore and copying the files elsewhere.
set -euo pipefail
umask 077

cd "$(dirname "$0")/.."
BACKUP_DIR=${BACKUP_DIR:-/var/backups/notenfuchs}
KEEP_DAYS=${KEEP_DAYS:-14}
stamp=$(date +%Y-%m-%d)
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# Encrypts for AGE_RECIPIENT if set, so the server never holds the key to read old backups.
store() {
    if [ -n "${AGE_RECIPIENT:-}" ]; then
        age -r "$AGE_RECIPIENT" -o "$2.age.tmp" "$1"
        rm "$1"
        mv "$2.age.tmp" "$2.age"
    else
        mv "$1" "$2"
    fi
}

docker compose exec -T postgres pg_dump -U notenfuchs -Fc notenfuchs > "$BACKUP_DIR/db.tmp"
store "$BACKUP_DIR/db.tmp" "$BACKUP_DIR/notenfuchs-$stamp.dump"

# Notenfuchs knows teachers by their Pocket ID account; a new Pocket ID would lock them out of their classes.
if docker compose ps --services | grep -qx pocket-id; then
    docker compose cp pocket-id:/app/data - > "$BACKUP_DIR/pocket-id.tmp"
    store "$BACKUP_DIR/pocket-id.tmp" "$BACKUP_DIR/pocket-id-$stamp.tar"
fi

find "$BACKUP_DIR" -name '*-????-??-??.*' -mtime +"$KEEP_DAYS" -delete
