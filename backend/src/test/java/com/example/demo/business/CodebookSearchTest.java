package com.example.demo.business;

import com.example.demo.model.CodebookItem;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.service.CodebookItemService;
import com.example.demo.service.CodebookService;
import com.example.demo.service.CodebookUsage;
import com.example.demo.service.LogService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - prijevod NAZIVA iz sifrarnika u sifre, podloga za pretragu zapisa.
 *
 * U zapisu stoji sifra ("ZG"), a korisnik trazi ono sto vidi ("Zagreb"). Bez ovog prijevoda
 * pretraga po sifrarnickom stupcu radi samo onome tko sifre zna napamet - dakle nikome.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class CodebookSearchTest {

    private static final Long CODEBOOK_ID = 7L;

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

    private CodebookItemService service() {
        return new CodebookItemService(repository, codebookService, codebookUsage, logService, entityManager);
    }

    private void items(CodebookItem... items) {
        lenient().when(repository.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(List.of(items));
    }

    private static CodebookItem item(String code, String name, boolean active) {
        return new CodebookItem(CODEBOOK_ID, code, name, active, 0);
    }

    @Test
    @DisplayName("naziv se trazi po dijelu i bez obzira na velika slova")
    void matchesNameByPartAndIgnoresCase() {
        items(item("ZG", "Zagreb", true), item("ST", "Split", true));

        assertThat(service().codesMatching(CODEBOOK_ID, "zag")).containsExactly("ZG");
        assertThat(service().codesMatching(CODEBOOK_ID, "ZAGREB")).containsExactly("ZG");
    }

    /** Tko utipka "ZG" ocito trazi bas nju - bilo bi cudno da mu pretraga ne nade nista. */
    @Test
    @DisplayName("trazi se i po samoj sifri, ne samo po nazivu")
    void matchesCodeToo() {
        items(item("ZG", "Zagreb", true), item("ST", "Split", true));

        assertThat(service().codesMatching(CODEBOOK_ID, "st")).containsExactly("ST");
    }

    /**
     * Iskljucena stavka se ne nudi za NOVE unose, ali zapisi koji su je dobili dok je bila
     * aktivna i dalje postoje. Da je pretraga preskace, ti bi zapisi postali nedohvatljivi -
     * iskljucivanje jedne stavke sifrarnika sakrilo bi tude podatke.
     */
    @Test
    @DisplayName("iskljucena stavka se i dalje moze pronaci")
    void inactiveItemsAreStillSearchable() {
        items(item("ZG", "Zagreb", true), item("OS", "Osijek", false));

        assertThat(service().codesMatching(CODEBOOK_ID, "osijek")).containsExactly("OS");
    }

    @Test
    @DisplayName("vise stavki koje odgovaraju daje vise sifara - trazi se bilo koja od njih")
    void severalMatchesGiveSeveralCodes() {
        items(item("ZG", "Zagreb", true), item("ZD", "Zadar", true), item("ST", "Split", true));

        assertThat(service().codesMatching(CODEBOOK_ID, "za")).containsExactly("ZG", "ZD");
    }

    @Test
    @DisplayName("prazan upit i stupac bez sifrarnika ne diraju bazu")
    void blankQueryAndMissingCodebookSkipTheDatabase() {
        assertThat(service().codesMatching(CODEBOOK_ID, "   ")).isEmpty();
        assertThat(service().codesMatching(null, "zagreb")).isEmpty();

        verifyNoInteractions(repository);
    }
}
