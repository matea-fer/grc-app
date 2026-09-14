package com.example.demo.service;

import com.example.demo.model.Codebook;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.model.User;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.repository.ColumnSequenceRepository;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.repository.RecordChangeRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Fizicki uklanja sve podatke jedne firme. Zove se SAMO nad vec arhiviranom firmom, i to
 * iz {@link CompanyService#purge} - provjeru da je firma arhivirana radi on, ne ovaj razred.
 *
 * Postoji odvojeno od {@code CompanyService} iz istog razloga kao {@link AttachmentCleanup}:
 * dodiruje sedam tablica preko sedam repozitorija, a repozitoriji ne ovise ni o cemu, pa
 * ovaj razred ne moze zatvoriti nijedan krug ovisnosti.
 *
 * <b>Sto se NE brise:</b>
 * <ul>
 *   <li><b>Dnevnik</b> - zapisi ostaju, samo im se makne veza na firmu. Audit koji nestane
 *       zajedno s onim sto biljezi ne dokazuje nista; ostaje barem trag da je firma
 *       postojala i tko ju je uklonio.</li>
 *   <li><b>GLOBAL sifrarnici</b> - zajednicki su, nemaju {@code company_id} i praznjenje
 *       jedne firme ih ne smije dirati.</li>
 * </ul>
 *
 * Redoslijed nije proizvoljan: ide se od onoga sto na nesto POKAZUJE prema onome na sto se
 * pokazuje. Stranih kljuceva u shemi nema, pa ih baza nece nametnuti - ali obrnut redoslijed
 * bi pri prekidu (npr. pad usred posla) ostavio priloge i zapise bez obrasca, dakle podatke
 * do kojih vise nijedan ekran ne vodi. Cijela metoda je jedna transakcija, pa se ili izvrsi
 * sve ili nista.
 */
@Component
public class CompanyPurgeService {
    private static final Logger log = LoggerFactory.getLogger(CompanyPurgeService.class);

    private final TemplateRepository templateRepository;
    private final SurveyResultRepository surveyRepository;
    private final ColumnSequenceRepository sequenceRepository;
    private final CodebookRepository codebookRepository;
    private final CodebookItemRepository codebookItemRepository;
    private final UserRepository userRepository;
    private final LogEntryRepository logEntryRepository;
    private final RecordChangeRepository recordChangeRepository;
    private final AttachmentCleanup attachmentCleanup;

    public CompanyPurgeService(TemplateRepository templateRepository,
                               SurveyResultRepository surveyRepository,
                               ColumnSequenceRepository sequenceRepository,
                               CodebookRepository codebookRepository,
                               CodebookItemRepository codebookItemRepository,
                               UserRepository userRepository,
                               LogEntryRepository logEntryRepository,
                               RecordChangeRepository recordChangeRepository,
                               AttachmentCleanup attachmentCleanup) {
        this.templateRepository = templateRepository;
        this.surveyRepository = surveyRepository;
        this.sequenceRepository = sequenceRepository;
        this.codebookRepository = codebookRepository;
        this.codebookItemRepository = codebookItemRepository;
        this.userRepository = userRepository;
        this.logEntryRepository = logEntryRepository;
        this.recordChangeRepository = recordChangeRepository;
        this.attachmentCleanup = attachmentCleanup;
    }

    /**
     * @param companyId firma koja se prazni; mora biti arhivirana (provjerava pozivatelj)
     * @return sazetak za audit zapis i poruku korisniku
     */
    @Transactional
    public PurgeSummary purge(Long companyId) {
        // 1. prilozi - prvi jer su jedino sto zauzima prostor izvan svoje tablice
        //    (large objecti); AttachmentCleanup ih oslobada kroz lo_unlink
        int attachments = attachmentCleanup.forCompany(companyId);

        // 2. povijest promjena, pa zapisi na koje se odnosi
        int changes = recordChangeRepository.deleteByCompanyId(companyId);
        List<SurveyResult> surveys = surveyRepository.findByCompanyId(companyId);
        surveyRepository.deleteByCompanyIdBulk(companyId);

        // 3. brojaci rednih brojeva, pa obrasci na koje pokazuju
        List<Template> templates = templateRepository.findByCompanyId(companyId);
        List<Long> templateIds = templates.stream().map(Template::getId).toList();
        if (!templateIds.isEmpty()) {
            sequenceRepository.deleteByTemplateIdIn(templateIds);
        }
        // Upitom, ne deleteAll(entiteti) - vidi TemplateRepository.deleteByCompanyIdBulk:
        // ucitavanje pa brisanje entiteta ovdje je tiho preskakalo obrasce.
        templateRepository.deleteByCompanyIdBulk(companyId);

        // 4. sifrarnici firme: stavke pa sifrarnici (stavka do firme dolazi samo preko
        //    svog sifrarnika, pa bi obrnut redoslijed ostavio stavke bez puta do njih)
        List<Codebook> codebooks = codebookRepository.findByCompanyId(companyId);
        for (Codebook codebook : codebooks) {
            codebookItemRepository.deleteByCodebookId(codebook.getId());
        }
        // Upitom, ne deleteAll(entiteti) - isto kao kod obrazaca; oboje nose @Version.
        codebookRepository.deleteByCompanyIdBulk(companyId);

        // 5. korisnici
        List<User> users = userRepository.findByCompanyId(companyId);
        userRepository.deleteByCompanyIdBulk(companyId);

        // 6. dnevnik se NE brise, samo odvezuje
        int detachedLogs = logEntryRepository.detachFromCompany(companyId);

        PurgeSummary summary = new PurgeSummary(templates.size(), surveys.size(), attachments,
                codebooks.size(), users.size(), changes, detachedLogs);
        log.info("Ispraznjena firma id={}: {}", companyId, summary);
        return summary;
    }

    /** Koliko je cega uklonjeno - ide u audit zapis i u poruku korisniku. */
    public record PurgeSummary(int templates, int surveys, int attachments, int codebooks,
                               int users, int changes, int detachedLogs) {

        /** Ljudski opis za dnevnik i poruku na ekranu. */
        public String describe() {
            return templates + " obraz., " + surveys + " zapis(a), " + attachments + " prilog(a), "
                    + codebooks + " sifrarnik(a), " + users + " korisnik(a), "
                    + changes + " zapis(a) povijesti; "
                    + detachedLogs + " zapis(a) dnevnika zadrzano";
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}
