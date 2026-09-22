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
| 2 | **Ciljaj po `template_code`** | `WHERE template_code = '<KOD>'`, **nikad po `id`** (svaki tenant ima svoj) ni po `name` (korisnik ga mijenja). Kod hvata sve tenante i preživljava preimenovanje. |
| 3 | **Idempotentno** | `AND NOT EXISTS (… col ->> 'key' = '<nova>')`. Pogodi li 0 redaka → **nije greška**. |
| 4 | **Ne prepisuj** | `definitions = definitions \|\| <element>::jsonb` (dodaj na kraj), nikad `= <cijeli niz>`. |

`definitions || '{...}'::jsonb` nad **nizom** i **objektom** doda objekt kao jedan novi element.
(Ako je desna strana i sama niz, spoji nizove — zato element mora biti objekt.)

> **Zašto kod, a ne `id` ni `name`:** `id` je per-tenant (svaki tenant ima svoj redak istog
> starter obrasca — isti kod, drugi `id`, drugi `company_id`), pa bi ciljanje po `id` zakrpalo
> samo jednog tenanta. `name` nije ni unique (u shemi je jedino `form_template_pkey` na `id`)
> ni immutable — `PUT /api/templates/{id}` (`TemplateService.rename`) ga mijenja, pa preimenovan
> obrazac migracija po imenu **ne bi** pogodila.
>
> **`template_code`** (uveden u [V13](../../backend/src/main/resources/db/migration/V13__template_code.sql))
> rješava oboje: nepromjenjiv je iz korisnicke perspektive (rename mijenja `name`, ne `code`),
> isti je kod svih tenanata za isti starter, i postavlja ga provisioning (Task 4), ne korisnik.
> Kodovi: `RIZICI`, `SAMOPROCJENA`, `DOBAVLJACI`, `UPRAVLJANJE_IMOVINOM`, `PRODUKTI`, `ZADACI`,
> `PRODUCT_ASSESSMENT`.
>
> **Rub:** obrazac koji je korisnik sam napravio (ne iz startera) ima `template_code = NULL` i
> migracije ga po kodu namjerno ne diraju. Isto vrijedi za starter koji je netko preimenovao
> prije nego je backfill dodao kod — takav ostane bez koda dok mu ga administrator ne dodijeli.

Ako mijenjaš postojeći element (a ne dodaješ novi), radi to kroz `jsonb_set` po **pronađenom
indeksu** ključa, ne po fiksnom `-> 0`; i dalje aditivno po smislu (mijenjaš svojstvo, ne rušiš kolonu).

Uz izmjenu bumpaj `version = version + 1` (@Version stupac). Aplikacija u trenutku migracije
još ne poslužuje zahtjeve (Flyway ide pri startu), pa nema utrke s optimističkim zaključavanjem.

## Kanonski oblik (ciljanje po `template_code`)

```sql
UPDATE public.form_template t
SET definitions = t.definitions || $json$ { "key": "napomena", "type": "string", ... } $json$::jsonb,
    version = t.version + 1
WHERE t.template_code = 'RIZICI'          -- stabilno sidro: hvata sve tenante, prezivljava rename
  AND t.definitions IS NOT NULL
  AND NOT EXISTS (                          -- idempotencija
      SELECT 1 FROM jsonb_array_elements(t.definitions) AS col
      WHERE col ->> 'key' = 'napomena'
  );
```

Cijeli element (svih 10 polja `ColumnEntry` + `options`) je gore u odjeljku „Oblik jednog elementa".

> Povijesna bilješka: prvi primjer (`V12`, ciljao po `name`) bio je demo iz Task 3 i uklonjen je
> s `main` (čuva se u grani `demo-v12`). Od V13 nadalje ciljaj po `template_code`.

## Kako se testira PRIJE produkcije (obavezno)

Migracija koja dira `definitions` testira se nad **kopijom pravih prod podataka**, jer jedini
pošten test je: *ruši li ono što su korisnici izgradili?* To radi lokalni staging
(vidi [STAGING.md](../../STAGING.md)):

1. `main` push → gradi `:edge` slike (bez diranja prod-a).
2. Staging: `up -d db` → restore zadnjeg prod dumpa → `up -d` (Flyway odvrti **novu** migraciju nad prod podacima).
3. U backend logu tražiš `Migrating schema … to version "N"` (verzija tvoje nove migracije) i `Started DemoApplication`.
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
