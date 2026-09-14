package com.example.demo.core;

import com.example.demo.dto.CodebookItemResponse;
import com.example.demo.dto.SaveCodebookItemsRequest;
import com.example.demo.dto.SaveCodebookItemsRequest.CodebookItemInput;
import com.example.demo.exception.CodebookInUseException;
import com.example.demo.exception.DuplicateCodebookItemCodeException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.service.CodebookItemService;
import com.example.demo.service.CodebookService;
import com.example.demo.service.CodebookUsage;
import com.example.demo.service.LogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CORE test - skupno spremanje stavki.
 *
 * Spremanje je puna zamjena, pa se ovdje provjerava troje sto se lako pokvari:
 * redoslijed koji dodjeljuje server, brisanje stavki kojih u popisu vise nema, i to
 * da se izmjena veze uz id stavke, a ne uz sifru.
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodebookItemServiceTest {

    private static final Long CODEBOOK_ID = 1L;

    @Mock
    private CodebookItemRepository repository;

    @Mock
    private CodebookService codebookService;

    @Mock
    private LogService logService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private CodebookUsage codebookUsage;

    @InjectMocks
    private CodebookItemService service;

    private static CodebookItem item(Long id, String code, String name, int sortOrder) {
        CodebookItem item = new CodebookItem(CODEBOOK_ID, code, name, true, sortOrder);
        item.setId(id);
        return item;
    }

    @BeforeEach
    void codebookIsEditable() {
        Codebook codebook = new Codebook("Statusi", CodebookScope.TENANT, 7L);
        codebook.setId(CODEBOOK_ID);
        when(codebookService.requireEditable(CODEBOOK_ID)).thenReturn(codebook);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private SaveCodebookItemsRequest request(CodebookItemInput... items) {
        return new SaveCodebookItemsRequest(List.of(items));
    }

    @Test
    @DisplayName("sortOrder dodjeljuje server po polozaju u nizu, ne klijent")
    void serverAssignsSortOrder() {
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(new ArrayList<>());

        List<CodebookItemResponse> saved = service.save(CODEBOOK_ID, request(
                new CodebookItemInput(null, "C", "Treći", true),
                new CodebookItemInput(null, "A", "Prvi", true),
                new CodebookItemInput(null, "B", "Drugi", false)));

        assertThat(saved).extracting(CodebookItemResponse::code, CodebookItemResponse::sortOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("C", 0),
                        org.assertj.core.groups.Tuple.tuple("A", 1),
                        org.assertj.core.groups.Tuple.tuple("B", 2));
        assertThat(saved.get(2).active()).isFalse();
    }

    @Test
    @DisplayName("stavka koje u popisu vise nema se brise")
    void missingItemIsRemoved() {
        CodebookItem ostaje = item(10L, "OPEN", "Otvoren", 0);
        CodebookItem nestaje = item(11L, "CLOSED", "Zatvoren", 1);
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID))
                .thenReturn(new ArrayList<>(List.of(ostaje, nestaje)));

        service.save(CODEBOOK_ID, request(new CodebookItemInput(10L, "OPEN", "Otvoren", true)));

        verify(repository).deleteAll(List.of(nestaje));
    }

    /**
     * Suprotno od brisanja cijelog sifrarnika: ovdje smeta tek STVARNO UPISANA sifra.
     * Brisanje bi inace unatrag pretvorilo vec spremljene zapise u sifre bez znacenja,
     * pa poruka nudi iskljucivanje - ono makne stavku iz ponude, a stare zapise ostavi citljivima.
     */
    @Test
    @DisplayName("stavka čija je šifra upisana u zapise se ne briše")
    void usedItemCannotBeRemoved() {
        CodebookItem ostaje = item(10L, "OPEN", "Otvoren", 0);
        CodebookItem nestaje = item(11L, "CLOSED", "Zatvoren", 1);
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID))
                .thenReturn(new ArrayList<>(List.of(ostaje, nestaje)));
        when(codebookUsage.firstCodeInUse(any(), eq(Set.of("CLOSED")))).thenReturn(
                new CodebookUsage.CodeUse("šifra \"CLOSED\" (obrazac \"Zahtjevi\", stupac \"status\")", false));

        assertThatThrownBy(() -> service.save(CODEBOOK_ID,
                request(new CodebookItemInput(10L, "OPEN", "Otvoren", true))))
                .isInstanceOf(CodebookInUseException.class)
                .hasMessageContaining("CLOSED")
                .hasMessageContaining("Isključite");

        verify(repository, never()).deleteAll(any());
    }

    /**
     * Isti otpor, drugi savjet: stavku koja je necija zadana vrijednost iskljucivanje NE
     * rjesava - stupac bi je i dalje predpopunjavao, pa bi prvo sljedece uredivanje tog
     * stupca palo. Nju treba maknuti sa stupca.
     */
    @Test
    @DisplayName("stavka koja je zadana vrijednost stupca traži drugi savjet, ne isključivanje")
    void defaultValueItemGetsDifferentAdvice() {
        CodebookItem ostaje = item(10L, "OPEN", "Otvoren", 0);
        CodebookItem nestaje = item(11L, "CLOSED", "Zatvoren", 1);
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID))
                .thenReturn(new ArrayList<>(List.of(ostaje, nestaje)));
        when(codebookUsage.firstCodeInUse(any(), eq(Set.of("CLOSED")))).thenReturn(
                new CodebookUsage.CodeUse("šifra \"CLOSED\" (obrazac \"Zahtjevi\", stupac \"status\")", true));

        assertThatThrownBy(() -> service.save(CODEBOOK_ID,
                request(new CodebookItemInput(10L, "OPEN", "Otvoren", true))))
                .isInstanceOf(CodebookInUseException.class)
                .hasMessageContaining("zadana vrijednost")
                .hasMessageContaining("Prvo maknite")
                .hasMessageNotContaining("Isključite");

        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("postojeca stavka se prepoznaje po id-u, pa se sifra smije ispraviti")
    void existingItemIsMatchedById() {
        CodebookItem existing = item(10L, "OPNE", "Otvoren", 0);
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID))
                .thenReturn(new ArrayList<>(List.of(existing)));

        List<CodebookItemResponse> saved = service.save(CODEBOOK_ID,
                request(new CodebookItemInput(10L, "OPEN", "Otvoren", true)));

        // ista stavka (isti id), ispravljena sifra - ne brise se i ne stvara nova
        assertThat(saved).singleElement()
                .extracting(CodebookItemResponse::id, CodebookItemResponse::code)
                .containsExactly(10L, "OPEN");
        verify(repository, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("ista sifra dvaput u popisu se odbija, bez obzira na velika slova")
    void duplicateCodeInPayloadIsRejected() {
        assertThatThrownBy(() -> service.save(CODEBOOK_ID, request(
                new CodebookItemInput(null, "OPEN", "Otvoren", true),
                new CodebookItemInput(null, "open", "Duplikat", true))))
                .isInstanceOf(DuplicateCodebookItemCodeException.class);

        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("id koji ne pripada ovom sifrarniku je 404")
    void foreignItemIdIsNotFound() {
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(new ArrayList<>());

        assertThatThrownBy(() -> service.save(CODEBOOK_ID,
                request(new CodebookItemInput(999L, "OPEN", "Otvoren", true))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("sifra i naziv se spremaju bez rubnih razmaka")
    void valuesAreTrimmed() {
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(new ArrayList<>());

        List<CodebookItemResponse> saved = service.save(CODEBOOK_ID,
                request(new CodebookItemInput(null, "  OPEN  ", "  Otvoren  ", true)));

        assertThat(saved).singleElement()
                .extracting(CodebookItemResponse::code, CodebookItemResponse::name)
                .containsExactly("OPEN", "Otvoren");
    }

    @Test
    @DisplayName("verzija sifrarnika se izricito podigne - inace izmjena stavki ne bi dirala roditelja")
    void parentVersionIsBumped() {
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(new ArrayList<>());

        service.save(CODEBOOK_ID, request(new CodebookItemInput(null, "OPEN", "Otvoren", true)));

        verify(entityManager).lock(any(Codebook.class), org.mockito.ArgumentMatchers
                .eq(LockModeType.OPTIMISTIC_FORCE_INCREMENT));
    }

    @Test
    @DisplayName("citanje stavki prolazi kroz provjeru dostupnosti sifrarnika")
    void readingGoesThroughAccessCheck() {
        when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(List.of());

        service.getForCodebook(CODEBOOK_ID);

        verify(codebookService).requireAccessible(CODEBOOK_ID);
    }
}
