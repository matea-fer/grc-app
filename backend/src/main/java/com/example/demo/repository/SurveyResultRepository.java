package com.example.demo.repository;

import com.example.demo.model.SurveyResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

public interface SurveyResultRepository extends JpaRepository<SurveyResult, Long> {

    // dohvati sve ankete za određenu firmu
    List<SurveyResult> findByCompanyId(Long companyId);

    // dohvati sve zapise jednog templatea (obrasca)
    List<SurveyResult> findByTemplateId(Long templateId);

    /**
     * Koliko zapisa obrazac ima - za najavu brisanja.
     *
     * Broji se upitom, ne duljinom liste: najava se radi i nad obrascem s tisucama zapisa, a
     * ucitati ih sve da bi se ispisao broj znacilo bi povuci cijeli jsonb svakog retka.
     */
    long countByTemplateId(Long templateId);

    /**
     * Postoji li zapis tog id-a bas u tom obrascu.
     *
     * Provjera za referentni stupac: sam {@code existsById} ne bi bio dovoljan, jer bi propustio
     * id zapisa iz nekog drugog obrasca - dakle vezu koja pokazuje na krivu vrstu stvari.
     */
    boolean existsByIdAndTemplateId(Long id, Long templateId);

    /**
     * Brise SVE zapise jedne firme - pri trajnom praznjenju.
     *
     * Upitom, ne ucitavanjem pa {@code deleteAll(entiteti)}: to drugo je pri praznjenju firme
     * tiho preskakalo retke, bez ijedne greske. Vidi {@code TemplateRepository}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SurveyResult e where e.companyId = :companyId")
    int deleteByCompanyIdBulk(@Param("companyId") Long companyId);
}
