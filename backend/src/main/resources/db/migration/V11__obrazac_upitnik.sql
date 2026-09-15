-- Obrazac tipa "upitnik": retke (pitanja) definira administrator u Editoru, a vide se
-- na svakoj instanci obrasca. Korisnik na instanci ne dodaje ni ne brise retke - samo
-- bira odgovor (sifrarnicki stupac). Flag stoji na samom obrascu.
ALTER TABLE public.form_template ADD COLUMN questionnaire boolean NOT NULL DEFAULT false;
