-- PRIMJER migracije koja dira konfiguraciju obrazaca (definitions jsonb).
--
-- Zasto ovako, a ne "prepisi definitions": na produkciji SU KORISNICI vlasnici obrazaca -
-- oni dodaju kolone, mijenjaju sifrarnike, grade svoje forme. Dev NE SMIJE prepisati taj
-- dokument, jer bi pobrisao njihov rad. Zato migracija koja dira definitions mora biti:
--
--   1. ADITIVNA        - samo dodaje element u niz, ne dira postojece kolone.
--   2. CILJANA PO KLJUCU - hvata obrasce po STABILNOM svojstvu (ime), ne po id-u ni poziciji;
--                          isti "starter" obrazac postoji kod svakog tenanta pod drugim id-em,
--                          pa se svi njegovi primjerci moraju uhvatiti odjednom.
--   3. IDEMPOTENTNA    - ako kolona vec postoji (korisnik ju je rucno dodao, ili je migracija
--                          nekako vec presla), NE dira se. Pogodi li 0 redaka - to NIJE greska.
--   4. NE PREPISUJE    - koristi `definitions || element` (dodaj na kraj niza), nikad `= <cijeli niz>`.
--
-- Ovaj primjer: obrascima imena 'Rizici' dodaje neobaveznu tekstualnu kolonu "napomena",
-- ali samo onima koji je jos nemaju. Zamijeni ime obrasca i definiciju kolone svojom potrebom.
--
-- PRIJE PROD-a: odvrti na stagingu (nad KOPIJOM prod podataka iz backupa) i provjeri broj
-- pogodenih redaka - vidi docs/how-to/migracije-definitions.md.

UPDATE public.form_template t
SET
    -- `||` nad jsonb nizom i objektom dodaje objekt kao JEDAN novi element na kraj niza.
    definitions = t.definitions || $json$
        {
          "key": "napomena",
          "type": "string",
          "codebookId": null,
          "label": "Napomena",
          "required": false,
          "unique": false,
          "defaultValue": null,
          "readOnly": false,
          "width": null,
          "options": {
            "autoIncrement": false,
            "numberMode": null,
            "min": null,
            "max": null,
            "numberFormat": null,
            "pattern": null,
            "dateMode": null,
            "pickerMode": null,
            "multiple": false,
            "allowNewValues": false,
            "formula": null,
            "buttonLabel": null,
            "buttonAction": null,
            "targetTemplateId": null,
            "displayColumnKey": null,
            "onTargetDelete": null
          }
        }
    $json$::jsonb,
    -- @Version stupac: bumpamo ga da je promjena vidljiva kao izmjena retka. Aplikacija u
    -- trenutku migracije jos ne posluzuje (Flyway ide pri startu, prije prvog zahtjeva),
    -- pa nema utrke s optimistickim zakljucavanjem.
    version = t.version + 1
WHERE t.name = 'Rizici'                    -- <-- CILJANJE PO IMENU (stabilno), hvata sve tenante
  AND t.definitions IS NOT NULL
  AND NOT EXISTS (                          -- <-- IDEMPOTENCIJA: preskoci ako kolona vec postoji
      SELECT 1
      FROM jsonb_array_elements(t.definitions) AS col
      WHERE col ->> 'key' = 'napomena'
  );
