#!/usr/bin/env bash
# Backup produkcijske baze -> lokalna kopija na Dropletu + DigitalOcean Spaces (S3).
#
# Pokretanje (na Dropletu, iz /opt/grc-app):   bash ops/backup.sh
# Konfiguracija:
#   .env             -> DB_USER, DB_NAME (vec postoji)
#   ops/backup.env   -> Spaces kljucevi/bucket (kopiraj iz ops/backup.env.example; NIJE u gitu)
set -euo pipefail

# radi iz korijena projekta (roditelj mape ops/)
cd "$(dirname "$0")/.."

# ucitaj varijable (DB_* iz .env, Spaces iz backup.env)
set -a
[ -f .env ] && . ./.env
[ -f ops/backup.env ] && . ./ops/backup.env
set +a

: "${DB_USER:?DB_USER nije postavljen (.env)}"
: "${DB_NAME:?DB_NAME nije postavljen (.env)}"
: "${SPACES_BUCKET:?SPACES_BUCKET nije postavljen (ops/backup.env)}"
: "${SPACES_ENDPOINT:?SPACES_ENDPOINT nije postavljen (ops/backup.env)}"

COMPOSE="${COMPOSE_FILE:-docker-compose.prod.yml}"
STAMP="$(date +%F-%H%M%S)"
DIR="./backups"
FILE="$DIR/grc-$STAMP.sql.gz"
mkdir -p "$DIR"

echo "==> pg_dump baze '$DB_NAME' -> $FILE"
docker compose -f "$COMPOSE" exec -T db \
  pg_dump -U "$DB_USER" --clean --if-exists "$DB_NAME" | gzip -9 > "$FILE"

echo "==> upload -> s3://$SPACES_BUCKET/grc-$STAMP.sql.gz"
AWS_DEFAULT_REGION="${SPACES_REGION:-fra1}" \
aws s3 cp "$FILE" "s3://$SPACES_BUCKET/grc-$STAMP.sql.gz" \
  --endpoint-url "https://$SPACES_ENDPOINT"

echo "==> lokalna retencija: brisem dumpove starije od ${RETENTION_DAYS:-7} dana"
find "$DIR" -name 'grc-*.sql.gz' -mtime +"${RETENTION_DAYS:-7}" -delete

echo "==> gotovo: $FILE"
