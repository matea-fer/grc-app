package com.example.demo.core;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CodebookResponse;
import com.example.demo.dto.CreateCodebookRequest;
import com.example.demo.dto.UpdateCodebookRequest;
import com.example.demo.exception.CodebookInUseException;
import com.example.demo.exception.DuplicateCodebookNameException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.Role;
import com.example.demo.model.Template;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.service.CodebookService;
import com.example.demo.service.CodebookUsage;
import com.example.demo.service.LogService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CORE test - CRUD sifrarnika. Doseg i ovlasti su u CodebookAccessTest; ovdje se
 * provjerava sam zivotni ciklus zaglavlja i to da broj stavki dolazi jednim upitom.
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodebookServiceTest {

    private static final Long COMPANY = 7L;

    @Mock
    private CodebookRepository repository;

    @Mock
    private CodebookItemRepository itemRepository;

    @Mock
    private TenantContext tenantContext;

    @Mock
    private AuthContext authContext;

    @Mock
    private LogService logService;

    @Mock
    private CodebookUsage codebookUsage;

    @InjectMocks
    private CodebookService service;

    /** Redak rezultata upita koji broji stavke; interface projekcija se u testu popuni rucno. */
    private record Count(Long codebookId, long itemCount) implements CodebookItemRepository.CodebookItemCount {
        @Override
        public Long getCodebookId() {
            return codebookId;
        }

        @Override
        public long getItemCount() {
            return itemCount;
        }
    }

    private static Codebook codebook(Long id, String name, CodebookScope scope, Long companyId) {
        Codebook codebook = new Codebook(name, scope, companyId);
        codebook.setId(id);
        return codebook;
    }

    @BeforeEach
    void loggedInAsAdminWithCompany() {
        when(authContext.require()).thenReturn(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
        when(tenantContext.get()).thenReturn(COMPANY);
        when(repository.save(any())).thenAnswer(invocation -> {
            Codebook saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return saved;
        });
    }

    @Test
    @DisplayName("popis nosi broj stavki, dohvacen jednim upitom za sve sifrarnike")
    void listCarriesItemCounts() {
        Codebook prvi = codebook(1L, "Statusi", CodebookScope.TENANT, COMPANY);
        Codebook drugi = codebook(2L, "Države", CodebookScope.GLOBAL, null);
        when(repository.findVisible(COMPANY, "%")).thenReturn(List.of(prvi, drugi));
        when(itemRepository.countByCodebookIds(anyCollection()))
                .thenReturn(List.of(new Count(1L, 3L)));

        List<CodebookResponse> result = service.list(null, null);

        assertThat(result).extracting(CodebookResponse::id, CodebookResponse::itemCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 3L),
                        org.assertj.core.groups.Tuple.tuple(2L, 0L));
        // jedan upit za sve, ne po sifrarniku
        verify(itemRepository).countByCodebookIds(anyCollection());
    }

    /**
     * Prazna pretraga mora dati uzorak "%", a NIKAD null.
     *
     * Null parametar je u bazu stizao bez tipa i cijeli upit bi pukao ("function
     * lower(bytea) does not exist"), a prazna pretraga je prvo stanje ekrana - dakle
     * pravilo, ne iznimka.
     */
    @Test
    @DisplayName("prazna pretraga postaje uzorak %, nikad null")
    void blankSearchBecomesMatchAllPattern() {
        when(repository.findVisible(COMPANY, "%")).thenReturn(List.of());

        service.list(null, "   ");

        verify(repository).findVisible(COMPANY, "%");
    }

    @Test
    @DisplayName("pretraga se pretvara u LIKE uzorak u malim slovima")
    void searchBecomesLowercasePattern() {
        when(repository.findVisible(COMPANY, "%status%")).thenReturn(List.of());

        service.list(null, "  STATUS  ");

        verify(repository).findVisible(COMPANY, "%status%");
    }

    @Test
    @DisplayName("dzokeri iz korisnickog unosa se ekraniraju - inace bi \"50%\" naslo sve sto pocinje s 50")
    void wildcardsInSearchAreEscaped() {
        when(repository.findVisible(COMPANY, "%50!%%")).thenReturn(List.of());

        service.list(null, "50%");

        verify(repository).findVisible(COMPANY, "%50!%%");
    }

    @Test
    @DisplayName("suzenje na doseg ide na drugi upit")
    void scopeFilterUsesDedicatedQuery() {
        when(repository.findVisibleByScope(COMPANY, "%sta%", CodebookScope.TENANT)).thenReturn(List.of());

        service.list(CodebookScope.TENANT, "sta");

        verify(repository).findVisibleByScope(COMPANY, "%sta%", CodebookScope.TENANT);
        verify(repository, never()).findVisible(any(), any());
    }

    @Test
    @DisplayName("novi sifrarnik firme dobiva firmu iz konteksta, ne iz tijela")
    void createTakesCompanyFromContext() {
        CodebookResponse created = service.create(new CreateCodebookRequest("  Statusi  ", CodebookScope.TENANT));

        ArgumentCaptor<Codebook> captor = ArgumentCaptor.forClass(Codebook.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(COMPANY);
        // naziv se sprema bez rubnih razmaka
        assertThat(captor.getValue().getName()).isEqualTo("Statusi");
        assertThat(created.scope()).isEqualTo(CodebookScope.TENANT);
        assertThat(created.itemCount()).isZero();
    }

    @Test
    @DisplayName("globalni sifrarnik se sprema bez firme")
    void createGlobalHasNoCompany() {
        service.create(new CreateCodebookRequest("Države", CodebookScope.GLOBAL));

        ArgumentCaptor<Codebook> captor = ArgumentCaptor.forClass(Codebook.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isNull();
    }

    @Test
    @DisplayName("zauzet naziv u istoj firmi se odbija")
    void duplicateNameIsRejected() {
        when(repository.existsByScopeAndCompanyIdAndNameIgnoreCase(CodebookScope.TENANT, COMPANY, "Statusi"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCodebookRequest("Statusi", CodebookScope.TENANT)))
                .isInstanceOf(DuplicateCodebookNameException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("preimenovanje u isti naziv ne pada na provjeri duplikata")
    void renameToSameNameIsAllowed() {
        Codebook existing = codebook(1L, "Statusi", CodebookScope.TENANT, COMPANY);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByCodebookIdOrderBySortOrderAsc(1L)).thenReturn(List.of());

        service.rename(1L, new UpdateCodebookRequest("statusi"));

        verify(repository, never()).existsByScopeAndCompanyIdAndNameIgnoreCase(any(), any(), any());
    }

    @Test
    @DisplayName("brisanje odnosi i sve stavke")
    void deleteRemovesItemsToo() {
        Codebook existing = codebook(1L, "Statusi", CodebookScope.TENANT, COMPANY);
        List<CodebookItem> items = List.of(new CodebookItem(1L, "OPEN", "Otvoren", true, 0));
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(itemRepository.findByCodebookIdOrderBySortOrderAsc(1L)).thenReturn(items);

        service.delete(1L);

        verify(itemRepository).deleteAll(items);
        verify(repository).delete(existing);
    }

    /**
     * Dovoljan je SAM stupac koji na sifrarnik pokazuje - ne treba nijedan upisan zapis.
     * Bez sifrarnika bi stupac ostao tvrditi da mu vrijednosti dolaze iz necega cega nema,
     * pa bi svaki sljedeci unos u njega pao.
     */
    @Test
    @DisplayName("šifrarnik na koji pokazuje stupac se ne briše")
    void deleteIsRejectedWhenAColumnPointsAtIt() {
        Codebook existing = codebook(1L, "Statusi", CodebookScope.TENANT, COMPANY);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        Template template = new Template(COMPANY, "Obrazac");
        template.setId(5L);
        when(codebookUsage.columnsUsing(existing)).thenReturn(List.of(
                new CodebookUsage.ColumnUse(template, new ColumnEntry("status", "codebook", 1L))));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(CodebookInUseException.class)
                .hasMessageContaining("Obrazac")
                .hasMessageContaining("status");

        verify(repository, never()).delete(any());
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("nepostojeci sifrarnik je 404")
    void unknownCodebookIsNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireAccessible(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
