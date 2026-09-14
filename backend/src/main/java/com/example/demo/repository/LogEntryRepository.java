package com.example.demo.repository;

import com.example.demo.model.LogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LogEntryRepository extends JpaRepository<LogEntry, Long> {

    /**
     * Akcije jedne firme od zadanog trenutka nadalje, najnovije prvo, po stranicama;
     * neobavezno samo jedne vrste ({@code action}).
     *
     * Filtar po vrsti akcije mora biti OVDJE, a ne u pregledniku: sa stranicenjem bi
     * filtriranje na klijentu prosijalo samo trenutnu stranicu, pa bi "Prijava" nasla
     * prijave medu zadnjih 50 zapisa i tvrdila da drugih nema.
     *
     * {@code :action is null or ...} je nacin da jedan upit pokrije oba slucaja - inace bi
     * postojale dvije skoro iste metode koje se moraju drzati usklađene.
     *
     * {@code IdDesc} na kraju nije ukras: dva zapisa umiju nastati u istoj tisucinki sekunde,
     * a bez jednoznacnog poretka ih baza smije vratiti bilo kojim redoslijedom - pa bi se isti
     * zapis znao pojaviti na dvije stranice ili nestati s obje.
     *
     * {@code Page} se ovdje koristi jer donosi i ukupan broj (COUNT ide sam od sebe), ali van
     * ne izlazi - u odgovor se prepisuje u nas {@code PageResponse}.
     */
    @Query("""
            select l from LogEntry l
            where l.companyId = :companyId
              and l.createdAt >= :from
              and (:action is null or l.action = :action)
            order by l.createdAt desc, l.id desc""")
    Page<LogEntry> search(@Param("companyId") Long companyId,
                          @Param("from") Instant from,
                          @Param("action") String action,
                          Pageable pageable);

    /**
     * Odvezuje zapise od firme koja se trajno prazni - zapisi OSTAJU, samo vise ne pripadaju
     * nikome ({@code company_id = null}, kao neuspjela prijava nepoznatim imenom).
     *
     * Audit koji nestane zajedno s onim sto biljezi ne dokazuje nista, pa se dnevnik ne brise.
     * Ne ostavlja se ni na starom {@code company_id}: id se u buducnosti moze ponovno pojaviti,
     * i tada bi se zapisi obrisane firme pomijesali s tudima.
     *
     * @return broj odvezanih zapisa
     */
    @Modifying(clearAutomatically = true)
    @Query("update LogEntry l set l.companyId = null where l.companyId = :companyId")
    int detachFromCompany(@Param("companyId") Long companyId);
}
