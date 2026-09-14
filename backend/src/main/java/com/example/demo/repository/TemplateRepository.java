package com.example.demo.repository;

import com.example.demo.model.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    // svi templatei jedne firme
    List<Template> findByCompanyId(Long companyId);

    /**
     * Brise SVE obrasce jedne firme - pri trajnom praznjenju.
     *
     * Upitom, a ne ucitavanjem pa {@code deleteAll(entiteti)}: to drugo je pri praznjenju
     * firme tiho zakazivalo. Obrazac je ostajao u bazi i dalje pokazujuci na firmu koje vise
     * nema, bez ijedne greske - purge ga je uredno PREBROJAO ("1 obraz." u dnevniku) i nije
     * ga obrisao. Nadeno u razvojnoj bazi za dvije firme, pa reproducirano.
     *
     * Bulk brisanje ne prolazi kroz perzistencijski kontekst i nema sto preskociti; usput je
     * i jedan upit umjesto jednog po obrascu.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Template t where t.companyId = :companyId")
    int deleteByCompanyIdBulk(@Param("companyId") Long companyId);
}
