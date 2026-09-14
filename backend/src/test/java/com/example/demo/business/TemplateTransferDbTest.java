package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.controller.TemplateTransferController;
import com.example.demo.dto.TransferPlanResponse;
import com.example.demo.dto.TransferTemplatesRequest;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.model.Template;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.TemplateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUSINESS test - prijenos obrazaca kroz PRAVU BAZU.
 *
 * Mock testovi ({@link TemplateTransferTest}) brane pravila, ali nista ne kazu o putu kroz
 * Postgres. Kod prijenosa je bas taj put najosjetljiviji: stupci putuju kao {@code jsonb}, a
 * u njima stoje PRESLIKANI id-evi. Izgubi li se {@code options.targetTemplateId} u
 * serijalizaciji, kopija i dalje izgleda ispravno - stupac je ondje, tip je ondje - a pokazuje
 * na obrazac druge firme ili u prazno.
 *
 * Zato se ovdje poslije svakog poziva radi {@code em.clear()}: bez toga bi Hibernate na
 * pogoden id vratio objekt koji jos drzi u memoriji, pa se nikad ne bi procitalo ono sto je
 * baza stvarno spremila. Tocno tako je brisanje priloga prolazilo kroz zelen test i padalo u
 * aplikaciji (vidi {@code AttachmentStorageTest.removeContentWorksWithoutLoadedEntity}).
 *
 * <p><b>Sto ovaj test NE pokriva:</b> rasclanjivanje pravog JSON-a. Poziva se kontroler kao
 * grah, pa DTO nastaje u kodu, a ne iz tijela zahtjeva. Za to treba MockMvc, koji je u Spring
 * Bootu 4 izasao iz {@code spring-boot-starter-test} u zaseban modul - dodavanje te ovisnosti
 * je odluka za build, ne za test.
 */
@Tag("business")
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
@Transactional
class TemplateTransferDbTest {

    @Autowired
    private TemplateTransferController controller;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private AuthContext authContext;

    @PersistenceContext
    private EntityManager em;

    private Long izvor;
    private Long cilj;
    private Long osobeId;
    private Long riziciId;

    @BeforeEach
    void seed() {
        izvor = companyRepository.save(new Company("Test-izvor")).getId();
        cilj = companyRepository.save(new Company("Test-cilj")).getId();

        Template osobe = new Template(izvor, "Odgovorne osobe");
        osobe.setDefinitions(List.of(new ColumnEntry("ime", "string", null)));
        osobeId = templateRepository.save(osobe).getId();

        Template rizici = new Template(izvor, "Rizici");
        rizici.setDefinitions(List.of(new ColumnEntry("odgovorna", "reference", null, "Odgovorna",
                false, false, null, false, null,
                ColumnOptions.EMPTY.withTargetTemplateId(osobeId))));
        riziciId = templateRepository.save(rizici).getId();

        // prijenos smije samo globalni ADMIN - onaj koji nije vezan ni uz jednu firmu
        authContext.set(new AuthenticatedUser(1L, "test-admin", Role.ADMIN, null));

        em.flush();
        em.clear();
    }

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private TransferTemplatesRequest request(Long... templateIds) {
        return new TransferTemplatesRequest(izvor, cilj, List.of(templateIds));
    }

    /** Kopija procitana iz BAZE, ne iz konteksta - vidi opis razreda. */
    private Template copyNamed(String name) {
        em.flush();
        em.clear();
        return templateRepository.findByCompanyId(cilj).stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("kopija \"" + name + "\" nije nastala"));
    }

    /**
     * Srz cijele znacajke: veza mora pokazivati na KOPIJU, ne na izvornik.
     *
     * Da se preslikavanje izgubi bilo gdje - u servisu ili na putu kroz jsonb - kopija bi
     * pokazivala preko granice firme, na obrazac koji ciljna firma ne smije ni vidjeti.
     */
    @Test
    @DisplayName("veza preseljenog obrasca pokazuje na kopiju, ne na izvornik")
    void referencePointsAtTheCopy() {
        controller.transfer(request(osobeId, riziciId));

        Long kopijaOsoba = copyNamed("Odgovorne osobe").getId();
        Template kopijaRizika = copyNamed("Rizici");

        assertThat(kopijaOsoba).isNotEqualTo(osobeId);
        assertThat(kopijaRizika.getDefinitions()).singleElement().satisfies(column -> {
            assertThat(column.type()).isEqualTo("reference");
            assertThat(column.options().targetTemplateId())
                    .as("veza mora pokazivati na kopiju (%s), a ne na izvornik (%s)",
                            kopijaOsoba, osobeId)
                    .isEqualTo(kopijaOsoba);
        });
    }

    @Test
    @DisplayName("najava ne stvara obrasce")
    void previewCreatesNothing() {
        TransferPlanResponse plan = controller.preview(request(osobeId, riziciId));

        assertThat(plan.templates()).hasSize(2);
        em.flush();
        em.clear();
        assertThat(templateRepository.findByCompanyId(cilj)).isEmpty();
    }

    /**
     * Veza na obrazac izvan odabira ne smije preziviti kao broj koji u ciljnoj firmi slucajno
     * nesto pogada - mora biti prazna, i to mora pisati u izvjestaju.
     */
    @Test
    @DisplayName("veza na obrazac izvan odabira ostaje prazna")
    void brokenReferenceIsClearedNotCarriedOver() {
        TransferPlanResponse plan = controller.transfer(request(riziciId));

        assertThat(plan.brokenReferences()).hasSize(1);
        assertThat(copyNamed("Rizici").getDefinitions()).singleElement().satisfies(column ->
                assertThat(column.options().targetTemplateId()).isNull());
    }

    /** Ponovljeni prijenos ne pregazi postojece nego nastaje kao kopija - i to preživi bazu. */
    @Test
    @DisplayName("drugi prijenos istog obrasca daje kopiju s novim nazivom")
    void secondTransferCreatesACopy() {
        controller.transfer(request(osobeId));
        em.flush();
        em.clear();

        controller.transfer(request(osobeId));
        em.flush();
        em.clear();

        assertThat(templateRepository.findByCompanyId(cilj))
                .extracting(Template::getName)
                .containsExactlyInAnyOrder("Odgovorne osobe", "Odgovorne osobe (kopija)");
    }
}
