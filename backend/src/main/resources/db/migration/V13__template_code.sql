-- Stabilan, developerski kontroliran identifikator obrasca: template_code.
--
-- Zasto: migracije koje diraju konfiguraciju (definitions jsonb) moraju ciljati obrasce po
-- necem STABILNOM. Ni id (svaki tenant ima svoj) ni name (korisnik ga mijenja kroz
-- PUT /api/templates/{id}) to nisu. template_code je:
--   - nepromjenjiv iz korisnicke perspektive (rename mijenja name, NE code),
--   - postavljen jednom pri provisioningu starter obrasca (Task 4),
--   - isti kod kod svih tenanata za isti starter (RIZICI, SAMOPROCJENA...),
-- pa migracije od sada ciljaju `WHERE template_code = 'RIZICI'` i prezive preimenovanje.
--
-- (Broj: preskacemo V12 - ondje je zivio demo iz Task 3, uklonjen s main. Rupe u
--  numeraciji su vec u konvenciji: nema ni V2.)

-- Nullable: obrasci koje je korisnik sam napravio (nisu iz starter paketa) nemaju kod.
ALTER TABLE public.form_template ADD COLUMN template_code varchar(64);

-- Jednokratni backfill POSTOJECIH starter obrazaca po imenu (best-effort, samo gdje kod jos
-- ne postoji). Ovo je jedini put da se oslanjamo na ime - jer stari podaci drugog sidra nemaju;
-- od sada kod postavlja provisioning, ne ime. Obrazac koji je netko vec preimenovao ostaje bez
-- koda i administrator mu ga moze dodijeliti naknadno.
UPDATE public.form_template
SET template_code = CASE name
        WHEN 'Rizici'               THEN 'RIZICI'
        WHEN 'Samoprocjena'         THEN 'SAMOPROCJENA'
        WHEN 'Dobavljači'           THEN 'DOBAVLJACI'
        WHEN 'Upravljanje imovinom' THEN 'UPRAVLJANJE_IMOVINOM'
        WHEN 'Produkti'             THEN 'PRODUKTI'
        WHEN 'Zadaci'               THEN 'ZADACI'
        WHEN 'Product assessment'   THEN 'PRODUCT_ASSESSMENT'
    END
WHERE template_code IS NULL
  AND name IN ('Rizici', 'Samoprocjena', 'Dobavljači', 'Upravljanje imovinom',
               'Produkti', 'Zadaci', 'Product assessment');

-- NAPOMENA: UNIQUE (company_id, template_code) NAMJERNO jos ne dodajemo. Jamstvo "jedan
-- starter tipa X po tenantu" ima smisla tek kad provisioning (Task 4) bude JEDINI put koji
-- postavlja kod; tada ide zasebnom migracijom, nakon sto se na stagingu potvrdi da postojeci
-- podaci to ogranicenje ne krse (Postgres UNIQUE ionako dopusta vise NULL-ova, pa custom
-- obrasci bez koda ostaju nedirnuti).
