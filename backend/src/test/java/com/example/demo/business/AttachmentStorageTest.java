package com.example.demo.business;

import com.example.demo.model.AttachmentContent;
import com.example.demo.repository.AttachmentContentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUSINESS test uz PRAVU bazu - da obrisana datoteka doista nestane iz baze.
 *
 * Ovo se ne da provjeriti mockom, jer problem uopce nije u nasem kodu nego u tome kako
 * Postgres cuva velike vrijednosti. {@code @Lob byte[]} on ne sprema u redak nego kao "large
 * object": u {@code attachment_content.data} stoji samo {@code oid}, dakle referenca, a bajtovi
 * su u sistemskoj tablici {@code pg_largeobject}. Brisanje retka uklanja referencu i nista vise
 * - datoteka ostaje, zauzima prostor, i do nje vise ne vodi nijedan put, pa je ne moze obrisati
 * ni onaj tko bi htio.
 *
 * Najgore od svega: sve izgleda ispravno. Prilog s ekrana uredno nestane, upit nad
 * {@code attachment} ga ne vraca, nijedan test s mockom ne primijeti nista. Zato ovi testovi
 * gledaju bas ono jedino mjesto koje zna istinu - {@code pg_largeobject_metadata}.
 *
 * {@code @Transactional} znaci da se sve upisano na kraju ponisti; stvaranje large objecta je
 * takoder transakcijsko, pa razvojna baza ostaje kakva je bila.
 */
@Tag("business")
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
@Transactional
class AttachmentStorageTest {

    /** Prilog koji u razvojnoj bazi ne postoji - test nikome ne dira podatke. */
    private static final long ATTACHMENT_ID = 999_101L;

    @Autowired
    private AttachmentContentRepository contentRepository;

    @PersistenceContext
    private EntityManager em;

    private long oidOf(long attachmentId) {
        return ((Number) em.createNativeQuery(
                        "select data from attachment_content where attachment_id = :id")
                .setParameter("id", attachmentId)
                .getSingleResult()).longValue();
    }

    private boolean rowExists(long attachmentId) {
        Number count = (Number) em.createNativeQuery(
                        "select count(*) from attachment_content where attachment_id = :id")
                .setParameter("id", attachmentId)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private boolean fileExists(long oid) {
        Number count = (Number) em.createNativeQuery(
                        "select count(*) from pg_largeobject_metadata where oid = :oid")
                .setParameter("oid", oid)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private long orphanCount() {
        return ((Number) em.createNativeQuery(
                "select count(*) from pg_largeobject_metadata m "
                        + "where not exists (select 1 from attachment_content c where c.data = m.oid)")
                .getSingleResult()).longValue();
    }

    @Test
    @DisplayName("brisanje sadrzaja oslobada i samu datoteku, ne samo redak")
    void removeContentFreesTheFile() {
        contentRepository.save(new AttachmentContent(ATTACHMENT_ID, "dokaz".getBytes(StandardCharsets.UTF_8)));
        em.flush();
        long oid = oidOf(ATTACHMENT_ID);
        assertThat(fileExists(oid)).as("datoteka je nastala").isTrue();

        contentRepository.removeContent(List.of(ATTACHMENT_ID));
        em.flush();

        // Bez lo_unlink bi redak nestao, a OVA provjera i dalje nasla datoteku - tocno to je
        // godinama tiho ostavljalo smece u bazi.
        assertThat(fileExists(oid)).as("datoteka je stvarno obrisana").isFalse();
    }

    /**
     * Regresija: brisanje priloga vracalo je 500 iz stvarne aplikacije, a gornji test je pritom
     * bio zelen.
     *
     * Razlika je bila SAMO u tome je li entitet vec u perzistencijskom kontekstu. Gornji test ga
     * malo prije sam spremi, pa ga izvedeno brisanje nade u kontekstu i ne cita ga iz baze.
     * Korisnik koji pritisne "x" na prilogu nema nista u kontekstu: Spring Data tada entitet
     * UCITA da bi ga obrisao, Hibernate popuni {@code @Lob byte[] data} - a datoteke vise nema,
     * jer ju je {@code lo_unlink} unistio redak prije. Driver padne u {@code getBinaryStream}.
     *
     * Zato ovdje stoji {@code em.clear()}: on je cijeli test. Bez njega prolazi i pokvaren kod.
     */
    @Test
    @DisplayName("sadrzaj se brise i kad entitet nije ucitan")
    void removeContentWorksWithoutLoadedEntity() {
        contentRepository.save(new AttachmentContent(ATTACHMENT_ID, "dokaz".getBytes(StandardCharsets.UTF_8)));
        em.flush();
        long oid = oidOf(ATTACHMENT_ID);

        // ovako to izgleda iz zahtjeva koji je upravo stigao - kontekst je prazan
        em.clear();

        contentRepository.removeContent(List.of(ATTACHMENT_ID));
        em.flush();

        assertThat(fileExists(oid)).as("datoteka je obrisana").isFalse();
        assertThat(rowExists(ATTACHMENT_ID)).as("redak je obrisan").isFalse();
    }

    /**
     * Prava regresija za kvar koji se dogodio: skupno brisanje (zapis, stupac, obrazac) je
     * lo_unlink dobilo, a brisanje POJEDINACNOG priloga - najcesci put od svih - nastavilo je
     * zvati samo brisanje retka. Curilo je dalje, bez ijednog znaka.
     *
     * Zato se ovdje ne provjerava ponasanje nego sam KOD: dva koraka koja se ne smiju
     * razdvojiti spojena su u {@code removeContent}, pa nitko drugi ne smije zvati drugi korak
     * sam. Test bi pao i danas da se popravak zaboravi na trecem mjestu koje jednom dode.
     */
    @Test
    @DisplayName("nitko ne brise sadrzaj mimo removeContent")
    void noOneDeletesContentWithoutUnlinking() throws IOException {
        Path sources = Path.of("src/main/java");
        List<String> callers;
        try (Stream<Path> files = Files.walk(sources)) {
            callers = files
                    .filter(path -> path.toString().endsWith(".java"))
                    // sam repozitorij smije - ondje je metoda i deklarirana
                    .filter(path -> !path.getFileName().toString().equals("AttachmentContentRepository.java"))
                    .filter(path -> read(path).contains("deleteRowsWithoutUnlinking"))
                    .map(path -> path.getFileName().toString())
                    .toList();
        }

        assertThat(callers)
                .as("brisanje retka bez lo_unlink ostavlja datoteku u bazi zauvijek - zovite removeContent")
                .isEmpty();
    }

    /**
     * Stanje same razvojne baze, nakon migracije V8 koja je pomela zatecene sirotice.
     *
     * Ovaj test ne provjerava kod nego posljedicu, i to je namjerno: sirotica se ne vidi ni u
     * jednom ekranu ni u jednom upitu aplikacije, pa jedino ovakva provjera moze reci da ih
     * doista nema. Ako jednom padne, negdje je nastao nov put koji brise redak bez datoteke.
     */
    @Test
    @DisplayName("u bazi nema datoteka bez priloga")
    void noOrphansLeftInDatabase() {
        assertThat(orphanCount())
                .as("datoteke bez ijednog priloga koji na njih pokazuje (vidi migraciju V8)")
                .isZero();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Ne mogu procitati " + path, e);
        }
    }
}
