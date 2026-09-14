package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.TemplateResponse;
import com.example.demo.dto.TransferPlanResponse;
import com.example.demo.dto.TransferTemplatesRequest;
import com.example.demo.exception.InvalidCompanyException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.Template;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prijenos obrazaca iz jedne firme u drugu ("provizioniranje").
 *
 * Ovo je jedina radnja u aplikaciji koja NAMJERNO prelazi granicu izmedu firmi, pa se tako i
 * ponasa: smije je pokrenuti samo globalni ADMIN, a obje firme se navode izrijekom. Ne ide
 * kroz {@link TemplateService} ni {@link CodebookService} - oni rade nad JEDNOM firmom iz
 * konteksta i svaki bi poziv nad drugom odbili s 404. Zato se ovdje radi izravno nad
 * repozitorijima, uz vlastitu provjeru ovlasti.
 *
 * Prenosi se SAMO definicija: obrasci, njihovi stupci i sifrarnici na koje stupci pokazuju.
 * Zapisi, prilozi i povijest ostaju u izvornoj firmi - prenosi se oblik, ne sadrzaj.
 *
 * Dvije stvari se pritom moraju preslikati, jer su id-evi vezani uz firmu:
 *
 * <ul>
 *   <li><b>sifrarnik</b> ({@code codebookId}) - TENANT sifrarnik izvorne firme u ciljnoj ne
 *       postoji. Ako ondje postoji sifrarnik ISTOG naziva, stupac se veze na njega; inace se
 *       stvara nov, sa stavkama. GLOBAL sifrarnike dijele sve firme, pa ostaju kakvi jesu.</li>
 *   <li><b>veza na drugi obrazac</b> ({@code options.targetTemplateId}) - pokazuje na obrazac
 *       izvorne firme. Ako je taj obrazac u odabiru, veza se preusmjerava na njegovu kopiju;
 *       ako nije, veza se prekida i to se javlja u izvjestaju.</li>
 * </ul>
 */
@Service
public class TemplateTransferService {

    private static final Logger log = LoggerFactory.getLogger(TemplateTransferService.class);

    /** Dodatak na naziv kad ga ciljna firma vec ima zauzetog. */
    private static final String COPY_SUFFIX = "(kopija)";

    private final TemplateRepository templateRepository;
    private final CodebookRepository codebookRepository;
    private final CodebookItemRepository codebookItemRepository;
    private final CompanyRepository companyRepository;
    private final AuthContext authContext;
    private final LogService logService;

    public TemplateTransferService(TemplateRepository templateRepository,
                                   CodebookRepository codebookRepository,
                                   CodebookItemRepository codebookItemRepository,
                                   CompanyRepository companyRepository,
                                   AuthContext authContext,
                                   LogService logService) {
        this.templateRepository = templateRepository;
        this.codebookRepository = codebookRepository;
        this.codebookItemRepository = codebookItemRepository;
        this.companyRepository = companyRepository;
        this.authContext = authContext;
        this.logService = logService;
    }

    /**
     * Obrasci jedne firme - za popis s kvacicama u dijalogu.
     *
     * Postoji jer {@code GET /api/templates} vraca obrasce firme IZ KONTEKSTA, a ovdje se
     * bira izvorna firma koja ne mora biti ona aktivna.
     */
    public List<TemplateResponse> listFor(Long companyId) {
        authContext.requireAdmin();
        requireCompany(companyId);
        return templateRepository.findByCompanyId(companyId).stream()
                .map(TemplateResponse::from)
                .toList();
    }

    /**
     * Sto bi prijenos napravio - bez ijedne izmjene.
     *
     * Postoji zato sto se posljedice ne vide unaprijed: koji sifrarnik nastaje a koji se
     * dijeli, koji obrazac dobiva drugi naziv, koja veza ostaje bez cilja. Najava i izvrsenje
     * racunaju istim kodom ({@link #plan}), pa se ne mogu raziti.
     */
    public TransferPlanResponse preview(TransferTemplatesRequest request) {
        authContext.requireAdmin();
        return plan(request).response();
    }

