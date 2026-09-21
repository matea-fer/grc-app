#!/usr/bin/env bash
# Restore dumpa u CILJANU bazu (lokalni staging ili throwaway provjera).
#
# Uporaba:  bash ops/restore.sh <dump.sql.gz> [ciljna_baza] [compose-file]
#   <dump.sql.gz>  putanja do backupa (obavezno)
#   [ciljna_baza]  default: DB_NAME iz .env
#   [compose-file] default: docker-compose.prod.yml
#
# VAZNO: restore radi DROP+CREATE objekata u ciljnoj bazi (pg_dump --clean).
# Backend nad tom bazom neka je ugasen ili baza prazna, da nema konflikta.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; [ -f .env ] && . ./.env; set +a

DUMP="${1:?daj putanju do .sql.gz backupa}"
TARGET_DB="${2:-${DB_NAME:?DB_NAME nije postavljen}}"
COMPOSE="${3:-docker-compose.prod.yml}"

[ -f "$DUMP" ] || { echo "Nema datoteke: $DUMP" >&2; exit 1; }

echo "==> restore '$DUMP' -> baza '$TARGET_DB' (compose: $COMPOSE)"
gunzip -c "$DUMP" | docker compose -f "$COMPOSE" exec -T db \
  psql -v ON_ERROR_STOP=0 -U "$DB_USER" -d "$TARGET_DB"

echo "==> gotovo. Ako je ovo staging app, restartaj backend da vidi svjeze stanje:"
echo "    docker compose -f $COMPOSE --env-file .env up -d --force-recreate backend"
