#!/usr/bin/env bash
# Pre-flight: verzije alata + zauzetost porta baze, u JEDNOM prolazu.
# Pokreni iz korijena startera i zalijepi AI-ju CIJELI ispis:
#   bash scripts/preflight.sh
# Ne prekida se na grešci — svaki redak koji fali je nalaz koji AI treba vidjeti.

PORT="${DB_PORT:-5432}"
[ -f .env ] && PORT="$(grep -E '^DB_PORT=' .env | tail -1 | cut -d= -f2 | tr -d '[:space:]')"
PORT="${PORT:-5432}"

echo "==================== VERZIJE ===================="
echo -n "Java:    "; java -version 2>&1 | head -1 || echo "NEMA"
echo -n "Gradle:  "; { ./backend/gradlew -v 2>/dev/null || gradle -v 2>/dev/null; } | grep -i '^Gradle' | head -1 || echo "NEMA (ni wrapper ni globalni)"
echo -n "Node:    "; node -v 2>/dev/null || echo "NEMA"
echo -n "npm:     "; npm -v 2>/dev/null || echo "NEMA"
echo -n "Angular: "; { npx --yes @angular/cli version 2>/dev/null || ng version 2>/dev/null; } | grep -i 'Angular CLI' | head -1 || echo "NEMA"
echo -n "psql:    "; psql --version 2>/dev/null || echo "NEMA u PATH-u (Postgres može ipak raditi u Dockeru)"
echo -n "docker:  "; docker --version 2>/dev/null || echo "NEMA"

echo ""
echo "============== PORT BAZE ($PORT) ==============="
# 1) Docker kontejner koji vec objavljuje ovaj port.
BUSY_DOCKER="$(docker ps --filter "publish=$PORT" --format '{{.Names}}  ({{.Image}})  {{.Ports}}' 2>/dev/null)"
# 2) BILO KOJI proces koji slusa na portu - hvata i NATIVNI Postgres (Windows servis,
#    Homebrew, apt), kojeg Docker filter iznad NE vidi. Prije diga baze ovdje ne smije
#    biti nikoga: sve sto slusa nije nas kontejner (njega jos nema).
LISTEN="$(
  { netstat -ano 2>/dev/null | grep -iE 'LISTEN' | grep -E "[:.]$PORT[[:space:]]"; } \
  || { ss -ltn 2>/dev/null | grep -E "[:.]$PORT[[:space:]]"; } \
  || { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null; }
)"
if [ -n "$BUSY_DOCKER" ]; then
  echo "ZAUZET (Docker kontejner):"
  echo "  $BUSY_DOCKER"
  echo "-> ugasi taj kontejner (docker stop <ime>) ILI promijeni DB_PORT u .env"
elif [ -n "$LISTEN" ]; then
  echo "ZAUZET (NATIVNI proces, NE Docker) — Docker filter ovo NE vidi:"
  echo "$LISTEN" | sed 's/^/  /'
  echo "-> vjerojatno nativni Postgres (Windows servis / Homebrew / apt) na $PORT."
  echo "   Backend bi gadao NJEGA, ne kontejner -> 28P01 password authentication failed."
  echo "   Daj kontejneru drugi host-port: DB_PORT=5433 u root .env I backend/.env"
  echo "   (+ u docker-compose.yml ako je hardkodiran). Ne gasi tudi Postgres."
else
  echo "slobodan (ni Docker ni nativni proces ne slusaju na $PORT)."
fi

echo ""
echo "Matrica koju kostur traži: Java 21 | Gradle 9.x | Node 20.19+/22.12+ | Angular 21 | Postgres 14+"
