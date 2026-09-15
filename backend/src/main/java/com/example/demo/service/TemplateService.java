package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateTemplateRequest;
import com.example.demo.dto.DeletePlanResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.RecordOptionResponse;
import com.example.demo.dto.TemplateResponse;
import com.example.demo.dto.UpdateTemplateRequest;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.exception.SchemaValidationException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.AttachmentRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Templatei (obrasci) jedne firme. Sve je vezano uz firmu iz {@link TenantContext} -
 * firma se nikad ne uzima iz putanje ni tijela, pa jedna firma ne vidi ni ne dira
 * tude templatee.
 *
 * Ujedno je i cuvar vlasnistva: {@link #requireOwned(Long)} vracaju drugi servisi
 * (stupci, zapisi) prije nego dodirnu bilo sto ispod templatea. Tudi ili nepostojeci
 * template zavrsava kao 404 (ne 403) - da se ni ne otkrije da postoji.
 *
 * Citanje i pisanje nemaju isti doseg, po istom pravilu kao kod sifrarnika
 * ({@link CodebookService}): shema obrasca je KONFIGURACIJA, a ne podatak - obican
 * korisnik po njoj unosi zapise, ali je ne mijenja.
 *
 * <pre>
 *                citanje                       pisanje
 *   obrazac      svi u toj firmi               ADMIN + TENANT_ADMIN te firme
 *   stupci       svi u toj firmi               ADMIN + TENANT_ADMIN te firme
 *   zapisi       svi u toj firmi               svi u toj firmi  (to su podaci)
 * </pre>
 */
@Service
public class TemplateService {
    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    private final TemplateRepository repository;
    private final SurveyResultRepository surveyRepository;
    private final TenantContext tenantContext;
    private final AuthContext authContext;
    private final AttachmentCleanup attachmentCleanup;
    private final RecordChangeService recordChangeService;
    private final LogService logService;
    private final AttachmentRepository attachmentRepository;
    private final ReferenceLookup referenceLookup;

    public TemplateService(TemplateRepository repository,
                           SurveyResultRepository surveyRepository,
                           TenantContext tenantContext,
                           AuthContext authContext,
                           AttachmentRepository attachmentRepository,
                           AttachmentCleanup attachmentCleanup,
                           RecordChangeService recordChangeService,
                           LogService logService,
                           ReferenceLookup referenceLookup) {
        this.repository = repository;
        this.surveyRepository = surveyRepository;
        this.tenantContext = tenantContext;
        this.authContext = authContext;
        this.attachmentRepository = attachmentRepository;
        this.attachmentCleanup = attachmentCleanup;
        this.recordChangeService = recordChangeService;
        this.logService = logService;
        this.referenceLookup = referenceLookup;
    }

    public List<TemplateResponse> list() {
        return repository.findByCompanyId(tenantContext.require()).stream()
                .map(TemplateResponse::from)
                .toList();
    }

    /**
     * Jedan obrazac. Ide kroz {@link #requireOwned(Long)}, pa je tudi obrazac 404 - isto
     * kao na svakoj drugoj putanji ispod njega.
     */
    public TemplateResponse getOne(Long id) {
        return TemplateResponse.from(requireOwned(id));
    }

    public TemplateResponse create(CreateTemplateRequest request) {
        requireEditor();
        Long companyId = tenantContext.require();
        Template saved = repository.save(new Template(companyId, request.name()));
        log.info("Created Template id={} companyId={}", saved.getId(), companyId);
        logService.record("TEMPLATE_CREATED", "Obrazac \"" + saved.getName() + "\" (id=" + saved.getId() + ")");
        return TemplateResponse.from(saved);
    }

    public TemplateResponse rename(Long id, UpdateTemplateRequest request) {
        Template template = requireEditable(id);
        template.setName(request.name());
        Template saved = repository.save(template);
        log.info("Renamed Template id={}", saved.getId());
        logService.record("TEMPLATE_RENAMED", "Obrazac id=" + id + " preimenovan u \"" + saved.getName() + "\"");
        return TemplateResponse.from(saved);
    }

    /**
     * Ukljuci/iskljuci "upitnik" nacin za obrazac. Kao i uredivanje sheme, smije samo
     * administrator (requireEditable). Kad je ukljucen, retke dodaje/brise samo administrator,
     * a korisnik na Podacima samo bira odgovor - provodi {@code SurveyResultService}.
     */
    public TemplateResponse setQuestionnaire(Long id, boolean value) {
        Template template = requireEditable(id);
        template.setQuestionnaire(value);
        Template saved = repository.save(template);
        log.info("Template id={} questionnaire={}", saved.getId(), value);
        logService.record("TEMPLATE_UPDATED",
                "Obrazac id=" + id + (value ? " označen kao upitnik" : " više nije upitnik"));
        return TemplateResponse.from(saved);
    }

    /**
     * Jedna stranica zapisa ovog obrasca kao ponuda za vezu: samo id i naziv, uz pretragu.
     *
     * Stoji ovdje, a ne uz zapise, jer je to pogled na OBRAZAC kao izvor ponude - trazi ga
     * onaj tko popunjava neki drugi obrazac. Zato mu treba samo pravo citanja te firme, kao i
     * za shemu: vezu bira onaj tko unosi podatke, a ne administrator.
     *
     * @param displayKey stupac koji sluzi kao naziv; bira ga referentni stupac koji pita, pa
     *                   dolazi kao parametar - ali se provjerava protiv sheme ovog obrasca
     */
    public PageResponse<RecordOptionResponse> options(Long id, String displayKey, String search, int page,
                                                      List<Long> ids) {
        Template template = requireOwned(id);
        requireDeclared(template, displayKey);
        // Zatrazeni id-evi imaju prednost nad pretragom: to nije "ponudi mi izbor" nego
        // "kako se zovu ovi". Tablica tako jednim pozivom razrijesi sve veze koje na njoj
        // stoje, umjesto da ih trazi kroz stranice ponude - u kojima ih uopce ne mora biti.
        if (ids != null && !ids.isEmpty()) {
            return referenceLookup.named(template, displayKey, ids);
        }
        return referenceLookup.options(template, displayKey, search, page);
    }

    /** Stupac mora postojati u obrascu - inace bi upit tiho vratio prazne nazive. */
    private void requireDeclared(Template template, String columnKey) {
        List<ColumnEntry> definitions =
                template.getDefinitions() != null ? template.getDefinitions() : List.of();
        for (ColumnEntry entry : definitions) {
            if (entry.key().equals(columnKey)) {
                return;
            }
        }
        throw new SchemaValidationException(List.of("unknown column \"" + columnKey
                + "\" in template " + template.getId()));
    }

    /** Brise template, sve njegove zapise i priloge (obrazac nestaje s podacima zajedno). */
    @Transactional
    public void delete(Long id) {
        Template template = requireEditable(id);
        // Obrazac na koji pokazuju stupci drugih obrazaca ne smije nestati: njihove bi veze
        // ostale pokazivati u prazno, a stupac bi i dalje tvrdio da bira iz obrasca kojeg nema.
        referenceLookup.requireTemplateDeletable(id);
        removeOne(template);
    }

    /**
     * Sto bi skupno brisanje odnijelo - bez ijedne izmjene.
     *
     * Broji zapise i priloge unaprijed, jer "3 obrasca" i "3 obrasca i 1.240 zapisa" nisu ista
     * odluka, a nakon brisanja se to vise nema gdje vidjeti.
     */
    public DeletePlanResponse deletionPlan(List<Long> ids) {
        List<DeletePlanResponse.TemplateDeletion> templates = ids.stream()
                .map(this::requireEditable)
                .map(template -> new DeletePlanResponse.TemplateDeletion(
                        template.getId(),
                        template.getName(),
                        surveyRepository.countByTemplateId(template.getId()),
                        attachmentRepository.countByTemplateId(template.getId())))
                .toList();
        return new DeletePlanResponse(templates, referenceLookup.blockersFor(ids));
    }

    /**
     * Brise VISE obrazaca odjednom.
     *
     * Postoji zbog jednog svojstva veza koje pojedinacno brisanje ne moze iskoristiti: veza
     * izmedu dva obrasca koji oba nestaju ne ostavlja nista da visi. Pojedinacno se takav par
     * ne da obrisati ni jednim redoslijedom - svaki od njih drzi onaj drugi - pa se prvo mora
     * rucno obrisati stupac s vezom. Ovdje je prepreka samo veza koja dolazi IZVANA, iz obrasca
     * koji ostaje; nju provjerava {@code requireDeletableTogether}.
     *
     * Pojedinacno brisanje ostaje kakvo je bilo i dalje ide kroz {@link #delete}.
     */
    @Transactional
    public void deleteAll(List<Long> ids) {
        List<Template> templates = ids.stream().map(this::requireEditable).toList();
        referenceLookup.requireDeletableTogether(ids);
        templates.forEach(this::removeOne);
        logService.record("TEMPLATES_DELETED", "Skupno obrisano " + templates.size()
                + " obrazaca: " + templates.stream().map(Template::getName)
                        .collect(java.util.stream.Collectors.joining(", ")));
    }

    /**
     * Brisanje jednog obrasca sa svime sto uz njega visi - bez provjere veza.
     *
     * Provjeru radi pozivatelj, jer se razlikuje: pojedinacno brisanje ne trpi nijednu vezu
     * izvana, a skupno samo one iz obrazaca koji ostaju.
     */
    private void removeOne(Template template) {
        Long id = template.getId();
        List<SurveyResult> surveys = surveyRepository.findByTemplateId(id);
        // prilozi su datoteke u bazi i ne zive u jsonb-u zapisa, pa ih brisanje zapisa
        // samo po sebi ne bi odnijelo - ostali bi zauzimati mjesto zauvijek
        int attachments = attachmentCleanup.forTemplate(id);
        // povijest promjena zivi u vlastitoj tablici, jednako kao prilozi
        recordChangeService.forTemplate(id);
        surveyRepository.deleteAll(surveys);
        repository.delete(template);
        log.info("Deleted Template id={} with {} record(s) and {} attachment(s)", id, surveys.size(), attachments);
        logService.record("TEMPLATE_DELETED",
                "Obrazac \"" + template.getName() + "\" (id=" + id + ") i " + surveys.size() + " zapis(a)"
                        + (attachments > 0 ? " te " + attachments + " prilog(a)" : ""));
    }

    /**
     * Template trazenog id-a, ali samo ako pripada firmi iz konteksta. Tudi ili
     * nepostojeci template jednako zavrsavaju kao 404.
     */
    public Template requireOwned(Long id) {
        Long companyId = tenantContext.require();
        return repository.findById(id)
                .filter(template -> companyId.equals(template.getCompanyId()))
                .orElseThrow(() -> new ResourceNotFoundException("Template", id));
    }

    /**
     * Template koji pozivatelj smije MIJENJATI - i sam obrazac i njegove stupce.
     * Zove ga i {@link ColumnDefinitionService} prije svake izmjene sheme.
     *
     * Prvo vlasnistvo (404 za tudeg), pa tek onda ovlast (403). Obrnutim redoslijedom bi
     * 403 nad tudim obrascem odao da taj obrazac postoji.
     */
    public Template requireEditable(Long id) {
        Template template = requireOwned(id);
        requireEditor();
        return template;
    }

    /**
     * Smije li pozivatelj uopce mijenjati sheme - obrasce i njihove stupce.
     *
     * Doseg po firmi ovime se NE prosiruje: administrator firme ostaje unutar svoje
     * firme, jednako kao obican korisnik (vidi TenantFilter). Sire ovlasti koje ima
     * odnose se na to STO smije raditi, ne nad cijom firmom.
     */
    public void requireEditor() {
        AuthenticatedUser user = authContext.require();
        if (!user.isAdmin() && !user.isTenantAdmin()) {
            throw new ForbiddenException("Obrasce smije uređivati samo administrator.");
        }
    }

    /** Sprema izmijenjeni template (koriste servisi stupaca nakon izmjene definicija). */
    public Template save(Template template) {
        return repository.save(template);
    }
}