    /**
     * Izvedi prijenos i vrati sto je napravljeno.
     *
     * Sve u jednoj transakciji: obrazac bez svojih sifrarnika je neupotrebljiv, pa je pola
     * prenesenog gore od nicega.
     */
    @Transactional
    public TransferPlanResponse transfer(TransferTemplatesRequest request) {
        authContext.requireAdmin();
        Plan plan = plan(request);

        // 1. sifrarnici - prije obrazaca, jer stupci na njih pokazuju
        Map<Long, Long> codebookMapping = new HashMap<>();
        for (PlannedCodebook planned : plan.codebooks) {
            codebookMapping.put(planned.sourceId, resolveCodebook(planned, request.targetCompanyId()));
        }

        // 2. obrasci, zasad bez veza - veza treba id kopije, a on nastaje tek spremanjem
        Map<Long, Long> templateMapping = new LinkedHashMap<>();
        for (PlannedTemplate planned : plan.templates) {
            Template copy = new Template(request.targetCompanyId(), planned.targetName);
            copy.setDefinitions(remapColumns(planned.source.getDefinitions(), codebookMapping, Map.of()));
            templateMapping.put(planned.source.getId(), templateRepository.save(copy).getId());
        }

        // 3. veze - sad kad svaka kopija ima id, prolazi se drugi put
        for (PlannedTemplate planned : plan.templates) {
            if (!hasReference(planned.source)) {
                continue;
            }
            Template copy = templateRepository.findById(templateMapping.get(planned.source.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Template",
                            templateMapping.get(planned.source.getId())));
            copy.setDefinitions(remapColumns(planned.source.getDefinitions(), codebookMapping, templateMapping));
            templateRepository.save(copy);
        }

        log.info("Preneseno {} obrazaca iz firme {} u firmu {}", plan.templates.size(),
                request.sourceCompanyId(), request.targetCompanyId());
        logService.record("TEMPLATES_TRANSFERRED", "Preneseno " + plan.templates.size()
                + " obrazaca iz firme id=" + request.sourceCompanyId()
                + " u firmu id=" + request.targetCompanyId());

        return plan.response();
    }

    // ===================== izracun =====================

    private Plan plan(TransferTemplatesRequest request) {
        if (request.sourceCompanyId().equals(request.targetCompanyId())) {
            throw new InvalidCompanyException("Izvorna i ciljna firma ne smiju biti iste.");
        }
        requireCompany(request.sourceCompanyId());
        requireCompany(request.targetCompanyId());

        List<Template> sources = templateRepository.findAllById(request.templateIds());
        if (sources.size() != request.templateIds().size()) {
            throw new ResourceNotFoundException("Template", null);
        }
        for (Template source : sources) {
            // obrazac tude firme ne smije uci u prijenos ni kad je njegov id pogoden
            if (!source.getCompanyId().equals(request.sourceCompanyId())) {
                throw new ResourceNotFoundException("Template", source.getId());
            }
        }

        Set<Long> selected = sources.stream().map(Template::getId).collect(Collectors.toSet());
        return new Plan(
                planTemplates(sources, request.targetCompanyId()),
                planCodebooks(sources, request.targetCompanyId()),
                planBrokenReferences(sources, selected));
    }

    /**
     * Naziv pod kojim obrazac nastaje.
     *
     * Ciljna firma smije vec imati obrazac istog naziva - tada nastaje kopija s dodatkom, a
     * postojeci se ne dira. Broji se i unutar samog prijenosa: dva obrasca istog naziva u
     * jednom potezu ne smiju dobiti isti naziv.
     */
    private List<PlannedTemplate> planTemplates(List<Template> sources, Long targetCompanyId) {
        List<String> taken = templateRepository.findByCompanyId(targetCompanyId).stream()
                .map(Template::getName)
                .collect(Collectors.toCollection(ArrayList::new));

        List<PlannedTemplate> planned = new ArrayList<>();
        for (Template source : sources) {
            String name = freeName(source.getName(), taken);
            taken.add(name);
            planned.add(new PlannedTemplate(source, name, !name.equals(source.getName())));
        }
        return planned;
    }

    private String freeName(String wanted, List<String> taken) {
        if (taken.stream().noneMatch(wanted::equalsIgnoreCase)) {
            return wanted;
        }
        String candidate = wanted + " " + COPY_SUFFIX;
        int counter = 2;
        while (taken.stream().anyMatch(candidate::equalsIgnoreCase)) {
            candidate = wanted + " " + COPY_SUFFIX.replace(")", " " + counter++ + ")");
        }
        return candidate;
    }

