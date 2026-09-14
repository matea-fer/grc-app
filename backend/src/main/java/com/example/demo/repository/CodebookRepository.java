package com.example.demo.repository;

import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Sifrarnici dostupni jednom pozivatelju.
 *
 * "Dostupno" znaci: svi GLOBAL sifrarnici + TENANT sifrarnici njegove firme. To se ne
 * da izraziti nazivom izvedene metode (uvjet je ILI preko dva stupca), pa su ova dva
 * upita jedini {@code @Query} u projektu - ostalo su i dalje izvedene metode.
 *
 * {@code companyId} smije biti {@code null}: globalni administrator koji jos nije
 * odabrao firmu tada vidi samo GLOBAL sifrarnike. To radi samo od sebe, jer
 * {@code c.companyId = null} u SQL-u nikad nije istina.
 *
 * {@code namePattern} je GOTOV LIKE uzorak, vec pretvoren u mala slova, i NIKAD nije
 * {@code null} - "sve" se salje kao {@code %}. Sastavlja ga {@code CodebookService}.
 * Razlog je konkretan: dok je upit imao oblik {@code :search is null or lower(concat(...))},
 * null parametar je stizao bez tipa, Postgres bi za concat pretpostavio bytea i cijeli
 * upit bi pukao s "function lower(bytea) does not exist". Uzorak bez null vrijednosti
 * taj problem uklanja iz korijena, umjesto da ga se zakrpa castom.
 */
public interface CodebookRepository extends JpaRepository<Codebook, Long> {

    @Query("""
            select c from Codebook c
            where (c.scope = com.example.demo.model.CodebookScope.GLOBAL or c.companyId = :companyId)
              and lower(c.name) like :namePattern escape '!'
            order by c.name asc
            """)
    List<Codebook> findVisible(@Param("companyId") Long companyId,
                               @Param("namePattern") String namePattern);

    /** Isto, ali suzeno na jedan doseg (filtar "Doseg" na ekranu). */
    @Query("""
            select c from Codebook c
            where (c.scope = com.example.demo.model.CodebookScope.GLOBAL or c.companyId = :companyId)
              and c.scope = :scope
              and lower(c.name) like :namePattern escape '!'
            order by c.name asc
            """)
    List<Codebook> findVisibleByScope(@Param("companyId") Long companyId,
                                      @Param("namePattern") String namePattern,
                                      @Param("scope") CodebookScope scope);

    /**
     * Postoji li vec sifrarnik tog naziva u istom dosegu i istoj firmi.
     *
     * Ovo NE moze biti jedinstveno ogranicenje u bazi: kod GLOBAL sifrarnika je
     * {@code company_id} null, a Postgres NULL vrijednosti u jedinstvenom indeksu
     * smatra razlicitima - pa bi dva globalna sifrarnika istog naziva prosla.
     * Zato provjera zivi u servisu, kao i kod duplikata naziva stupca.
     */
    boolean existsByScopeAndCompanyIdAndNameIgnoreCase(CodebookScope scope, Long companyId, String name);

    /** Isto, za GLOBAL doseg gdje je firma null (izvedene metode traze zaseban naziv za IS NULL). */
    boolean existsByScopeAndCompanyIdIsNullAndNameIgnoreCase(CodebookScope scope, String name);

    /**
     * Globalni sifrarnik tog naziva, ako postoji.
     *
     * Koristi ga migracija starih Da/Ne stupaca: ona sifrarnik "Da/Ne" stvara samo ako ga
     * jos nema, pa mora moci razlikovati "nema ga" od "postoji" - a ne samo saznati da je
     * naziv zauzet. {@code findFirst} jer naziv u GLOBAL dosegu cuva servis, ne baza.
     */
    Optional<Codebook> findFirstByScopeAndCompanyIdIsNullAndNameIgnoreCase(CodebookScope scope, String name);

    /**
     * Sifrarnik te firme pod tim nazivom, ako postoji.
     *
     * Koristi ga prijenos obrazaca u drugu firmu: sifrarnik na koji preneseni stupac pokazuje
     * treba u ciljnoj firmi PRONACI prije nego se odluci stvarati ga, jer je naziv ondje vec
     * mogao biti zauzet istim popisom. Zato ne exists nego find - treba id, ne odgovor da/ne.
     */
    Optional<Codebook> findFirstByScopeAndCompanyIdAndNameIgnoreCase(CodebookScope scope,
                                                                     Long companyId, String name);

    /**
     * Sifrarnici jedne firme - pri trajnom praznjenju arhivirane firme.
     *
     * GLOBAL sifrarnici imaju {@code company_id} null, pa ih ovaj upit ne moze zahvatiti ni
     * kad bi se htjelo: praznjenje jedne firme nikad ne dira zajednicke sifrarnike.
     */
    List<Codebook> findByCompanyId(Long companyId);

    /**
     * Brise SVE sifrarnike jedne firme - pri trajnom praznjenju.
     *
     * Upitom, iz istog razloga kao {@code TemplateRepository.deleteByCompanyIdBulk}: ucitavanje
     * pa {@code deleteAll(entiteti)} je ovdje tiho zakazivalo i sifrarnik je ostajao u bazi
     * pokazujuci na firmu koje vise nema. Pogodeni su bili tocno entiteti s {@code @Version} -
     * {@link com.example.demo.model.Template} i {@link com.example.demo.model.Codebook} - dok
     * su se zapisi i korisnici, koji verziju nemaju, brisali uredno.
     *
     * Stavke se brisu PRIJE ovoga: do firme dolaze samo preko svog sifrarnika.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Codebook c where c.companyId = :companyId")
    int deleteByCompanyIdBulk(@Param("companyId") Long companyId);
}
