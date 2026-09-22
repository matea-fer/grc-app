<!--
audience: [developer, architect]
status: draft
last-verified: 2026-09-22
evidence:
  - code: backend/src/main/java/com/example/demo/model/ColumnEntry.java
  - code: backend/src/main/java/com/example/demo/model/ColumnOptions.java
  - code: backend/src/main/resources/db/migration/V1__zatecena_shema.sql (form_template.definitions jsonb)
  - code: backend/src/main/resources/db/migration/V12__primjer_dodaj_kolonu_napomena.sql
-->

# Kako migrirati konfiguraciju obrazaca (`definitions` jsonb)

## Kontekst: zašto je ovo posebno

Na produkciji **korisnici su vlasnici obrazaca** — oni dodaju kolone, spajaju šifrarnike,
grade svoje forme. `form_template.definitions` je `jsonb` niz `ColumnEntry` objekata i
sadrži **njihov rad**, ne naš.

Zato dev **nikad** ne smije prepisati taj dokument iz seeda ili koda. Kad promjena
konfiguracije mora doći od developera (npr. svim tenantima dodati novu standardnu kolonu),
ona ide kao **Flyway migracija koja krpa jsonb**, a ne kao `UPDATE ... SET definitions = <cijeli niz>`.

## Oblik jednog elementa

Jedan stupac u nizu (vidi `ColumnEntry` / `ColumnOptions`):

```json
{
  "key": "napomena", "type": "string", "codebookId": null,
  "label": "Napomena", "required": false, "unique": false,
  "defaultValue": null, "readOnly": false, "width": null,
  "options": { "autoIncrement": false, "numberMode": null, "min": null, "max": null,
    "numberFormat": null, "pattern": null, "dateMode": null, "pickerMode": null,
    "multiple": false, "allowNewValues": false, "formula": null, "buttonLabel": null,
    "buttonAction": null, "targetTemplateId": null, "displayColumnKey": null,
    "onTargetDelete": null } }
```

`type` ∈ `string | number | date | codebook | formula | button | file | reference | link`.
`options` je uvijek prisutan objekt (nikad `null` — kompaktni konstruktor ga normalizira).

## Četiri pravila (nepregovorna)

| # | Pravilo | Kako |
|---|---------|------|
| 1 | **Aditivno** | Samo dodaj element, ne diraj postojeće kolone. |
| 2 | **Ciljaj po stabilnom imenu** | `WHERE name = '<obrazac>'`, **nikad po `id`** — isti starter obrazac ima kod svakog tenanta drugi `id`; po imenu ih uhvatiš sve. |
| 3 | **Idempotentno** | `AND NOT EXISTS (… col ->> 'key' = '<nova>')`. Pogodi li 0 redaka → **nije greška**. |
| 4 | **Ne prepisuj** | `definitions = definitions \|\| <element>::jsonb` (dodaj na kraj), nikad `= <cijeli niz>`. |

`definitions || '{...}'::jsonb` nad **nizom** i **objektom** doda objekt kao jedan novi element.
(Ako je desna strana i sama niz, spoji nizove — zato element mora biti objekt.)

> **`name` NIJE unique** (u shemi je jedino `form_template_pkey` na `id`). To nije propust nego
> preduvjet: svaki tenant ima **svoj** redak istog starter obrasca (isti `name`, drugi `id`,
> drugi `company_id`), pa jedan `UPDATE ... WHERE name = '<obrazac>'` namjerno pogodi **sve** kopije.
> Ciljanje po `id` zakrpalo bi samo jednog tenanta.
>
> **Ime nije ni immutable:** `PUT /api/templates/{id}` (`TemplateService.rename`) dopušta
> preimenovanje. Zato je `name` **best-effort sidro**, ne jamstvo — preimenovan obrazac
> migracija po imenu **neće** pogoditi. Za neobaveznu, aditivnu kolonu to je podnošljivo
> (idempotentno je, korisnik je doda sam); za obavezan zahvat nije dovoljno.
>
> **Robusno sidro** je nepromjenjiv, developerski kontroliran `template_code` stupac koji se
> postavi pri provisioningu starter obrasca i korisnik ga ne dira (rename mijenja `name`, ne
> `code`). Migracije tada ciljaju `WHERE template_code = 'RIZICI'` i preživljavaju preimenovanje.
> Taj isti kod je i temelj starter-paketa po tenantu (Task 4). Dok koda nema, kao zakrpu možeš
> dodati strukturni uvjet: `AND EXISTS (select 1 from jsonb_array_elements(definitions) c where c->>'key'='<poznata_kolona>')`.

Ako mijenjaš postojeći element (a ne dodaješ novi), radi to kroz `jsonb_set` po **pronađenom
indeksu** ključa, ne po fiksnom `-> 0`; i dalje aditivno po smislu (mijenjaš svojstvo, ne rušiš kolonu).

Uz izmjenu bumpaj `version = version + 1` (@Version stupac). Aplikacija u trenutku migracije
još ne poslužuje zahtjeve (Flyway ide pri startu), pa nema utrke s optimističkim zaključavanjem.

## Referentni primjer

`V12__primjer_dodaj_kolonu_napomena.sql` — obrascima `'Rizici'` dodaje neobaveznu kolonu
`napomena`, samo onima koji je nemaju. Prekopiraj ga i prilagodi ime obrasca i element.

## Kako se testira PRIJE produkcije (obavezno)

Migracija koja dira `definitions` testira se nad **kopijom pravih prod podataka**, jer jedini
pošten test je: *ruši li ono što su korisnici izgradili?* To radi lokalni staging
(vidi [STAGING.md](../../STAGING.md)):

1. `main` push → gradi `:edge` slike (bez diranja prod-a).
2. Staging: `up -d db` → restore zadnjeg prod dumpa → `up -d` (Flyway odvrti **novu** migraciju nad prod podacima).
3. U backend logu tražiš `Migrating schema … to version "12"` (ili višu) i `Started DemoApplication`.
4. Provjeri **broj pogođenih redaka** i da su postojeće kolone netaknute:

```bash
# koliko obrazaca 'Rizici' sada ima kolonu 'napomena'
docker compose -f docker-compose.staging.yml --env-file .env.staging exec -T db \
  psql -U postgres -d grc_stage -c \
  "select id, name, (select count(*) from jsonb_array_elements(definitions) c where c->>'key'='napomena') as ima_napomenu, jsonb_array_length(definitions) as ukupno_kolona from form_template where name='Rizici' order by id;"
```

Očekuješ `ima_napomenu = 1` na svakom `Rizici` retku, a `ukupno_kolona` = staro + 1
(dokaz da ništa nije obrisano). Otvori i **http://localhost:8081** pa vidi da se ta nova kolona
pojavila u obrascu, a stari podaci su i dalje tu.

5. Tek kad je staging čist → `git tag vX.Y.Z` → CI deploya na prod. **Isti** migracijski SQL
   koji si potvrdila na stagingu odvrti se na produkciji.

## Idempotentnost + rollback

- Flyway svaku verziju vrti **točno jednom** i pamti checksum — datoteku migracije poslije
  **ne mijenjaš** (izmjena checksuma zaustavlja start). Ispravak ide kao nova verzija.
- Za "poništi": napiši **novu** migraciju koja makne element
  (`jsonb_set` / rekonstrukcija niza bez tog ključa), također aditivno i idempotentno.
  Nema `flyway undo` u ovom setupu.
- Backup je i dalje krajnja mreža: prije taga na prod postoji off-site dump (vidi `ops/backup.sh`).
