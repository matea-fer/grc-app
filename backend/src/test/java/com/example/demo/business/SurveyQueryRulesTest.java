package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.SurveyResponse;
import com.example.demo.exception.SchemaValidationException;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CodebookItemService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.ColumnSequenceService;
import com.example.demo.service.FormulaEvaluator;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.SurveyFilter;
import com.example.demo.service.SurveyResultService;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - prijevod korisnickog upita u pitanje za bazu.
 *
 * Dijeli posao sa {@code SurveySearchSqlTest}: ondje se provjerava da SQL radi ono sto tvrdi,
 * ovdje da mu se predaje ono sto treba. Tezina je u tome sto korisnik i baza ne govore istim
 * jezikom - korisnik trazi "Zagreb", a u zapisu stoji "ZG"; upisuje "100%", a to je za LIKE
 * uzorak; sortira po stupcu koji mozda ne postoji ili nema po cemu biti poredan.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class SurveyQueryRulesTest {

    private static final long TEMPLATE_ID = 10L;
    private static final Long CODEBOOK_ID = 7L;

    @Mock
    private SurveyResultRepository repository;

    @Mock
    private SurveyQueryRepository queryRepository;

    @Mock
    private ColumnDefinitionService columnDefinitionService;

    @Mock
    private SchemaValidator validator;

    @Mock
    private TemplateService templateService;

    @Mock
    private ColumnSequenceService columnSequenceService;

    @Mock
    private CodebookItemService codebookItemService;

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private ReferenceLookup referenceLookup;

    @Mock
    private RecordChangeService recordChangeService;

    private SurveyResultService service;

    @BeforeEach
    void setUp() {
        service = new SurveyResultService(repository, queryRepository, columnDefinitionService, validator,
                templateService, columnSequenceService, codebookItemService, new FormulaEvaluator(),
                attachmentCleanup, recordChangeService, logService, new AuthContext(), referenceLookup);

        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        lenient().when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(template);
        lenient().when(columnDefinitionService.getForTemplate(TEMPLATE_ID)).thenReturn(List.of(
                text("grad"), number("cijena"), formula("ukupno"),
                codebook("drzava", false), codebook("oznake", true),
                valueless("dokument", "file"), valueless("akcija", "button")));
    }

    private static ColumnDefinitionResponse text(String key) {
        return new ColumnDefinitionResponse(key, "string", null);
    }

    private static ColumnDefinitionResponse number(String key) {
        return new ColumnDefinitionResponse(key, "number", null);
    }

    private static ColumnDefinitionResponse formula(String key) {
        return new ColumnDefinitionResponse(key, "formula", null, null, false, false, null, true, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                        "[cijena] * 2", null, null));
    }

    private static ColumnDefinitionResponse codebook(String key, boolean multiple) {
        return new ColumnDefinitionResponse(key, "codebook", CODEBOOK_ID, null, false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null,
                        multiple ? "checkbox" : "dropdown", multiple, false, null, null, null));
    }

    private static ColumnDefinitionResponse valueless(String key, String type) {
        return new ColumnDefinitionResponse(key, type, null, null, false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                        null, "Gumb", "button".equals(type) ? "history" : null));
    }

    /** Filtri koje je servis na kraju predao upitu. */
    @SuppressWarnings("unchecked")
    private List<SurveyFilter> capturedFilters() {
        ArgumentCaptor<List<SurveyFilter>> captor = ArgumentCaptor.forClass(List.class);
        verify(queryRepository).find(eq(TEMPLATE_ID), captor.capture(), isNull(), any(), anyBoolean(), anyBoolean(),
                anyInt(), anyInt());
        return captor.getValue();
    }

    private PageResponse<SurveyResponse> search(String... filters) {
        return service.getAll(TEMPLATE_ID, 0, null, null, List.of(filters));
    }

    // ===================== PRETRAGA PO SIFRARNIKU =====================

    /**
     * U zapisu stoji SIFRA ("ZG"), a korisnik trazi ono sto vidi ("Zagreb"). Bez prijevoda bi
     * pretraga po sifrarnickom stupcu nalazila samo one koji znaju sifre napamet.
     */
    @Test
    @DisplayName("naziv iz sifrarnika se prevodi u sifre prije upita")
    void codebookNameIsTranslatedToCodes() {
        when(codebookItemService.codesMatching(CODEBOOK_ID, "zagreb")).thenReturn(List.of("ZG"));

        search("drzava:zagreb");

        assertThat(capturedFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.kind()).isEqualTo(SurveyFilter.Kind.CODES);
            assertThat(filter.codes()).containsExactly("ZG");
        });
    }

    /**
     * Naziv koji nijedna stavka ne nosi nije greska nego upit bez pogodaka - i to se zna prije
     * baze. Da se svejedno upita, uvjet bi ostao bez ijednog clana i vratio SVE zapise, dakle
     * tocno suprotno od trazenog.
     */
    @Test
    @DisplayName("naziv koji ne postoji u sifrarniku daje praznu stranicu, bez odlaska u bazu")
    void unknownCodebookNameReturnsEmptyPageWithoutQuery() {
        when(codebookItemService.codesMatching(CODEBOOK_ID, "atlantida")).thenReturn(List.of());

        PageResponse<SurveyResponse> page = search("drzava:atlantida");

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        verify(queryRepository, never()).find(any(), anyList(), any(), any(), anyBoolean(), anyBoolean(), anyInt(), anyInt());
        verify(queryRepository, never()).count(any(), anyList(), any());
    }

    // ===================== TEKST I BROJ =====================

    /**
     * {@code %} i {@code _} su za LIKE uzorak; korisnik ih upisuje kao obican tekst. Bez bijega
     * bi "100%" naslo sve sto pocinje sa "100" - pretraga bi tiho radila nesto drugo nego sto
     * pise.
     */
    @Test
    @DisplayName("znakovi uzorka u upisanom tekstu se pobjegnu")
    void likeWildcardsAreEscaped() {
        search("grad:100%_x");

        assertThat(capturedFilters()).singleElement()
                .satisfies(filter -> assertThat(filter.text()).isEqualTo("100\\%\\_x"));
    }

    @Test
    @DisplayName("brojcani stupac se trazi kao broj, uz zarez kao decimalni znak")
    void numericColumnIsSearchedAsNumber() {
        search("cijena:12,5");

        assertThat(capturedFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.kind()).isEqualTo(SurveyFilter.Kind.NUMBER);
            assertThat(filter.number()).isEqualByComparingTo(new BigDecimal("12.5"));
        });
    }

    @Test
    @DisplayName("tekst u brojcanom stupcu daje praznu stranicu, a ne gresku")
    void textInNumericColumnFindsNothing() {
        assertThat(search("cijena:abc").content()).isEmpty();

        verify(queryRepository, never()).count(any(), anyList(), any());
    }

    @Test
    @DisplayName("prazna vrijednost filtra ne suzava nista - ponasa se kao da filtra nema")
    void blankFilterValueIsIgnored() {
        assertThat(search("grad:   ").content()).isEmpty();
    }

    // ===================== VISE FILTARA =====================

    @Test
    @DisplayName("vise filtara putuje zajedno, redom kojim su zadani")
    void filtersTravelTogether() {
        when(codebookItemService.codesMatching(CODEBOOK_ID, "hr")).thenReturn(List.of("HR"));

        search("grad:zagreb", "cijena:100", "drzava:hr");

        assertThat(capturedFilters()).extracting(SurveyFilter::columnKey)
                .containsExactly("grad", "cijena", "drzava");
    }

    /** Dvotocka u vrijednosti je obicna dvotocka - dijeli se samo na prvoj. */
    @Test
    @DisplayName("vrijednost smije sadrzavati dvotocku")
    void valueMayContainColon() {
        search("grad:12:30");

        assertThat(capturedFilters()).singleElement()
                .satisfies(filter -> assertThat(filter.text()).isEqualTo("12:30"));
    }

    @Test
    @DisplayName("filtar bez dvotocke se odbija - inace bi tiho trazio nista")
    void malformedFilterIsRejected() {
        assertThatThrownBy(() -> search("grad"))
                .isInstanceOf(SchemaValidationException.class);
    }

    /**
     * Naziv stupca dolazi iz zahtjeva i zavrsava u upitu. Provjera protiv sheme je ono sto
     * cuva da se ondje ne nade sto god posiljatelj zamisli.
     */
    @Test
    @DisplayName("filtar po stupcu kojeg nema u shemi se odbija")
    void filterOnUnknownColumnIsRejected() {
        assertThatThrownBy(() -> search("nepostojeci:x"))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("nepostojeci");
    }

    // ===================== SORTIRANJE =====================

    @Test
    @DisplayName("tekstualni stupac se sortira kao tekst, brojcani i formula kao broj")
    void sortModeFollowsColumnType() {
        assertThat(sortNumericFor("grad")).isFalse();
        assertThat(sortNumericFor("cijena")).isTrue();
        // formula sprema izracunatu vrijednost, dakle broj - inace bi se 100 slozilo ispred 9
        assertThat(sortNumericFor("ukupno")).isTrue();
    }

    private boolean sortNumericFor(String columnKey) {
        SurveyQueryRepository fresh = org.mockito.Mockito.mock(SurveyQueryRepository.class);
        SurveyResultService local = new SurveyResultService(repository, fresh, columnDefinitionService, validator,
                templateService, columnSequenceService, codebookItemService, new FormulaEvaluator(),
                attachmentCleanup, recordChangeService, logService, new AuthContext(), referenceLookup);
        local.getAll(TEMPLATE_ID, 0, columnKey, "asc", null);

        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(fresh).find(eq(TEMPLATE_ID), anyList(), isNull(), eq(columnKey), captor.capture(), anyBoolean(),
                anyInt(), anyInt());
        return captor.getValue();
    }

    @Test
    @DisplayName("smjer sortiranja se cita iz zahtjeva, a sve osim \"desc\" je uzlazno")
    void sortDirectionIsReadFromRequest() {
        service.getAll(TEMPLATE_ID, 0, "grad", "DESC", null);

        verify(queryRepository).find(eq(TEMPLATE_ID), anyList(), isNull(), eq("grad"), anyBoolean(), eq(true),
                anyInt(), anyInt());
    }

    @Test
    @DisplayName("bez sortiranja se zadrzava poredak unosa")
    void withoutSortKeepsInsertionOrder() {
        service.getAll(TEMPLATE_ID, 0, null, null, null);

        verify(queryRepository).find(eq(TEMPLATE_ID), anyList(), isNull(), isNull(), anyBoolean(), anyBoolean(),
                anyInt(), anyInt());
    }

    /**
     * Gumb i datoteka u zapisu ne drze nista, a visevrijednosni sifrarnik drzi popis sifara -
     * poredak popisa nije poredak nicega sto korisnik vidi. Ponuditi ih znacilo bi obecati
     * poredak koji ne postoji.
     */
    @Test
    @DisplayName("stupci bez vrijednosti i popis sifara se ne daju sortirati")
    void unsortableColumnsAreRejected() {
        assertThatThrownBy(() -> service.getAll(TEMPLATE_ID, 0, "dokument", "asc", null))
                .isInstanceOf(SchemaValidationException.class);
        assertThatThrownBy(() -> service.getAll(TEMPLATE_ID, 0, "akcija", "asc", null))
                .isInstanceOf(SchemaValidationException.class);
        assertThatThrownBy(() -> service.getAll(TEMPLATE_ID, 0, "oznake", "asc", null))
                .isInstanceOf(SchemaValidationException.class);
    }

    @Test
    @DisplayName("sortiranje po stupcu kojeg nema u shemi se odbija")
    void sortOnUnknownColumnIsRejected() {
        assertThatThrownBy(() -> service.getAll(TEMPLATE_ID, 0, "nepostojeci", "asc", null))
                .isInstanceOf(SchemaValidationException.class);
    }

    // ===================== STRANICA =====================

    @Test
    @DisplayName("negativna stranica se cita kao prva, umjesto da upit padne na negativnom pomaku")
    void negativePageFallsBackToFirst() {
        PageResponse<SurveyResponse> page = service.getAll(TEMPLATE_ID, -5, null, null, null);

        assertThat(page.page()).isZero();
        verify(queryRepository).find(eq(TEMPLATE_ID), anyList(), isNull(), isNull(), anyBoolean(), anyBoolean(),
                eq(0), eq(SurveyResultService.PAGE_SIZE));
    }

    @Test
    @DisplayName("broj stranica se racuna iz ukupnog broja, ne iz onoga sto je na stranici")
    void totalPagesComeFromTotalCount() {
        when(queryRepository.count(eq(TEMPLATE_ID), eq(List.of()), isNull())).thenReturn(101L);
        when(queryRepository.find(eq(TEMPLATE_ID), anyList(), isNull(), any(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of(new SurveyResult()));

        PageResponse<SurveyResponse> page = service.getAll(TEMPLATE_ID, 0, null, null, null);

        assertThat(page.totalElements()).isEqualTo(101);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(SurveyResultService.PAGE_SIZE);
    }
}
