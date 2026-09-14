-- Indeksi za stranicenje i pretragu na posluzitelju (12.08.2026.).
--
-- Indeks je pomocna struktura koja bazi omogucuje da nade retke bez citanja cijele tablice.
-- Do sada ih ove dvije tablice nisu imale nijedan (osim primarnog kljuca), pa je svaki dohvat
-- citao SVE retke i tek onda odbacio tude - ukljucujuci retke drugih firmi.
--
-- 1. survey_result (template_id): svaki upit ekrana "Podaci" pocinje s "zapisi ovog obrasca".
--    Bez indeksa bi stranicenje bilo obmana: prijenos i crtanje bi bili brzi, a baza bi i dalje
--    prolazila cijelu tablicu da nade tih 50 redaka.
--
-- 2. log_table (company_id, created_at DESC): tocan oblik upita nad dnevnikom - filtriraj po
--    firmi, poredaj po vremenu silazno, uzmi stranicu. Redoslijed stupaca nije proizvoljan:
--    prvo ide ono po cemu se filtrira (jednakost), pa ono po cemu se sortira - obrnuto
--    bi indeks za ovaj upit bio gotovo beskoristan.
--
-- ZASTO NEMA INDEKSA PO POJEDINIM STUPCIMA OBRASCA (npr. data->>'grad'):
-- ti "stupci" nisu stupci baze nego kljucevi u jsonb dokumentu, a definira ih korisnik u
-- Editoru. Indeks po njima znacio bi da dodavanje, preimenovanje i brisanje stupca mora
-- stvarati i rusiti indekse - dakle da aplikacija u redovnom radu mijenja shemu baze. Cijena
-- je da pretraga i sortiranje citaju sve retke TOG obrasca; na nekoliko tisuca zapisa to je
-- ispod milisekunde. GIN indeks nad cijelim `data` se ne dodaje jer ubrzava "sadrzi ovu
-- vrijednost", a ne pretragu po dijelu teksta (LIKE '%zagreb%'), koja nam je glavni slucaj.

CREATE INDEX ix_survey_result_template ON public.survey_result USING btree (template_id);

CREATE INDEX ix_log_table_company_created ON public.log_table USING btree (company_id, created_at DESC);
