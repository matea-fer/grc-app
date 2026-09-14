package com.example.demo.service;

import com.example.demo.model.Attachment;
import com.example.demo.repository.AttachmentContentRepository;
import com.example.demo.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Brise priloge kad nestane ono cemu su pripadali - zapis, stupac ili cijeli obrazac.
 *
 * Postoji kao zaseban razred, a ne kao dio {@link AttachmentService}, zbog smjera ovisnosti:
 * {@code AttachmentService} treba {@link TemplateService} (da provjeri vlasnistvo), pa bi
 * {@code TemplateService} koji njega zove zatvorio krug. Ovaj razred ovisi samo o dva
 * repozitorija, koji ne ovise ni o cemu, pa ga svatko smije zvati.
 *
 * Zasto se prilozi uopce moraju brisati, za razliku od brojaca rednih brojeva koji se ne
 * brisu: brojac je jedan redak i nikome ne smeta, a prilog je datoteka u bazi. Zaboravljeni
 * prilog je podatak koji je korisnik mislio da je obrisao, i koji zauzima mjesto zauvijek.
 */
@Component
public class AttachmentCleanup {
    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanup.class);

    private final AttachmentRepository repository;
    private final AttachmentContentRepository contentRepository;

    public AttachmentCleanup(AttachmentRepository repository, AttachmentContentRepository contentRepository) {
        this.repository = repository;
        this.contentRepository = contentRepository;
    }

    /** @return broj obrisanih priloga */
    @Transactional
    public int forSurvey(Long surveyId) {
        return remove(repository.findBySurveyIdOrderByUploadedAtAsc(surveyId), "zapis id=" + surveyId);
    }

    /** @return broj obrisanih priloga */
    @Transactional
    public int forTemplate(Long templateId) {
        return remove(repository.findByTemplateIdOrderByUploadedAtAsc(templateId), "obrazac id=" + templateId);
    }

    /**
     * Svi prilozi jedne firme - pri trajnom praznjenju arhivirane firme.
     *
     * Ide po firmi, a ne obrazac po obrazac, jer {@code attachment} nosi {@code company_id}
     * izravno. Prilog cijim je obrascem netko u meduvremenu obrisao trag tako ipak nestane;
     * petlja po obrascima bi ga preskocila i ostavila datoteku bez vlasnika.
     *
     * @return broj obrisanih priloga
     */
    @Transactional
    public int forCompany(Long companyId) {
        return remove(repository.findByCompanyId(companyId), "firma id=" + companyId);
    }

    /**
     * Prilozi jednog stupca - kad se stupac obrise ili mu se promijeni tip.
     *
     * Promjena tipa je ovdje jednako vazna kao brisanje: stupac koji vise nije "file" nema
     * gdje prikazati svoje priloge, pa bi oni ostali u bazi bez ijednog puta do njih.
     *
     * @return broj obrisanih priloga
     */
    @Transactional
    public int forColumn(Long templateId, String columnKey) {
        return remove(repository.findByTemplateIdAndColumnKey(templateId, columnKey),
                "stupac \"" + columnKey + "\" obrasca id=" + templateId);
    }

    /**
     * Stupac je preimenovan - prilozi ga moraju slijediti.
     *
     * Za razliku od brojaca rednih brojeva, koji se sam popravi (novi nastane iznad vec
     * upisanih vrijednosti), prilog se ne moze popraviti: ostao bi vezan uz naziv kojeg vise
     * nema, pa ga nijedan ekran ne bi prikazao ni ponudio na brisanje.
     *
     * @return broj priloga koji su presli na novi naziv
     */
    @Transactional
    public int renameColumn(Long templateId, String oldKey, String newKey) {
        if (oldKey.equals(newKey)) {
            return 0;
        }
        List<Attachment> attachments = repository.findByTemplateIdAndColumnKey(templateId, oldKey);
        for (Attachment attachment : attachments) {
            attachment.setColumnKey(newKey);
        }
        repository.saveAll(attachments);
        return attachments.size();
    }

    private int remove(List<Attachment> attachments, String what) {
        if (attachments.isEmpty()) {
            return 0;
        }
        List<Long> ids = attachments.stream().map(Attachment::getId).toList();
        // Datoteke prve, i to kroz removeContent (koji radi lo_unlink pa tek onda brisanje):
        // sadrzaj je large object, pa brisanje retka oslobada samo referencu, a ne i prostor.
        // Sadrzaj prije priloga: prilog bez sadrzaja je samo neupotrebljiv redak, a sadrzaj bez
        // priloga je redak do kojeg vise nitko ne moze doci ni da ga zeli obrisati.
        contentRepository.removeContent(ids);
        repository.deleteAll(attachments);
        log.info("Obrisano {} prilog(a) - {}", attachments.size(), what);
        return attachments.size();
    }
}
