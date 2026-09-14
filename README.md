# starter-project — kostur za brzo podizanje nove aplikacije

Ovo je **dokazano zelen snapshot** kostura iz `Angular-tables` (Spring Boot 4 /
Java 21 backend + Angular 21 frontend, dinamične tablice po shemi, prijava,
uloge, izolacija po grupi, dnevnik, Flyway, prilozi, formule, veze, šifrarnici).

Svrha: **novu aplikaciju ne generiraš iz nule.** Kloniraš ovaj kostur i AI radi
samo razliku (domena, preimenovanja), umjesto da iznova piše isti boilerplate.
To je najveća ušteda vremena i tokena.

> Naziv „survey/anketa" ovdje znači **generički zapis** (redak u dinamičnoj
> tablici), ne domenu. Starter je domenski neutralan; „survey" je samo naslijeđen
> naziv resursa. Preimenovanje je neobavezno (vidi „Rebranding" niže).

## Brzi put (fast path)

Po podjeli rada: **ljusku pokrećeš ti, AI piše datoteke.** Redoslijed:

1. **Kloniraj kostur** u novi projekt (radi se jednom, izvan ovog direktorija):
   ```bash
   git archive HEAD | tar -x -C /c/Users/mtorbarina/GitProjects/<nova-app>
   ```
   (ili obična kopija bez `.git`, `node_modules`, `build`, `.gradle`, `.env`)

2. **Pre-flight** — verzije alata + zauzetost porta baze, u jednom prolazu:
   ```bash
   bash scripts/preflight.sh        # ili: powershell -File scripts\preflight.ps1
   ```
   Zalijepi AI-ju cijeli ispis. On potvrdi kompatibilnost prije koda.

3. **Svježa baza** za ovu aplikaciju (svaka dobiva svoju):
   ```bash
   cp .env.example .env             # postavi DB_NAME, DB_PORT, DB_PASSWORD
   docker compose up -d db
   ```
   Compose diže Postgres na `DB_PORT` (default 5432). Ako je port zauzet,
   promijeni `DB_PORT` u `.env` — ne moraš gasiti tuđe baze.

4. **Backend .env pa pokreni backend:**
   ```bash
   cp backend/.env.example backend/.env   # DB_PASSWORD, JWT_SECRET (≥32 znaka), DB_PORT/DB_NAME
   cd backend && ./gradlew bootRun
   ```
   Flyway napravi shemu pri prvom pokretanju.

5. **Frontend** (drugi prozor):
   ```bash
   cd frontend && npm install && ng serve
   ```

6. **Prihvat** — provjeri da kostur zadovoljava ugovor (motor već postoji):
   ```bash
   cd pipeline && python prihvat.py projekti/starter.json
   ```
   Zalijepi AI-ju OK/PAD izvještaj. Jedan artefakt umjesto ručnih `curl` provjera.

Tek nakon toga AI kreće s **domenom** (skill `domain-seed`) ili proširenjima.

## Kickoff parametri

Umjesto da te AI ispituje svaki put, popuni `projekt.yml`:
```bash
cp projekt.example.yml projekt.yml   # ime, base paket, grupa, portovi
```
AI ga pročita i krene bez pitanja natrag.

## Rebranding base paketa (neobavezno)

Kostur koristi `com.example.demo`. Ako želiš vlastiti paket, to je zaseban korak
koji radi AI (preimenovanje paketa + `group` u `build.gradle`); reci mu i on
napravi izmjenu kroz sve datoteke. Za brzo podizanje nije nužno.

## Reset baze

Svježa baza iz čista lista:
```bash
docker compose down -v && docker compose up -d db
```

## Što je unutra

| Dio | Sadrži |
|---|---|
| `backend/` | Spring Boot 4, Java 21, Gradle 9, Flyway, JWT, izolacija, dnevnik |
| `frontend/` | Angular 21, standalone komponente, signali, tema-tokeni |
| `pipeline/` | `prihvat.py` + `ugovor.md` + `projekti/starter.json` — prijenosni prihvat |
| `scripts/` | `preflight.sh` / `.ps1` — verzije + port u jednom prolazu |
| `docker-compose.yml` | throwaway Postgres, port po `DB_PORT` |
| `projekt.example.yml` | kickoff parametri |
# starter-project
# grc-app
