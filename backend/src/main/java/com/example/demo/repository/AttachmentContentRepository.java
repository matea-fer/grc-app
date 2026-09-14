package com.example.demo.repository;

import com.example.demo.model.AttachmentContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttachmentContentRepository extends JpaRepository<AttachmentContent, Long> {

    /**
     * Sadrzaj ovih priloga odlazi iz baze - i referenca i sama datoteka.
     *
     * Postoji kao JEDNA metoda jer se dva koraka ne smiju razdvojiti, a jednom vec jesu:
     * brisanje pojedinacnog priloga kroz sucelje zvalo je samo {@code delete}, pa je svaka
     * obrisana datoteka ostajala u {@code pg_largeobject} bez ijednog puta do sebe. Nitko to
     * nije mogao primijetiti - prilog s ekrana uredno nestane, a prostor tiho ostane zauzet.
     * Dok su koraci dva, netko ce prije ili kasnije pozvati samo drugi.
     *
     * Redoslijed je obavezan: {@code lo_unlink} cita {@code data} iz retka, pa retka u tom
     * trenutku jos mora biti.
     */
    default void removeContent(List<Long> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return;
        }
        unlinkContent(attachmentIds);
        deleteRowsWithoutUnlinking(attachmentIds);
    }

    /**
     * Brise SAMO redak, ne i datoteku na koju pokazuje - zato se ne zove izvana nego kroz
     * {@link #removeContent}.
     *
     * Mora biti {@code @Modifying} upit, a ne izvedena metoda ({@code deleteByAttachmentIdIn}).
     * Izvedeno brisanje Spring Data izvodi u dva koraka: prvo UCITA entitete, pa ih brise
     * jedan po jedan. Ucitavanje znaci da Hibernate popuni i {@code @Lob byte[] data} - a
     * datoteke u tom trenutku vise nema, jer ju je {@link #unlinkContent} upravo unistio.
     * Postgresov driver tada padne u {@code getBinaryStream}, brisanje priloga vrati 500, i
     * prilog se ne da maknuti.
     *
     * Bulk brisanje salje obican DELETE i redak ne cita, pa mu je svejedno na sto je oid
     * pokazivao.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from AttachmentContent c where c.attachmentId in :attachmentIds")
    void deleteRowsWithoutUnlinking(@Param("attachmentIds") List<Long> attachmentIds);

    /**
     * Oslobada prostor koji zauzimaju datoteke ovih priloga. Zove se PRIJE
     * {@link #deleteRowsWithoutUnlinking}, i bez toga brisanje priloga ne oslobodi nista.
     *
     * Razlog je u tipu stupca: {@code @Lob byte[]} Hibernate na Postgresu mapira u
     * {@code oid}, dakle u referencu na <i>large object</i>. Sami bajtovi nisu u ovoj
     * tablici nego u {@code pg_largeobject}. Brisanje retka uklanja SAMO referencu -
     * datoteka ostaje u bazi, zauzima mjesto i do nje vise ne vodi nijedan put, pa je ne
     * moze obrisati ni onaj tko bi htio. Jedino {@code lo_unlink} je stvarno uklanja.
     *
     * Vraca listu jer je {@code lo_unlink} funkcija (vraca 1 po obrisanom objektu), pa
     * upit mora biti SELECT - {@code @Modifying} bi ga pokusao izvrsiti kao UPDATE i pao.
     * Mora teci unutar transakcije, jer operacije nad large objectima izvan nje nisu
     * dopustene; sve metode koje ga zovu su {@code @Transactional}.
     */
    @Query(value = "select lo_unlink(data) from attachment_content where attachment_id in (:attachmentIds)",
            nativeQuery = true)
    List<Integer> unlinkContent(@Param("attachmentIds") List<Long> attachmentIds);
}
