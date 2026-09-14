-- Ciscenje datoteka koje su ostale u bazi bez ijednog priloga koji na njih pokazuje.
--
-- Odakle sirotice. Sadrzaj priloga je "large object": u attachment_content stoji samo oid,
-- dakle referenca, a bajtovi su u sistemskoj tablici pg_largeobject. Brisanje retka uklanja
-- referencu i nista vise, pa datoteka ostaje - zauzima prostor, a do nje vise ne vodi nijedan
-- put, tako da je ne moze obrisati ni onaj tko bi htio. Jedino lo_unlink je stvarno uklanja.
--
-- Curilo je na dva mjesta i u dva navrata: skupno brisanje (zapis, stupac, obrazac, firma)
-- popravljeno je uvodenjem lo_unlink u AttachmentCleanup, a brisanje POJEDINACNOG priloga -
-- najcesci put od svih, onaj kojim korisnik makne prilog s ekrana - curilo je i dalje, sve
-- dok oba nisu spojena u AttachmentContentRepository.removeContent. Ova migracija cisti ono
-- sto je za sobom ostavilo oboje; kod je popravljen, pa novih sirotica vise nema odakle.
--
-- Zasto se smije pomesti SVE bez vlasnika: u ovoj bazi je attachment_content jedina tablica
-- koja drzi large objecte, pa je "nitko na njega ne pokazuje" ovdje isto sto i "smece". U bazi
-- koju dijeli jos koja aplikacija ovo se ne bi smjelo pokrenuti ovako - njezine bi datoteke
-- ovom upitu izgledale isto tako osirotjelo. Isti posao inace radi alat vacuumlo.
--
-- Prazna baza nema sto pocistiti, pa migracija na njoj uredno prode bez ijednog retka.
SELECT lo_unlink(m.oid)
FROM pg_largeobject_metadata m
WHERE NOT EXISTS (
    SELECT 1 FROM attachment_content c WHERE c.data = m.oid
);
