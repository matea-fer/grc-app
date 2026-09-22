# Lokalni staging (test nadogradnje prije prod-a)

Cilj: prije nego staviš `v*` tag (koji deploya na prod), pokreni **kandidat verziju**
(`:edge`, koju gradi push u `main`) **na svom računalu**, nad **kopijom prod podataka**
(iz backupa). Tako vidiš da nove migracije i promjene rade nad stvarnim podacima — a prod
se ne dira.

## Preduvjeti (jednom)
- **Docker Desktop** na računalu.
- Prijava na GHCR lokalno (za povlačenje privatnih slika):
  ```bash
  docker login ghcr.io -u matea-fer   # password = PAT s read:packages
  ```
- Postoji `:edge` slika — nastaje na svaki push u `main` (workflow `test-and-build`).

## 1. Dohvati zadnji prod backup na računalo
Najlakše `scp` s Dropleta (u Git Bashu):
```bash
mkdir -p backups
scp root@<IP-Dropleta>:/opt/grc-app/backups/'grc-*.sql.gz' ./backups/
```
(ili preuzmi datoteku iz DO Spaces web sučelja u `./backups/`)

## 2. Pripremi staging env
```bash
cp .env.staging.example .env.staging
# po zelji uredi .env.staging (TAG=edge ili konkretan sha-xxxxxxx)
```

## 3. Digni SAMO bazu, pa u nju vrati prod dump
Bazu prvo (da Flyway ne stvori praznu shemu prije restora):
```bash
docker compose -f docker-compose.staging.yml --env-file .env.staging up -d db
```
Restore najnovijeg dumpa u `grc_stage`:
```bash
LATEST=$(ls -t backups/grc-*.sql.gz | head -1); echo "$LATEST"
gunzip -c "$LATEST" | docker compose -f docker-compose.staging.yml --env-file .env.staging exec -T db psql -U postgres -d grc_stage
```

## 4. Digni aplikaciju (kandidat slike) i pusti Flyway da migrira
```bash
docker compose -f docker-compose.staging.yml --env-file .env.staging up -d
docker compose -f docker-compose.staging.yml logs -f backend
```
U logu gledaš:
- `Migrating schema … to version "N"` — **nove** migracije koje se primjenjuju NA prod podatke (upravo ovo testiramo!),
- `Started DemoApplication`.

Otvori **http://localhost:8081** → provjeri da su prod podaci tu i da nova promjena radi.

## 5. Ako je sve OK → izdanje na prod
```bash
git tag v0.1.0
git push origin v0.1.0
```
→ CI: `test` → `images` (:latest, :v0.1.0) → `deploy` na prod. (Vidi `deploy.yml`.)

## 6. Počisti staging kad završiš
```bash
docker compose -f docker-compose.staging.yml --env-file .env.staging down -v
```
(`-v` briše staging volumen — smije, jer je throwaway.)

---

## Ovo je i pravi „disaster drill"
Korak 3 (restore prod dumpa u praznu bazu) je točno ono što bi napravila kad izgubiš prod:
digneš novi stack i vratiš backup. Ako radi ovdje, radi i u nuždi. Zato staging služi
dvjema stvarima odjednom: **test nadogradnje** i **vježba oporavka**.

> Napomena o migracijama koje diraju konfiguraciju (`definitions` jsonb): ovdje ih testiraš
> nad STVARNIM oblikom korisničkih podataka iz prod dumpa — jedini pošten način da se uvjeriš
> da ne razbijaju ono što su korisnici izgradili, prije nego dođu na prod.
