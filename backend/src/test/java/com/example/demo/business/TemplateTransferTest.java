package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.TransferPlanResponse;
import com.example.demo.dto.TransferTemplatesRequest;
import com.example.demo.exception.InvalidCompanyException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Template;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.LogService;
import com.example.demo.service.TemplateTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - prijenos obrazaca iz jedne firme u drugu.
 *
 * Ovo je jedina radnja koja namjerno prelazi granicu izmedu firmi, pa se najvise testira ono
 * sto se pri prelasku moze tiho izgubiti: id sifrarnika i id ciljanog obrasca vrijede samo
 * unutar svoje firme. Ako se ne preslikaju, preneseni obrazac IZGLEDA ispravno - stupci su
 * ondje, nazivi su ondje - a pokazuje na tudi sifrarnik ili u prazno.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class TemplateTransferTest {

    private static final long GOOGLE = 1L;
    private static final long CARNET = 2L;

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private CodebookRepository codebookRepository;
    @Mock
    private CodebookItemRepository codebookItemRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private AuthContext authContext;
    @Mock
    private LogService logService;

    private TemplateTransferService service;

    @BeforeEach
    void setUp() {
        service = new TemplateTransferService(templateRepository, codebookRepository,
                codebookItemRepository, companyRepository, authContext, logService);
        lenient().when(companyRepository.existsById(anyLong())).thenReturn(true);
    }

    // ===================== pomocno =====================

    private Template template(long id, long companyId, String name, ColumnEntry... columns) {
        Template t = new Template(companyId, name);
        t.setId(id);
        t.setDefinitions(List.of(columns));
        return t;
    }

    private ColumnEntry text(String key) {
        return new ColumnEntry(key, "string", null);
    }

    private ColumnEntry fromCodebook(String key, long codebookId) {
        return new ColumnEntry(key, "codebook", codebookId);
    }

    private ColumnEntry reference(String key, long targetTemplateId) {
        return new ColumnEntry(key, "reference", null, key, false, false, null, false, null,
                ColumnOptions.EMPTY.withTargetTemplateId(targetTemplateId));
    }

    private TransferTemplatesRequest request(Long... templateIds) {
        return new TransferTemplatesRequest(GOOGLE, CARNET, List.of(templateIds));
    }

    /** Spremanje vraca obrazac s dodijeljenim id-em, kao prava baza. */
    private void savesWithGeneratedIds(long firstId) {
        AtomicLong next = new AtomicLong(firstId);
        when(templateRepository.save(any(Template.class))).thenAnswer(call -> {
            Template saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(next.getAndIncrement());
            }
            return saved;
        });
    }

    // ===================== ovlasti i doseg =====================

    @Test
    @DisplayName("prijenos smije pokrenuti samo globalni administrator")
    void requiresAdmin() {
        when(authContext.requireAdmin()).thenThrow(new com.example.demo.exception.ForbiddenException("nije admin"));

        assertThatThrownBy(() -> service.preview(request(10L)))
                .isInstanceOf(com.example.demo.exception.ForbiddenException.class);

        verify(templateRepository, never()).save(any());
    }

    @Test
    @DisplayName("izvorna i ciljna firma ne smiju biti iste")
    void rejectsSameCompany() {
        assertThatThrownBy(() -> service.preview(
                new TransferTemplatesRequest(GOOGLE, GOOGLE, List.of(10L))))
                .isInstanceOf(InvalidCompanyException.class);
    }

    /**
     * Obrazac se trazi po id-u, pa bi pogoden id tude firme inace prosao - i firma bi u
     * jednom potezu dobila shemu koju nije smjela ni vidjeti.
     */
    @Test
    @DisplayName("obrazac koji ne pripada izvornoj firmi se odbija")
    void rejectsTemplateOfAnotherCompany() {
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, 99L, "Tudi obrazac")));

        assertThatThrownBy(() -> service.preview(request(10L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ===================== nazivi =====================

    @Test
    @DisplayName("obrazac čiji je naziv u ciljnoj firmi zauzet nastaje kao kopija")
    void renamesOnCollision() {
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, GOOGLE, "Rizici", text("naziv"))));
        when(templateRepository.findByCompanyId(CARNET))
                .thenReturn(List.of(template(50L, CARNET, "Rizici")));

        TransferPlanResponse plan = service.preview(request(10L));

        assertThat(plan.templates()).singleElement().satisfies(t -> {
            assertThat(t.targetName()).isEqualTo("Rizici (kopija)");
            assertThat(t.renamed()).isTrue();
        });
    }

    /** Dva obrasca istog naziva u jednom potezu ne smiju dobiti isti naziv. */
    @Test
    @DisplayName("kopija se broji i unutar istog prijenosa")
    void countsCollisionsWithinOneTransfer() {
        when(templateRepository.findAllById(List.of(10L, 11L))).thenReturn(List.of(
                template(10L, GOOGLE, "Rizici"),
                template(11L, GOOGLE, "Rizici")));
        when(templateRepository.findByCompanyId(CARNET))
                .thenReturn(List.of(template(50L, CARNET, "Rizici")));

        TransferPlanResponse plan = service.preview(request(10L, 11L));

        assertThat(plan.templates()).extracting(TransferPlanResponse.TemplatePlan::targetName)
                .containsExactly("Rizici (kopija)", "Rizici (kopija 2)");
    }

    @Test
    @DisplayName("slobodan naziv ostaje kakav je bio")
    void keepsFreeName() {
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, GOOGLE, "Procesi")));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());

        TransferPlanResponse plan = service.preview(request(10L));

        assertThat(plan.templates()).singleElement().satisfies(t -> {
            assertThat(t.targetName()).isEqualTo("Procesi");
            assertThat(t.renamed()).isFalse();
        });
    }

    // ===================== sifrarnici =====================

    @Test
    @DisplayName("šifrarnik firme koji ciljna nema se stvara, sa stavkama")
    void createsMissingTenantCodebook() {
        Codebook source = new Codebook("Gradovi", CodebookScope.TENANT, GOOGLE);
        source.setId(7L);
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, GOOGLE, "Vlasnici", fromCodebook("grad", 7L))));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());
        when(codebookRepository.findById(7L)).thenReturn(Optional.of(source));
        when(codebookRepository.findFirstByScopeAndCompanyIdAndNameIgnoreCase(
                CodebookScope.TENANT, CARNET, "Gradovi")).thenReturn(Optional.empty());
        when(codebookItemRepository.countByCodebookId(7L)).thenReturn(1L);

        TransferPlanResponse plan = service.preview(request(10L));

        assertThat(plan.codebooks()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("Gradovi");
            assertThat(c.reused()).isFalse();
            assertThat(c.itemCount()).isEqualTo(1);
        });
    }

    /**
     * Bez ovoga bi svaki prijenos stvarao novi "Gradovi", pa bi ciljna firma nakon tri
     * prijenosa imala tri popisa gradova i nijedan ne bi bio glavni.
     */
    @Test
    @DisplayName("šifrarnik istog naziva u ciljnoj firmi se ponovno koristi")
    void reusesExistingCodebookByName() {
        Codebook source = new Codebook("Gradovi", CodebookScope.TENANT, GOOGLE);
        source.setId(7L);
        Codebook existing = new Codebook("Gradovi", CodebookScope.TENANT, CARNET);
        existing.setId(70L);
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, GOOGLE, "Vlasnici", fromCodebook("grad", 7L))));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());
        when(codebookRepository.findById(7L)).thenReturn(Optional.of(source));
        when(codebookRepository.findFirstByScopeAndCompanyIdAndNameIgnoreCase(
                CodebookScope.TENANT, CARNET, "Gradovi")).thenReturn(Optional.of(existing));
        when(codebookItemRepository.countByCodebookId(70L)).thenReturn(0L);
        savesWithGeneratedIds(100L);

        TransferPlanResponse plan = service.transfer(request(10L));

        assertThat(plan.codebooks()).singleElement()
                .satisfies(c -> assertThat(c.reused()).isTrue());
        // nov sifrarnik se NE stvara
        verify(codebookRepository, never()).save(any());

        ArgumentCaptor<Template> saved = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository).save(saved.capture());
        assertThat(saved.getValue().getDefinitions()).singleElement()
                .satisfies(c -> assertThat(c.codebookId()).isEqualTo(70L));
    }

    /** Globalni sifrarnik dijele sve firme, pa mu se id ne dira. */
    @Test
    @DisplayName("globalni šifrarnik se ne kopira")
    void keepsGlobalCodebook() {
        Codebook global = new Codebook("Da/Ne", CodebookScope.GLOBAL, null);
        global.setId(3L);
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, GOOGLE, "Procesi", fromCodebook("aktivno", 3L))));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());
        when(codebookRepository.findById(3L)).thenReturn(Optional.of(global));
        when(codebookItemRepository.countByCodebookId(3L)).thenReturn(0L);
        savesWithGeneratedIds(100L);

        service.transfer(request(10L));

        verify(codebookRepository, never()).save(any());
        ArgumentCaptor<Template> saved = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository).save(saved.capture());
        assertThat(saved.getValue().getDefinitions()).singleElement()
                .satisfies(c -> assertThat(c.codebookId()).isEqualTo(3L));
    }

    // ===================== veze =====================

    /**
     * Veza pokazuje na id obrasca, a kopija u ciljnoj firmi ima DRUGI id. Bez preslikavanja
     * bi preneseni stupac pokazivao na obrazac izvorne firme - dakle preko granice firme.
     */
    @Test
    @DisplayName("veza se preusmjerava na kopiju ciljanog obrasca")
    void remapsReferenceToTheCopy() {
        when(templateRepository.findAllById(List.of(10L, 11L))).thenReturn(List.of(
                template(10L, GOOGLE, "Vlasnici", text("ime")),
                template(11L, GOOGLE, "Zivotinje", reference("vlasnik", 10L))));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());
        savesWithGeneratedIds(100L);
        when(templateRepository.findById(101L)).thenAnswer(call ->
                Optional.of(template(101L, CARNET, "Zivotinje", reference("vlasnik", 10L))));

        TransferPlanResponse plan = service.transfer(request(10L, 11L));

        assertThat(plan.brokenReferences()).isEmpty();
        ArgumentCaptor<Template> saved = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository, org.mockito.Mockito.atLeast(3)).save(saved.capture());
        Template last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getDefinitions()).singleElement().satisfies(c ->
                assertThat(c.options().targetTemplateId()).isEqualTo(100L));
    }

    /**
     * Odabir smije izostaviti obrazac na koji netko pokazuje. Veza se tada prekida - ali se to
     * mora VIDJETI, jer se po samom stupcu poslije ne raspoznaje da je nesto izgubljeno.
     */
    @Test
    @DisplayName("veza na obrazac izvan odabira se prekida i javlja")
    void reportsBrokenReference() {
        when(templateRepository.findAllById(List.of(11L))).thenReturn(List.of(
                template(11L, GOOGLE, "Zivotinje", reference("vlasnik", 10L))));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());
        when(templateRepository.findById(10L))
                .thenReturn(Optional.of(template(10L, GOOGLE, "Vlasnici")));

        TransferPlanResponse plan = service.preview(request(11L));

        assertThat(plan.brokenReferences()).singleElement().satisfies(b -> {
            assertThat(b.templateName()).isEqualTo("Zivotinje");
            assertThat(b.columnKey()).isEqualTo("vlasnik");
            assertThat(b.targetName()).isEqualTo("Vlasnici");
        });
    }

    // ===================== najava =====================

    /**
     * Najava mora biti bezopasna - inace nitko nece htjeti kliknuti "pokaži što će se
     * dogoditi", a upravo je to korak zbog kojeg posljedice postanu vidljive.
     */
    @Test
    @DisplayName("najava ne mijenja ništa")
    void previewWritesNothing() {
        Codebook source = new Codebook("Gradovi", CodebookScope.TENANT, GOOGLE);
        source.setId(7L);
        when(templateRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(template(10L, GOOGLE, "Vlasnici", fromCodebook("grad", 7L))));
        when(templateRepository.findByCompanyId(CARNET)).thenReturn(List.of());
        when(codebookRepository.findById(7L)).thenReturn(Optional.of(source));
        when(codebookRepository.findFirstByScopeAndCompanyIdAndNameIgnoreCase(
                CodebookScope.TENANT, CARNET, "Gradovi")).thenReturn(Optional.empty());
        when(codebookItemRepository.countByCodebookId(7L)).thenReturn(0L);

        service.preview(request(10L));

        verify(templateRepository, never()).save(any());
        verify(codebookRepository, never()).save(any());
        verify(codebookItemRepository, never()).save(any());
        verify(logService, never()).record(any(), any());
    }
}