    /** Sifrarnici na koje kopirani stupci pokazuju, i sto se s njima dogada. */
    private List<PlannedCodebook> planCodebooks(List<Template> sources, Long targetCompanyId) {
        Set<Long> ids = sources.stream()
                .flatMap(t -> t.getDefinitions().stream())
                .map(ColumnEntry::codebookId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        List<PlannedCodebook> planned = new ArrayList<>();
        for (Long id : ids) {
            Codebook source = codebookRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Codebook", id));
            if (source.getScope() == CodebookScope.GLOBAL) {
                // globalni vrijedi za sve firme - ne kopira se i id ostaje isti
                planned.add(new PlannedCodebook(id, source.getName(), CodebookScope.GLOBAL, id,
                        countItems(id)));
                continue;
            }
            Long existing = codebookRepository
                    .findFirstByScopeAndCompanyIdAndNameIgnoreCase(CodebookScope.TENANT,
                            targetCompanyId, source.getName())
                    .map(Codebook::getId)
                    .orElse(null);
            planned.add(new PlannedCodebook(id, source.getName(), CodebookScope.TENANT, existing,
                    countItems(existing != null ? existing : id)));
        }
        return planned;
    }

    /**
     * Veze koje ostaju bez cilja.
     *
     * Stupac tipa {@code reference} koji pokazuje na obrazac izvan odabira ne moze se prenijeti
     * cijel: u ciljnoj firmi tog obrasca nema. Stupac ostaje, veza se prekida - i to mora
     * stajati u najavi, jer se poslije po samom stupcu ne vidi da je nesto izgubljeno.
     */
    private List<TransferPlanResponse.BrokenReference> planBrokenReferences(List<Template> sources,
                                                                            Set<Long> selected) {
        List<TransferPlanResponse.BrokenReference> broken = new ArrayList<>();
        for (Template source : sources) {
            for (ColumnEntry column : source.getDefinitions()) {
                Long target = column.options().targetTemplateId();
                if (target == null || selected.contains(target)) {
                    continue;
                }
                String targetName = templateRepository.findById(target)
                        .map(Template::getName)
                        .orElse("obrazac id=" + target);
                broken.add(new TransferPlanResponse.BrokenReference(
                        source.getName(), column.key(), targetName));
            }
        }
        return broken;
    }

    // ===================== izvrsenje =====================

    /** Postojeci sifrarnik, ili nov sa svim stavkama. */
    private Long resolveCodebook(PlannedCodebook planned, Long targetCompanyId) {
        if (planned.targetId != null) {
            return planned.targetId;
        }
        Codebook copy = codebookRepository.save(
                new Codebook(planned.name, CodebookScope.TENANT, targetCompanyId));
        for (CodebookItem item : codebookItemRepository.findByCodebookIdOrderBySortOrderAsc(planned.sourceId)) {
            codebookItemRepository.save(new CodebookItem(copy.getId(), item.getCode(), item.getName(),
                    item.isActive(), item.getSortOrder()));
        }
        return copy.getId();
    }

    /** Stupci s preslikanim id-evima; {@code templateMapping} je prazan u prvom prolazu. */
    private List<ColumnEntry> remapColumns(List<ColumnEntry> source, Map<Long, Long> codebookMapping,
                                           Map<Long, Long> templateMapping) {
        List<ColumnEntry> remapped = new ArrayList<>();
        for (ColumnEntry column : source) {
            Long codebookId = column.codebookId() == null
                    ? null
                    : codebookMapping.getOrDefault(column.codebookId(), column.codebookId());
            Long target = column.options().targetTemplateId();
            Long newTarget = target == null ? null : templateMapping.get(target);
            remapped.add(new ColumnEntry(column.key(), column.type(), codebookId, column.label(),
                    column.required(), column.unique(), column.defaultValue(), column.readOnly(),
                    column.width(), column.options().withTargetTemplateId(newTarget)));
        }
        return remapped;
    }

    private boolean hasReference(Template template) {
        return template.getDefinitions().stream()
                .anyMatch(column -> column.options().targetTemplateId() != null);
    }

    private int countItems(Long codebookId) {
        return (int) codebookItemRepository.countByCodebookId(codebookId);
    }

    private void requireCompany(Long id) {
        if (!companyRepository.existsById(id)) {
            throw new ResourceNotFoundException("Company", id);
        }
    }

    // ===================== nacrt =====================

    private record PlannedTemplate(Template source, String targetName, boolean renamed) {
    }

    /** @param targetId sifrarnik u ciljnoj firmi ako vec postoji; null = treba ga stvoriti */
    private record PlannedCodebook(Long sourceId, String name, CodebookScope scope, Long targetId,
                                   int itemCount) {
    }

    private record Plan(List<PlannedTemplate> templates, List<PlannedCodebook> codebooks,
                        List<TransferPlanResponse.BrokenReference> brokenReferences) {

        TransferPlanResponse response() {
            return new TransferPlanResponse(
                    templates.stream()
                            .map(t -> new TransferPlanResponse.TemplatePlan(t.source.getId(),
                                    t.source.getName(), t.targetName, t.renamed,
                                    t.source.getDefinitions().size()))
                            .toList(),
                    codebooks.stream()
                            .map(c -> new TransferPlanResponse.CodebookPlan(c.name, c.scope.name(),
                                    c.targetId != null, c.itemCount))
                            .toList(),
                    brokenReferences);
        }
    }
}
