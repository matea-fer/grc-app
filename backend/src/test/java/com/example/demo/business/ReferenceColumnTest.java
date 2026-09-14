package com.example.demo.business;

import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Template;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CodebookService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.FormulaEvaluator;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - pravila stupca koji pokazuje na zapis DRUGOG obrasca.
 *
 * Zajednicka nit: veza se deklarira jednom, a posljedice nosi svaki kasniji unos i svako
 * kasnije brisanje. Zato se sve sto se moze provjeriti pri deklariranju provjerava ODMAH -
 * korisnik koji unosi podatke shemu nije ni vidio, a ne moze je ni popraviti.
 *
 * Provjera vrijednosti pri upisu je u {@code ReferenceValidationTest}, a pravila brisanja u
 * {@code ReferenceDeleteRulesTest}.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class ReferenceColumnTest {

    private static final long TEMPLATE_ID = 10L;
    private static final long TARGET_ID = 20L;

    @Mock
    private TemplateService templateService;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private LogService logService;

    @Mock
    private CodebookService codebookService;

    @Mock
    private CodebookItemRepository codebookItems;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private RecordChangeService recordChangeService;

    private ColumnDefinitionService service() {
        return new ColumnDefinitionService(templateService, surveyRepository, codebookService,
                new SchemaValidator(codebookItems, surveyRepository), new FormulaEvaluator(),
                attachmentCleanup, logService, recordChangeService);
    }

    private Template template(long id, String name, ColumnEntry... entries) {
        Template template = new Template(3L, name);
        template.setId(id);
        template.setDefinitions(new ArrayList<>(List.of(entries)));
        return template;
    }

    /** Obrazac "Procesi" s tekstualnim nazivom - tipican cilj veze. */
    private Template targetTemplate() {
        return template(TARGET_ID, "Procesi", new ColumnEntry("naziv", "string", null));
    }

    private static ColumnOptions reference(Long targetTemplateId, String displayKey, String onDelete,
                                           boolean multiple) {
        return new ColumnOptions(false, null, null, null, null, null, null, null, multiple, false,
                null, null, null, targetTemplateId, displayKey, onDelete);
    }

    private CreateColumnDefinitionRequest column(String key, String type, ColumnOptions options) {
        return new CreateColumnDefinitionRequest(key, type, null, null, false, false, null, false, null, options);
    }

    @Test
    @DisplayName("veza s obrascem i stupcem za naziv prolazi")
    void acceptsValidReference() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));
        when(templateService.requireOwned(TARGET_ID)).thenReturn(targetTemplate());

        assertThatCode(() -> service().create(TEMPLATE_ID,
                column("proces", "reference", reference(TARGET_ID, "naziv", "restrict", false))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("veza bez odabranog obrasca se odbija")
    void rejectsReferenceWithoutTarget() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("proces", "reference", reference(null, "naziv", "restrict", false))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("povezani obrazac");
    }

    /**
     * Tudi obrazac zavrsava kao 404 jos u {@code requireOwned} - jednako kao svugdje.
     *
     * Ovo nije formalnost nego rupa u izolaciji koja bi se otvorila kroz SHEMU, a ne kroz
     * podatke: veza uperena u tudi obrazac bi njegove zapise nudila na odabir.
     */
    @Test
    @DisplayName("veza na obrazac druge firme je 404")
    void rejectsForeignTarget() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));
        when(templateService.requireOwned(TARGET_ID))
                .thenThrow(new ResourceNotFoundException("Template", TARGET_ID));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("proces", "reference", reference(TARGET_ID, "naziv", "restrict", false))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("stupac za naziv mora postojati u ciljanom obrascu")
    void rejectsUnknownDisplayColumn() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));
        when(templateService.requireOwned(TARGET_ID)).thenReturn(targetTemplate());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("proces", "reference", reference(TARGET_ID, "nepostojeci", "restrict", false))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("nema stupac");
    }

    /**
     * Veza kao naziv veze bi se razrjesavala u lancu i mogla bi se vrtjeti u krug, a gumb u
     * zapisu ne drzi nikakvu vrijednost - oboje bi dalo praznu celiju.
     */
    @Test
    @DisplayName("stupac bez vlastite vrijednosti ne moze biti naziv")
    void rejectsValuelessDisplayColumn() {
        Template target = template(TARGET_ID, "Procesi", new ColumnEntry("akcija", "button", null));
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));
        when(templateService.requireOwned(TARGET_ID)).thenReturn(target);

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("proces", "reference", reference(TARGET_ID, "akcija", "restrict", false))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("ne može služiti kao naziv");
    }

    /** Hijerarhija (proces -> nadredeni proces) je trazena, a ne greska. */
    @Test
    @DisplayName("obrazac smije pokazivati sam na sebe")
    void allowsSelfReference() {
        Template self = template(TEMPLATE_ID, "Procesi", new ColumnEntry("naziv", "string", null));
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(self);
        when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(self);

        assertThatCode(() -> service().create(TEMPLATE_ID,
                column("nadredeni", "reference", reference(TEMPLATE_ID, "naziv", "restrict", false))))
                .doesNotThrowAnyException();
    }

    /**
     * Veza se uvijek bira kroz dijalog s pretragom, pa bi postavka "padajuci izbornik" bila
     * tvrdnja koju nista ne provodi.
     */
    @Test
    @DisplayName("nacin odabira se ne moze postaviti na vezi")
    void rejectsPickerModeOnReference() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));

        ColumnOptions withPicker = new ColumnOptions(false, null, null, null, null, null, null,
                "dropdown", false, false, null, null, null, TARGET_ID, "naziv", "restrict");

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, column("proces", "reference", withPicker)))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Način odabira");
    }

    @Test
    @DisplayName("povezani obrazac se ne moze postaviti na stupcu koji nije veza")
    void rejectsTargetOnOtherType() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("grad", "string", reference(TARGET_ID, "naziv", "restrict", false))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Povezani obrazac");
    }

    @Test
    @DisplayName("veza ne moze imati zadanu vrijednost")
    void rejectsDefaultValueOnReference() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Rizici"));
        when(templateService.requireOwned(TARGET_ID)).thenReturn(targetTemplate());

        var request = new CreateColumnDefinitionRequest("proces", "reference", null, null, false, false,
                "5", false, null, reference(TARGET_ID, "naziv", "restrict", false));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, request))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("zadanu vrijednost");
    }

    /**
     * Gumb "Povezani zapisi" NE trazi nikakvu konfiguraciju.
     *
     * Koji stupci na ovaj obrazac pokazuju vec pise u shemama, pa bi trazenje da se to upise
     * jos jednom rukom bilo ponavljanje koje se povrh svega moze RAZICI sa stvarnoscu -
     * stupac se preimenuje ili makne, a gumb i dalje tvrdi da ga gleda.
     */
    @Test
    @DisplayName("gumb za povezane zapise ne trazi nikakve postavke")
    void relatedButtonNeedsNoConfiguration() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(TEMPLATE_ID, "Procesi"));

        ColumnOptions justAction = new ColumnOptions(false, null, null, null, null, null, null,
                null, false, false, null, "Potprocesi", ColumnOptions.ACTION_RELATED);

        assertThatCode(() -> service().create(TEMPLATE_ID, column("povezani", "button", justAction)))
                .doesNotThrowAnyException();
    }
}
