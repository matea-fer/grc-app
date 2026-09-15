-- SBOM evaluacija se moze vezati uz jedan zapis-produkt (softver) iz obrasca "Produkti".
--
-- product_id je id survey_result retka tog produkta. Bez fizickog stranog kljuca, kao i
-- sve veze u ovoj aplikaciji (referencijalni integritet drzi aplikacija) - pa brisanje
-- produkta ne pada na ogranicenju baze. product_name se cuva kao tekst u trenutku uploada,
-- da ekran ima naziv i kad se produkt kasnije preimenuje ili obrise.
--
-- Oba stupca su NULL-abilna: SBOM se smije evaluirati i samostalno, bez produkta.

ALTER TABLE public.sbom_evaluation ADD COLUMN product_id bigint;
ALTER TABLE public.sbom_evaluation ADD COLUMN product_name character varying(255);

-- popis evaluacija jednog produkta - upit koji radi ekran otvoren iz zapisa produkta
CREATE INDEX ix_sbom_evaluation_product ON public.sbom_evaluation USING btree (product_id);
