package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.RecordChangeResponse;
import com.example.demo.model.RecordChange;
import com.example.demo.model.Role;
import com.example.demo.repository.RecordChangeRepository;
import com.example.demo.service.RecordChangeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - revizijski trag zapisa.
 *
 * Ovdje se ne provjerava da se promjene spremaju, nego da se sprema BAS ONO sto je promjena:
 * polje koje se nije dirnulo ne smije proizvesti redak (inace je trag necitljiv), a polje
 * koje je vrijednost dobilo ili izgubilo mora ga proizvesti (inace trag laze presucivanjem).
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class RecordChangeTest {

    private static final Long SURVEY = 42L;
    private static final Long TEMPLATE = 18L;
    private static final Long COMPANY = 7L;

    @Mock
    private RecordChangeRepository repository;

    private final AuthContext authContext = new AuthContext();

    @BeforeEach
    void loggedIn() {
        authContext.set(new AuthenticatedUser(2L, "mhorvat", Role.USER, COMPANY));
    }

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private RecordChangeService service() {
        return new RecordChangeService(repository, authContext);
    }

    /** Spremljeni retci, redom kojim su predani repozitoriju. */
    @SuppressWarnings("unchecked")
    private List<RecordChange> captureSaved() {
        ArgumentCaptor<List<RecordChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> data(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("stvaranje zapisa se biljezi kao promjena iz nicega - povijest pocinje od nastanka")
    void creationIsRecordedAsChangeFromNothing() {
        int count = service().capture(SURVEY, TEMPLATE, COMPANY, null, data("ocjena", 3, "komentar", "ok"));

        assertThat(count).isEqualTo(2);
        assertThat(captureSaved())
                .allSatisfy(change -> assertThat(change.getOldValue()).isNull())
                .extracting(RecordChange::getNewValue)
                .containsExactlyInAnyOrder("3", "ok");
    }

    @Test
    @DisplayName("biljezi se SAMO promijenjeno polje, ne cijeli redak")
    void onlyChangedFieldsAreRecorded() {
        Map<String, Object> before = data("ocjena", 3, "komentar", "ok");
        Map<String, Object> after = data("ocjena", 4, "komentar", "ok");

        int count = service().capture(SURVEY, TEMPLATE, COMPANY, before, after);

        assertThat(count).isEqualTo(1);
        RecordChange change = captureSaved().getFirst();
        assertThat(change.getColumnKey()).isEqualTo("ocjena");
        assertThat(change.getOldValue()).isEqualTo("3");
        assertThat(change.getNewValue()).isEqualTo("4");
        assertThat(change.getUsername()).isEqualTo("mhorvat");
    }

    /**
     * Prilog nije vrijednost zapisa - stupac tipa "file" u podacima ne drzi nista, pa ga
     * usporedba starog i novog stanja nikad ne vidi. Zato ide zasebnim putem, ali u istu
     * tablicu i pod isti kljuc stupca: citatelju povijesti je prilozen dokaz promjena retka
     * jednako kao i upisana vrijednost.
     */
    @Test
    @DisplayName("prilozena datoteka se biljezi kao vrijednost koju je polje dobilo")
    void attachmentAddedIsRecordedAsNewValue() {
        service().attachmentAdded(SURVEY, TEMPLATE, COMPANY, "ponuda", "ponuda.pdf");

        ArgumentCaptor<RecordChange> captor = ArgumentCaptor.forClass(RecordChange.class);
        verify(repository).save(captor.capture());
        RecordChange change = captor.getValue();
        assertThat(change.getColumnKey()).isEqualTo("ponuda");
        assertThat(change.getOldValue()).isNull();
        assertThat(change.getNewValue()).isEqualTo("ponuda.pdf");
        assertThat(change.getUsername()).isEqualTo("mhorvat");
    }

    @Test
    @DisplayName("uklonjena datoteka se biljezi kao vrijednost koju je polje izgubilo")
    void attachmentRemovedIsRecordedAsLostValue() {
        service().attachmentRemoved(SURVEY, TEMPLATE, COMPANY, "ponuda", "ponuda.pdf");

        ArgumentCaptor<RecordChange> captor = ArgumentCaptor.forClass(RecordChange.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOldValue()).isEqualTo("ponuda.pdf");
        assertThat(captor.getValue().getNewValue()).isNull();
    }

    @Test
    @DisplayName("spremanje bez ijedne izmjene ne ostavlja trag - i ne dira repozitorij")
    void unchangedRecordLeavesNoTrace() {
        Map<String, Object> same = data("ocjena", 3);

        assertThat(service().capture(SURVEY, TEMPLATE, COMPANY, same, data("ocjena", 3))).isZero();

        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("obrisana vrijednost je promjena jednako kao i upisana")
    void clearingAValueIsAChange() {
        service().capture(SURVEY, TEMPLATE, COMPANY, data("komentar", "bilo je"), data());

        RecordChange change = captureSaved().getFirst();
        assertThat(change.getOldValue()).isEqualTo("bilo je");
        assertThat(change.getNewValue()).isNull();
    }

    @Test
    @DisplayName("prazan tekst se biljezi kao 'nema vrijednosti', a ne kao vrijednost")
    void blankTextCountsAsNoValue() {
        // inace bi ciscenje polja dalo redak "iz 'x' u ''", a to citatelju ne znaci nista
        assertThat(service().capture(SURVEY, TEMPLATE, COMPANY, data("komentar", ""), data())).isZero();
    }

    @Test
    @DisplayName("visevrijednosni stupac ide u trag kao popis, a ne kao oblik spremanja")
    void multiValuedColumnIsRecordedAsList() {
        service().capture(SURVEY, TEMPLATE, COMPANY,
                data("mjere", List.of("POL")), data("mjere", List.of("POL", "ORG")));

        assertThat(captureSaved().getFirst().getNewValue()).isEqualTo("POL, ORG");
    }

    @Test
    @DisplayName("predugacka vrijednost se skrati - stupac u bazi ima granicu, a upis ne smije pasti")
    void longValueIsTruncated() {
        service().capture(SURVEY, TEMPLATE, COMPANY, null, data("opis", "x".repeat(1500)));

        String saved = captureSaved().getFirst().getNewValue();
        assertThat(saved).hasSize(1000).endsWith("...");
    }

    @Test
    @DisplayName("naziv polja dolazi iz TRENUTNE sheme, a obrisani stupac zadrzava svoj kljuc")
    void labelComesFromCurrentSchemaAndSurvivesDeletion() {
        RecordChange existing = new RecordChange(SURVEY, TEMPLATE, COMPANY, "ocjena",
                "3", "4", "mhorvat", Instant.now());
        RecordChange removed = new RecordChange(SURVEY, TEMPLATE, COMPANY, "stari_stupac",
                "a", "b", "mhorvat", Instant.now());
        when(repository.findBySurveyIdOrderByChangedAtDescIdDesc(SURVEY))
                .thenReturn(List.of(existing, removed));

        List<ColumnDefinitionResponse> schema = List.of(
                new ColumnDefinitionResponse("ocjena", "number", null, "Ocjena kontrole",
                        false, false, null, false));

        List<RecordChangeResponse> history = service().history(SURVEY, schema);

        assertThat(history).extracting(RecordChangeResponse::columnLabel)
                .containsExactly("Ocjena kontrole", "stari_stupac");
    }
}
