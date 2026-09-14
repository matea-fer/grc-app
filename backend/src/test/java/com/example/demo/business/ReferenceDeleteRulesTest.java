package com.example.demo.business;

import com.example.demo.dto.RelatedGroupResponse;
import com.example.demo.exception.RecordReferencedException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.CodebookItemService;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - sto se dogada kad se brise zapis na koji netko pokazuje.
 *
 * Ovo je odgovor na pitanje "kako se onda išta briše": zabrana ne znaci "nikad" nego "ne dok
 * veza postoji". Da bi bila upotrebljiva, poruka mora reci TKO smeta - inace korisnik vidi da
 * ne moze, ali ne zna sto bi popravio.
 *
 * Ponasanje je postavka STUPCA, ne globalno pravilo: veza rizika na proces i veza potprocesa
 * na proces nisu ista vrsta veze.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferenceDeleteRulesTest {

    private static final long COMPANY_ID = 3L;
    private static final long TARGET_ID = 20L;
    private static final long SOURCE_ID = 30L;
    private static final long RECORD_ID = 5L;

    @Mock
    private TemplateRepository templateRepository;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private SurveyQueryRepository queryRepository;

    @Mock
    private CodebookItemService codebookItemService;

    private final TenantContext tenantContext = new TenantContext();

    private ReferenceLookup lookup;

    @BeforeEach
    void setUp() {
        tenantContext.set(COMPANY_ID);
        lookup = new ReferenceLookup(templateRepository, surveyRepository, queryRepository,
                codebookItemService, tenantContext);
    }

    private static ColumnOptions reference(String onDelete, boolean multiple) {
        return new ColumnOptions(false, null, null, null, null, null, null, null, multiple, false,
                null, null, null, TARGET_ID, "naziv", onDelete);
    }

    /** Obrazac "Rizici" sa stupcem "proces" koji pokazuje na obrazac koji se brise. */
    private Template sourceTemplate(String onDelete, boolean multiple) {
        Template template = new Template(COMPANY_ID, "Rizici");
        template.setId(SOURCE_ID);
        template.setDefinitions(new ArrayList<>(List.of(
                new ColumnEntry("proces", "reference", null, "Proces", false, false, null, false,
                        null, reference(onDelete, multiple)))));
        return template;
    }

    private SurveyResult linkedRecord(long id, Object value, boolean locked) {
        SurveyResult record = new SurveyResult();
        record.setId(id);
        record.setCompanyId(COMPANY_ID);
        record.setTemplateId(SOURCE_ID);
        record.setData(new HashMap<>(Map.of("proces", value)));
        if (locked) {
            record.setLockedAt(Instant.now());
            record.setLockedBy("mhorvat");
        }
        return record;
    }

    private void companyHas(Template... templates) {
        when(templateRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(templates));
    }

    private void linked(long total, SurveyResult... records) {
        when(queryRepository.countLinkedTo(SOURCE_ID, "proces", RECORD_ID)).thenReturn(total);
        when(queryRepository.findLinkedTo(eq(SOURCE_ID), eq("proces"), eq(RECORD_ID), anyInt()))
                .thenReturn(List.of(records));
    }

    @Test
    @DisplayName("zapis bez ijedne veze se brise bez prigovora")
    void allowsDeleteWithoutLinks() {
        companyHas(sourceTemplate("restrict", false));
        when(queryRepository.countLinkedTo(SOURCE_ID, "proces", RECORD_ID)).thenReturn(0L);

        assertThat(lookup.requireDeletable(TARGET_ID, RECORD_ID)).isEmpty();
    }

    /**
     * Poruka mora imenovati obrazac, stupac i same zapise. Bez toga je zabrana slijepa ulica -
     * korisnik vidi da ne moze, a ne zna gdje bi trazio.
     */
    @Test
    @DisplayName("zabrana imenuje obrazac, stupac i zapise koji smetaju")
    void blocksAndNamesBlockers() {
        companyHas(sourceTemplate("restrict", false));
        linked(2, linkedRecord(11L, 5, false), linkedRecord(12L, 5, false));

        assertThatThrownBy(() -> lookup.requireDeletable(TARGET_ID, RECORD_ID))
                .isInstanceOf(RecordReferencedException.class)
                .hasMessageContaining("Rizici")
                .hasMessageContaining("Proces")
                .hasMessageContaining("#11")
                .hasMessageContaining("#12");
    }

    @Test
    @DisplayName("poruka kaze koliko ih je preko nabrojanih")
    void mentionsRemainingCount() {
        companyHas(sourceTemplate("restrict", false));
        linked(45, linkedRecord(11L, 5, false));

        assertThatThrownBy(() -> lookup.requireDeletable(TARGET_ID, RECORD_ID))
                .isInstanceOf(RecordReferencedException.class)
                .hasMessageContaining("45")
                .hasMessageContaining("i još 44");
    }

    @Test
    @DisplayName("stupac s prekidom veze vraca zapise koje treba odvezati, bez greske")
    void clearsInsteadOfBlocking() {
        companyHas(sourceTemplate("clear", false));
        linked(1, linkedRecord(11L, 5, false));

        List<ReferenceLookup.Broken> broken = lookup.requireDeletable(TARGET_ID, RECORD_ID);

        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).record().getId()).isEqualTo(11L);
        assertThat(broken.get(0).columnKey()).isEqualTo("proces");
    }

    /**
     * Prekid veze je IZMJENA zapisa koji vezu drzi. Da se ovdje napravi iznimka, brisanje na
     * drugom kraju bi zaobislo bravu koju je netko izricito stavio - i to bez traga na samom
     * zapisu.
     */
    @Test
    @DisplayName("zakljucan zapis blokira brisanje i kad stupac trazi prekid veze")
    void lockedRecordBlocksEvenWithClear() {
        companyHas(sourceTemplate("clear", false));
        linked(1, linkedRecord(11L, 5, true));

        assertThatThrownBy(() -> lookup.requireDeletable(TARGET_ID, RECORD_ID))
                .isInstanceOf(RecordReferencedException.class)
                .hasMessageContaining("zaključan");
    }

    @Test
    @DisplayName("kod visevrijednosne veze se mice samo jedan clan popisa")
    void removesOnlyOneMemberFromList() {
        SurveyResult record = linkedRecord(11L, new ArrayList<>(List.of(5, 7)), false);
        var broken = new ReferenceLookup.Broken(record, "proces", true);

        Map<String, Object> updated = lookup.withoutLink(broken, RECORD_ID);

        assertThat(updated.get("proces")).isEqualTo(List.of(7));
    }

    @Test
    @DisplayName("kod jednovrijednosne veze polje ostaje prazno")
    void clearsSingleValue() {
        var broken = new ReferenceLookup.Broken(linkedRecord(11L, 5, false), "proces", false);

        Map<String, Object> updated = lookup.withoutLink(broken, RECORD_ID);

        assertThat(updated).doesNotContainKey("proces");
    }

    /**
     * Obrazac koji je necemu cilj ne smije nestati: veze bi ostale pokazivati u prazno, a
     * stupac bi i dalje tvrdio da bira iz obrasca kojeg nema. Ovo je greska SHEME, pa poruka
     * imenuje obrazac i stupac, a ne zapise.
     */
    @Test
    @DisplayName("obrazac koji je necijem stupcu cilj se ne brise")
    void blocksTemplateDeletion() {
        companyHas(sourceTemplate("restrict", false));

        assertThatThrownBy(() -> lookup.requireTemplateDeletable(TARGET_ID))
                .isInstanceOf(RecordReferencedException.class)
                .hasMessageContaining("Rizici")
                .hasMessageContaining("Proces");
    }

    // ===================== GUMB "POVEZANI ZAPISI" =====================

    /**
     * Skupine se IZVODE iz shema, ne konfiguriraju.
     *
     * Isti podatak ("tko na mene pokazuje") vec sluzi provjeri pri brisanju, pa bi trazenje da
     * ga netko upise jos jednom rukom bilo ponavljanje koje se moze raziici sa stvarnoscu.
     */
    /** Obrazac "Procesi" - onaj nad kojim se gumb pritisce. Sam po sebi nema veza. */
    private Template targetTemplate(ColumnEntry... extra) {
        Template template = new Template(COMPANY_ID, "Procesi");
        template.setId(TARGET_ID);
        template.setDefinitions(new ArrayList<>(List.of(extra)));
        return template;
    }

    private SurveyResult record(Object outgoingValue) {
        SurveyResult record = new SurveyResult();
        record.setId(RECORD_ID);
        record.setCompanyId(COMPANY_ID);
        record.setTemplateId(TARGET_ID);
        record.setData(outgoingValue == null ? new HashMap<>() : new HashMap<>(Map.of("vlasnik", outgoingValue)));
        return record;
    }

    @Test
    @DisplayName("skupine povezanih zapisa se izvode iz shema, bez ijedne postavke")
    void groupsComeFromSchemas() {
        companyHas(sourceTemplate("restrict", false), targetTemplate());
        when(queryRepository.countLinkedTo(SOURCE_ID, "proces", RECORD_ID)).thenReturn(3L);

        var groups = lookup.groupsFor(targetTemplate(), record(null));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).templateName()).isEqualTo("Rizici");
        assertThat(groups.get(0).columnKey()).isEqualTo("proces");
        assertThat(groups.get(0).columnLabel()).isEqualTo("Proces");
        assertThat(groups.get(0).total()).isEqualTo(3);
        assertThat(groups.get(0).direction()).isEqualTo(RelatedGroupResponse.Direction.INCOMING);
    }

    /**
     * Obrazac koji na nacelnoj razini moze pokazivati ovamo, ali na OVAJ zapis ne pokazuje, u
     * dijalogu nema sto raditi - prazna kartica je klik koji nista ne otvara.
     */
    @Test
    @DisplayName("skupina bez ijednog zapisa se izostavlja")
    void skipsEmptyGroups() {
        companyHas(sourceTemplate("restrict", false), targetTemplate());
        when(queryRepository.countLinkedTo(SOURCE_ID, "proces", RECORD_ID)).thenReturn(0L);

        assertThat(lookup.groupsFor(targetTemplate(), record(null))).isEmpty();
    }

    /**
     * Smjer NAPRIJED nosi id-eve, a ne uvjet: oni vec stoje u podacima retka, pa se zapisi
     * traze bas po njima. Filtar bi bio pitanje o sadrzaju, a ovdje se ne pita nista - zna se.
     */
    @Test
    @DisplayName("zapisi na koje ovaj pokazuje cine vlastitu skupinu, s id-evima")
    void outgoingGroupCarriesIds() {
        Template owner = new Template(COMPANY_ID, "Osobe");
        owner.setId(40L);
        Template target = targetTemplate(new ColumnEntry("vlasnik", "reference", null, "Vlasnik",
                false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, true, false,
                        null, null, null, 40L, "ime", "restrict")));
        companyHas(target, owner);
        when(templateRepository.findById(40L)).thenReturn(java.util.Optional.of(owner));

        var groups = lookup.groupsFor(target, record(new ArrayList<>(List.of(7, 9))));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).direction()).isEqualTo(RelatedGroupResponse.Direction.OUTGOING);
        assertThat(groups.get(0).templateName()).isEqualTo("Osobe");
        assertThat(groups.get(0).columnLabel()).isEqualTo("Vlasnik");
        assertThat(groups.get(0).recordIds()).containsExactly(7L, 9L);
        assertThat(groups.get(0).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("nepopunjena veza ne pravi skupinu")
    void emptyOutgoingIsSkipped() {
        Template target = targetTemplate(new ColumnEntry("vlasnik", "reference", null, "Vlasnik",
                false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                        null, null, null, 40L, "ime", "restrict")));
        companyHas(target);

        assertThat(lookup.groupsFor(target, record(null))).isEmpty();
    }

    // ===================== NAZIV POVEZANOG ZAPISA =====================

    /**
     * Zapis bez naziva i zapis cije ime jos putuje ne smiju izgledati isto.
     *
     * Naziv zivi u drugom obrascu, pa ga celija dohvaca zasebno i dok ceka pokazuje goli
     * {@code #12}. Kad bi i zapis bez naziva bio {@code #12}, ta dva stanja - "jos ne znam" i
     * "znam, i nema ga" - bila bi na ekranu ista, pa se cekalo nesto sto nikad ne stigne.
     * Njezina primjedba nakon rucne provjere.
     */
    @Test
    @DisplayName("zapis bez naziva se ispisuje drukcije od onog koji se jos ucitava")
    void namelessRecordIsNamedAsSuch() {
        Template target = targetTemplate();
        when(templateRepository.findById(TARGET_ID)).thenReturn(java.util.Optional.of(target));
        when(surveyRepository.findAllById(List.of(11L, 12L)))
                .thenReturn(List.of(named(11L, "Nabava"), named(12L, null)));

        var options = lookup.named(target, "naziv", List.of(11L, 12L));

        assertThat(options.content()).extracting("label")
                .containsExactly("Nabava", "#12 (bez naziva)");
    }

    /** Zapis ciljanog obrasca s nazivom ili bez njega. */
    private SurveyResult named(long id, String name) {
        SurveyResult record = new SurveyResult();
        record.setId(id);
        record.setCompanyId(COMPANY_ID);
        record.setTemplateId(TARGET_ID);
        record.setData(name == null ? new HashMap<>() : new HashMap<>(Map.of("naziv", name)));
        return record;
    }

    /** Obrazac koji pokazuje sam na sebe se smije obrisati - odlazi zajedno sa svojim vezama. */
    @Test
    @DisplayName("veza obrasca na samog sebe ne sprjecava njegovo brisanje")
    void selfReferenceDoesNotBlockTemplateDeletion() {
        Template self = new Template(COMPANY_ID, "Procesi");
        self.setId(TARGET_ID);
        self.setDefinitions(new ArrayList<>(List.of(
                new ColumnEntry("nadredeni", "reference", null, "Nadređeni", false, false, null, false,
                        null, reference("restrict", false)))));
        companyHas(self);

        assertThatCode(() -> lookup.requireTemplateDeletable(TARGET_ID)).doesNotThrowAnyException();
    }
}
