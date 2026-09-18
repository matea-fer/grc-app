# Deploy na DigitalOcean Droplet

Stack: **Postgres + backend (Spring Boot + grype u slici) + frontend (nginx)**, sve preko
`docker compose`. Slike se grade u **GitHub Actions → GHCR**; Droplet ih samo povlači
(na njemu se NE gradi — premalo RAM-a). Nadogradnja = nova slika + `pull && up -d`; Flyway
sam primijeni migracije.

---

## 0. Jednom: objavi slike (CI)
1. Push na `main` (ili tag `v*`) pokrene workflow `.github/workflows/deploy.yml`.
2. Provjeri u GitHubu: **Actions** → build prošao; **Packages** → `grc-app-backend` i `grc-app-frontend`.
3. Da ih Droplet može povući bez lozinke, na GitHubu otvori svaki package →
   **Package settings → Change visibility → Public**.
   (Alternativa: ostavi privatno pa se na Dropletu prijavi:
   `echo <PAT_s_read:packages> | docker login ghcr.io -u matea-fer --password-stdin`.)

## 1. Priprema Dropleta (jednom)
SSH na Droplet (`ssh root@<IP>`), pa:
```bash
# Docker je već tu ako si uzela "Docker" marketplace image; provjeri:
docker --version && docker compose version

# SWAP (obavezno na malom RAM-u - inače JVM/grype znaju pasti s OOM):
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
free -h   # provjeri da swap postoji

# Firewall: pusti SSH i HTTP
ufw allow OpenSSH && ufw allow 80/tcp && ufw --force enable
```

## 2. Prvi deploy
Na Dropletu napravi radni direktorij i stavi 2 datoteke (compose + .env):
```bash
mkdir -p /opt/grc-app && cd /opt/grc-app
# prekopiraj docker-compose.prod.yml iz repoa (scp, git clone samo tih datoteka, ili nano)
cp .env.prod.example .env   # pa popuni PRAVE vrijednosti:
nano .env                   # DB_PASSWORD, JWT_SECRET (openssl rand -base64 48), GH_OWNER, TAG
```
Pokreni:
```bash
docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d
docker compose -f docker-compose.prod.yml logs -f backend   # čekaj Flyway + "Started"
```
Otvori **http://<IP>** → prijava `admin` / `admin` (početni račun na praznoj bazi).

## 3. (opcionalno) Napuni domenu i upitnik
Domena (firme, obrasci, šifrarnici, SBOM, Product assessment) nije u shemi nego se uvozi
kroz API. S bilo kojeg računala s Pythonom, protiv javnog URL-a:
```bash
cd pipeline
# u projekti/*.json i seed/*.json privremeno promijeni baseUrl na http://<IP>
python uvoz_seed.py seed/grc.json
python uvoz_seed.py seed/product_assessment.json
```

## 4. Provjera (smoke test)
Isti alat kao za kostur, samo drugi `baseUrl`:
```bash
cd pipeline && python prihvat.py projekti/starter.json   # baseUrl = http://<IP>
```

## 5. Nadogradnja (svaki sljedeći deploy)
1. Lokalno: napravi izmjene → **push na `main`** (ili tag) → CI izgradi nove slike.
2. **Backup baze prije svega** (Flyway migracije su forward-only):
   ```bash
   cd /opt/grc-app
   docker compose -f docker-compose.prod.yml exec db \
     pg_dump -U "$DB_USER" "$DB_NAME" > backup-$(date +%F-%H%M).sql
   ```
3. Povuci i podigni nove slike (nove migracije se same primijene):
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env pull
   docker compose -f docker-compose.prod.yml --env-file .env up -d
   ```
4. Smoke test (korak 4). Gotovo.

> Za točnu verziju umjesto `latest`: u `.env` postavi `TAG=<git-sha>` (iz imena slike u GHCR-u)
> i `up -d`. Tako znaš točno što je deployano i imaš na što se vratiti.

## 6. Rollback
```bash
# 1) vrati prethodni TAG
nano .env    # TAG=<prethodni-sha>
docker compose -f docker-compose.prod.yml --env-file .env up -d
# 2) ako je migracija promijenila shemu nekompatibilno, vrati i bazu:
#    docker compose exec -T db psql -U "$DB_USER" -d "$DB_NAME" < backup-....sql
```

## Napomene
- **Memorija:** JVM + Postgres + grype (pri skeniranju) su tijesni na najmanjem Dropletu;
  swap iz koraka 1 je nužan. Veliki SBOM-ovi mogu biti prespori/pasti — za pouzdano skeniranje
  bolji je Droplet s ≥ 2 GB RAM-a.
- **Grype baza** je ugrađena u backend sliku; osvježavanje = ponovni build slike (CI).
- **HTTPS/domena:** za početak je HTTP na IP-u. Kad poželiš domenu + TLS, dodajemo Caddy ili
  nginx + Let's Encrypt ispred (poseban korak).
- **Tajne:** `.env` na serveru NIKAD ne commitaj; drži ga samo na Dropletu.
