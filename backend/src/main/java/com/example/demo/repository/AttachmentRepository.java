package com.example.demo.repository;

import com.example.demo.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /** Prilozi jednog zapisa, svih njegovih stupaca. */
    List<Attachment> findBySurveyIdOrderByUploadedAtAsc(Long surveyId);

    /**
     * Prilozi cijelog obrasca - tablica zapisa ih treba sve odjednom.
     *
     * Dohvat po zapisu bi znacio jedan poziv po retku; ovako ide jedan po ekranu. Sadrzaj
     * se pritom ne dira, jer je u drugoj tablici.
     */
    List<Attachment> findByTemplateIdOrderByUploadedAtAsc(Long templateId);

    /** Koliko prilozenih datoteka visi o obrascu - za najavu brisanja. */
    long countByTemplateId(Long templateId);

    /** Prilozi jednog stupca - trebaju kad se stupac brise ili mu se mijenja tip. */
    List<Attachment> findByTemplateIdAndColumnKey(Long templateId, String columnKey);

    /** Svi prilozi jedne firme - pri trajnom praznjenju arhivirane firme. */
    List<Attachment> findByCompanyId(Long companyId);
}
