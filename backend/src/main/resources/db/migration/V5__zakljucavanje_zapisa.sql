-- Zakljucavanje zapisa (12.08.2026.).
--
-- Gumb u retku dobiva drugu radnju: "zakljucaj". Zakljucan zapis se vise ne smije mijenjati
-- ni brisati, niti mu se smiju dirati prilozi; otkljucati ga smije samo administrator
-- (globalni ili firmin). To je trenutak u kojem zapis prestaje biti radna verzija - "gotov
-- sam, saljem na validaciju".
--
-- ZASTO NE U jsonb `data`: zakljucanost nije podatak koji obrazac prikuplja nego stanje
-- zapisa, i ono odlucuje smije li se `data` uopce mijenjati. Da stoji unutra, mijenjao bi ga
-- isti PUT koji zakljucavanje treba sprijeciti, a nestao bi i kad bi se gumb maknuo iz sheme -
-- zapisi bi se tiho otkljucali brisanjem STUPCA, sto s njihovim sadrzajem nema veze.
--
-- locked_at umjesto boolean locked: jedan stupac odgovara i na "je li" i na "kada", pa svi
-- zatecni retci ostaju otkljucani bez ijednog UPDATE-a. Isti obrazac kao company.deleted_at
-- iz V3. locked_by je korisnicko ime, kao i u dnevniku - tko je zakljucao vidi se u retku,
-- bez odlaska u dnevnik.
--
-- Indeksa nema: zapisi se citaju po obrascu i onda filtriraju u memoriji; po zakljucanosti
-- se ne pretrazuje.

ALTER TABLE public.survey_result ADD COLUMN locked_at timestamp(6) with time zone;
ALTER TABLE public.survey_result ADD COLUMN locked_by character varying(255);
