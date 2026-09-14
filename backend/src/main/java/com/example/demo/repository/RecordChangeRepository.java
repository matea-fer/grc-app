package com.example.demo.repository;

import com.example.demo.model.RecordChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Povijest promjena zapisa.
 *
 * Sve metode su kljucane po zapisu, obrascu ili firmi - nikad po samom {@code columnKey}.
 * Isti razlog kao kod {@code CodebookItemRepository}: kljuc stupca ("status") nije jedinstven
 * izvan svog obrasca, pa bi upit po njemu vratio tude retke bez ijedne greske.
 */
public interface RecordChangeRepository extends JpaRepository<RecordChange, Long> {

    /** Povijest jednog zapisa, najnovije prvo - to je jedini prikaz koji postoji. */
    List<RecordChange> findBySurveyIdOrderByChangedAtDescIdDesc(Long surveyId);

    /** Zapis se brise - njegova povijest nema vise na sto pokazivati. */
    void deleteBySurveyId(Long surveyId);

    /** Brise se cijeli obrazac, sa svim svojim zapisima. */
    void deleteByTemplateId(Long templateId);

    /** Firma se trajno prazni. @return broj obrisanih zapisa povijesti, za sazetak praznjenja */
    int deleteByCompanyId(Long companyId);
}
