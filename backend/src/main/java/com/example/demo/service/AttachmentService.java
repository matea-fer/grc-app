package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.AttachmentResponse;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.exception.InvalidAttachmentException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Attachment;
import com.example.demo.model.AttachmentContent;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.AttachmentContentRepository;
import com.example.demo.repository.AttachmentRepository;
import com.example.demo.repository.SurveyResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Datoteke prilozene uz zapis, na stupcu tipa "file".
 *
 * Prilog je PODATAK, a ne konfiguracija - pa vrijedi isto pravilo kao za zapise: smije ga
 * dodati i maknuti svatko u firmi, ne samo administrator. Doseg cuva {@link TemplateService}
 * kao i svugdje: tudi ili nepostojeci obrazac je 404, i prije nego se dodirne ijedan prilog.
 *
 * Prilog se moze dodati SAMO postojecem zapisu. Datoteka se vezuje uz {@code surveyId}, a
 * njega prije spremanja nema; alternativa bi bila privremena pohrana koju netko mora cistiti,
 * i to za slucaj kad korisnik odustane od unosa. Zato Podaci gumb za prilaganje nude tek
 * nakon sto je zapis spremljen.
 *
 * Prilaganje i uklanjanje ulaze u povijest zapisa ({@link RecordChangeService}), a ne samo u
 * dnevnik: prilog je dokaz uz redak, pa je njegov dolazak i odlazak promjena tog retka.
 * Preuzimanje se ne biljezi - ono nista ne mijenja, a trag bi napunilo citanjem.
 */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** Duze ime se odbacuje, a ne odbija - ime datoteke nije razlog da prilog propadne. */
    private static final int MAX_FILE_NAME_LENGTH = 200;

    private final AttachmentRepository repository;
    private final AttachmentContentRepository contentRepository;
    private final SurveyResultRepository surveyRepository;
    private final ColumnDefinitionService columnDefinitionService;
    private final TemplateService templateService;
    private final AttachmentCleanup cleanup;
    private final AuthContext authContext;
    private final LogService logService;
    private final RecordChangeService recordChangeService;

    /** Granica se cuva i ovdje, ne samo u Springovoj postavci - vidi {@link #requireAcceptable}. */
    private final long maxSizeBytes;

    public AttachmentService(AttachmentRepository repository,
                             AttachmentContentRepository contentRepository,
                             SurveyResultRepository surveyRepository,
                             ColumnDefinitionService columnDefinitionService,
                             TemplateService templateService,
                             AttachmentCleanup cleanup,
                             AuthContext authContext,
                             LogService logService,
                             RecordChangeService recordChangeService,
                             @Value("${app.upload.max-file-size-kb:5120}") long maxSizeKb) {
        this.repository = repository;
        this.contentRepository = contentRepository;
        this.surveyRepository = surveyRepository;
        this.columnDefinitionService = columnDefinitionService;
        this.templateService = templateService;
        this.cleanup = cleanup;
        this.authContext = authContext;
        this.logService = logService;
        this.recordChangeService = recordChangeService;
        this.maxSizeBytes = maxSizeKb * 1024;
    }

    /**
     * Svi prilozi jednog obrasca - tablica zapisa ih treba sve odjednom.
     *
     * Dohvat po retku bi znacio jedan poziv po zapisu; ovako ide jedan po ekranu. Sadrzaj
     * datoteka se pritom ne dira, jer je u drugoj tablici ({@link AttachmentContent}).
     */
    public List<AttachmentResponse> listForTemplate(Long templateId) {
        templateService.requireOwned(templateId);
        return repository.findByTemplateIdOrderByUploadedAtAsc(templateId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    /** Prilozi jednog zapisa - koristi dijalog za uredivanje. */
    public List<AttachmentResponse> listForSurvey(Long templateId, Long surveyId) {
        templateService.requireOwned(templateId);
        requireSurveyOf(templateId, surveyId);
        return repository.findBySurveyIdOrderByUploadedAtAsc(surveyId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @Transactional
    public AttachmentResponse upload(Long templateId, Long surveyId, String columnKey, MultipartFile file) {
        Template template = templateService.requireOwned(templateId);
        // Zakljucan zapis ne prima nove priloge. Bez ovoga bi zakljucavanje bilo poluzastita:
        // vrijednosti se ne bi dale mijenjati, ali bi se dokazi i dalje smjeli podmetati.
        SurveyResultService.requireUnlocked(requireSurveyOf(templateId, surveyId));
        requireFileColumn(templateId, columnKey);
        requireAcceptable(file);

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            // prijenos je puknuo na pola; ovo nije stanje sustava nego neuspio poziv
            throw new InvalidAttachmentException("Čitanje datoteke nije uspjelo: " + e.getMessage());
        }

        Attachment attachment = repository.save(new Attachment(template.getCompanyId(), templateId, surveyId,
                columnKey, safeFileName(file.getOriginalFilename()), file.getContentType(), data.length,
                authContext.require().username()));
        contentRepository.save(new AttachmentContent(attachment.getId(), data));

        // Prilaganje dokaza je promjena retka jednako kao i upisana vrijednost - i cesto ona
        // koja se jedina broji. Ide u istu transakciju kao i sam prilog: trag koji ne mora
        // proci nije trag (vidi RecordChangeService).
        recordChangeService.attachmentAdded(surveyId, templateId, template.getCompanyId(),
                columnKey, attachment.getFileName());

        log.info("Prilozena datoteka id={} ({} B) uz zapis id={} stupac={}",
                attachment.getId(), data.length, surveyId, columnKey);
        logService.record("ATTACHMENT_ADDED", "Datoteka \"" + attachment.getFileName() + "\" uz zapis id="
                + surveyId + ", stupac \"" + columnKey + "\" (obrazac id=" + templateId + ")");
        return AttachmentResponse.from(attachment);
    }

    /** Prilog i njegov sadrzaj, za preuzimanje. */
    public Download download(Long templateId, Long attachmentId) {
        templateService.requireOwned(templateId);
        Attachment attachment = requireAttachmentOf(templateId, attachmentId);
        AttachmentContent content = contentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("AttachmentContent", attachmentId));
        return new Download(attachment, content.getData());
    }

    @Transactional
    public void delete(Long templateId, Long attachmentId) {
        templateService.requireOwned(templateId);
        Attachment attachment = requireAttachmentOf(templateId, attachmentId);
        // uklanjanje dokaza sa zakljucanog zapisa je izmjena kao i svaka druga - i najgora
        // vrsta izmjene za trag koji se pokazuje revizoru
        SurveyResultService.requireUnlocked(requireSurveyOf(templateId, attachment.getSurveyId()));

        // Kroz removeContent, a ne samo brisanjem retka: sadrzaj je large object, pa redak
        // drzi samo referencu na njega. Ovdje je do sada stajalo obicno brisanje retka i
        // datoteka je pri svakom uklanjanju priloga ostajala u bazi zauvijek.
        contentRepository.removeContent(List.of(attachmentId));
        repository.delete(attachment);

        // Uklanjanje dokaza je promjena koja se trazi cesce od prilaganja: prilog je nestao,
        // pa bez ovog retka nista ne bi govorilo da ga je uopce bilo.
        recordChangeService.attachmentRemoved(attachment.getSurveyId(), templateId,
                attachment.getCompanyId(), attachment.getColumnKey(), attachment.getFileName());

        log.info("Obrisan prilog id={} uz zapis id={}", attachmentId, attachment.getSurveyId());
        logService.record("ATTACHMENT_REMOVED", "Datoteka \"" + attachment.getFileName() + "\" sa zapisa id="
                + attachment.getSurveyId() + " (obrazac id=" + templateId + ")");
    }

    // --- provjere ---

    /**
     * Zapis mora pripadati bas ovom obrascu (koji je vec potvrden kao vlasnistvo firme).
     * Tudi ili nepostojeci jednako zavrsavaju kao 404 - kao i svugdje drugdje.
     */
    private SurveyResult requireSurveyOf(Long templateId, Long surveyId) {
        return surveyRepository.findById(surveyId)
                .filter(survey -> templateId.equals(survey.getTemplateId()))
                .orElseThrow(() -> new ResourceNotFoundException("SurveyResult", surveyId));
    }

    private Attachment requireAttachmentOf(Long templateId, Long attachmentId) {
        return repository.findById(attachmentId)
                .filter(attachment -> templateId.equals(attachment.getTemplateId()))
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
    }

    /**
     * Prilagati se smije samo na stupcu koji je za to i predviden.
     *
     * Bez ove provjere bi se datoteka mogla objesiti na bilo koji naziv stupca - i na onaj
     * koji ne postoji - pa bi u bazi zavrsili prilozi koje nijedan ekran ne prikazuje.
     */
    private void requireFileColumn(Long templateId, String columnKey) {
        ColumnDefinitionResponse column = columnDefinitionService.getForTemplate(templateId).stream()
                .filter(candidate -> candidate.columnKey().equals(columnKey))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Column \"" + columnKey + "\" not found in template " + templateId));
        if (!"file".equals(column.columnType())) {
            throw new InvalidAttachmentException(
                    "Stupac \"" + columnKey + "\" nije stupac za prilaganje datoteka.");
        }
    }

    /**
     * Velicina se provjerava i ovdje, iako je Spring vec odbija svojom granicom.
     *
     * Springova granica je postavka posluzitelja i vrijedi za SVAKI multipart zahtjev; ova je
     * pravilo ove znacajke. Da se oslonim samo na prvu, promjena te postavke (ili drugaciji
     * posluzitelj) tiho bi promijenila sto aplikacija prihvaca.
     */
    private void requireAcceptable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidAttachmentException("Datoteka je prazna.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new InvalidAttachmentException("Datoteka je prevelika (najviše "
                    + (maxSizeBytes / 1024 / 1024) + " MB).");
        }
    }

    /**
     * Ime datoteke ocisceno od putanje i skraceno.
     *
     * Preglednici salju samo ime, ali neki klijenti posalju cijelu putanju ("C:\Users\...\a.pdf"),
     * a ona u nasem imenu nema sto raditi. Kosa crta se mice i zato sto ime zavrsi u zaglavlju
     * pri preuzimanju - ondje ga ne smije nitko moci iskoristiti da pogodi gdje ce se datoteka
     * spremiti na tudem racunalu.
     */
    private String safeFileName(String original) {
        String name = original == null ? "" : original.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        // upravljacki znakovi i navodnici bi razbili Content-Disposition zaglavlje
        name = name.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            name = "datoteka";
        }
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    /** Prilog spreman za slanje - podaci i sadrzaj zajedno. */
    public record Download(Attachment attachment, byte[] data) {
    }
}
