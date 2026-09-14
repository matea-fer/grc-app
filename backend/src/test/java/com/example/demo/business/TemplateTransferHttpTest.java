package com.example.demo.business;

import com.example.demo.auth.JwtService;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import com.example.demo.model.Template;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.TemplateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS test - prijenos obrazaca kroz PRAVI zahtjev.
 *
 * Razlika prema {@link TemplateTransferDbTest} nije u tome sto se provjerava nego KOJIM PUTEM.
 * Ondje se kontroler zove kao grah, pa DTO nastaje u Javi; ovdje se salje pravo tijelo
 * zahtjeva, pa kroz posao prolaze i one cetiri stvari koje poziv u kodu preskoci:
 *
 * <ul>
 *   <li>Jackson - {@code templateIds} se stvarno rasclanjuje iz JSON-a,</li>
 *   <li>{@code @Valid} i {@code @NotEmpty} na {@code TransferTemplatesRequest},</li>
 *   <li>{@code ApiExceptionHandler} - preslikavanje iznimke u STATUS KOD,</li>
 *   <li>ulazni filtri.</li>
 * </ul>
 *
 * Zakljucak iz pokusa 02 koji je ovo trazio: "test koji gradi DTO u kodu nikad ne posalje
 * pravi JSON, pa ne vidi nista sto se dogodi pri rasclanjivanju - cijeli razred gresaka
 * tako prode kroz zelene jedinicne testove."
 */
@Tag("business")
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
@AutoConfigureMockMvc
@Transactional
class TemplateTransferHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private JwtService jwtService;

    @PersistenceContext
    private EntityManager em;

    private Long izvor;
    private Long cilj;
    private Long osobeId;
    private Long riziciId;
    private String token;

    @BeforeEach
    void seed() {
        izvor = companyRepository.save(new Company("HTTP-izvor")).getId();
        cilj = companyRepository.save(new Company("HTTP-cilj")).getId();

        Template osobe = new Template(izvor, "Odgovorne osobe");
        osobe.setDefinitions(List.of(new ColumnEntry("ime", "string", null)));
        osobeId = templateRepository.save(osobe).getId();

        Template rizici = new Template(izvor, "Rizici");
        rizici.setDefinitions(List.of(new ColumnEntry("odgovorna", "reference", null, "Odgovorna",
                false, false, null, false, null,
                ColumnOptions.EMPTY.withTargetTemplateId(osobeId))));
        riziciId = templateRepository.save(rizici).getId();

        // Pravi zahtjev prolazi kroz JwtAuthFilter, pa mu treba i pravi token - postavljanje
        // konteksta u kodu ovdje ne pomaze, jer ga filtar za svaki zahtjev postavlja iznova.
        // Globalni ADMIN nije vezan uz firmu; prijenos je jedina radnja nad dvjema firmama.
        User admin = new User();
        admin.setUsername("http-admin-" + System.nanoTime());
        admin.setPasswordHash("nije-bitno-token-se-izdaje-izravno");
        admin.setRole(Role.ADMIN);
        em.persist(admin);

        em.flush();
        em.clear();
        token = "Bearer " + jwtService.issue(admin);
    }

    /** Tijelo se sastavlja kao TEKST - da ga Jackson stvarno mora rasclaniti. */
    private String body(String templateIds) {
        return "{\"sourceCompanyId\":" + izvor + ",\"targetCompanyId\":" + cilj
                + ",\"templateIds\":" + templateIds + "}";
    }

    // ===================== put kroz Jackson =====================

    /**
     * Ovo je jedini test u projektu koji dokazuje da preslikani id prezivi CIJELI put:
     * JSON -> DTO -> servis -> jsonb -> natrag iz baze.
     */
    @Test
    @DisplayName("veza preslikana kroz pravi zahtjev pokazuje na kopiju")
    void referenceSurvivesTheWholeRoundTrip() throws Exception {
        mvc.perform(post("/api/template-transfer")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("[" + osobeId + "," + riziciId + "]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(2));

        em.flush();
        em.clear();

        Long kopijaOsoba = copyNamed("Odgovorne osobe").getId();
        assertThat(copyNamed("Rizici").getDefinitions()).singleElement().satisfies(column ->
                assertThat(column.options().targetTemplateId()).isEqualTo(kopijaOsoba));
    }

    @Test
    @DisplayName("najava vraća isti oblik i ne stvara ništa")
    void previewReturnsPlanAndChangesNothing() throws Exception {
        mvc.perform(post("/api/template-transfer/preview")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("[" + osobeId + "]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[0].sourceName").value("Odgovorne osobe"))
                .andExpect(jsonPath("$.codebooks").isArray())
                .andExpect(jsonPath("$.brokenReferences").isArray());

        em.flush();
        em.clear();
        assertThat(templateRepository.findByCompanyId(cilj)).isEmpty();
    }

    // ===================== validacija i statusi =====================

    /**
     * {@code @NotEmpty} provodi Spring pri vezanju tijela. Poziv kontrolera u kodu tu provjeru
     * preskace u cijelosti, pa je ovo jedino mjesto na kojem se vidi da uopce radi.
     */
    @Test
    @DisplayName("prazan popis obrazaca vraća 400, ne 500")
    void emptySelectionIsRejectedAsBadRequest() throws Exception {
        mvc.perform(post("/api/template-transfer")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("[]")))
                .andExpect(status().isBadRequest());
    }

    /** Ista firma s obje strane je pogresan zahtjev, a ne kvar posluzitelja. */
    @Test
    @DisplayName("ista firma s obje strane vraća 400")
    void sameCompanyIsBadRequest() throws Exception {
        mvc.perform(post("/api/template-transfer")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCompanyId\":" + izvor + ",\"targetCompanyId\":" + izvor
                                + ",\"templateIds\":[" + osobeId + "]}"))
                .andExpect(status().isBadRequest());
    }

    /** Obrazac tude firme se tretira kao da ne postoji - 404, ne 403 i nikako ne 500. */
    @Test
    @DisplayName("obrazac koji ne pripada izvornoj firmi vraća 404")
    void foreignTemplateIsNotFound() throws Exception {
        Long tudi = templateRepository.save(new Template(cilj, "Tudi")).getId();
        em.flush();
        em.clear();

        mvc.perform(post("/api/template-transfer")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("[" + tudi + "]")))
                .andExpect(status().isNotFound());
    }

    /**
     * Nedostaje obavezno polje. Bez rukovatelja bi ovo bio 500, a tipfeler u tijelu zahtjeva
     * ne smije izgledati kao kvar posluzitelja.
     */
    @Test
    @DisplayName("nepotpuno tijelo vraća 4xx s porukom, ne 500")
    void malformedBodyIsClientError() throws Exception {
        mvc.perform(post("/api/template-transfer")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetCompanyId\":" + cilj + "}"))
                .andExpect(status().is4xxClientError());
    }

    private Template copyNamed(String name) {
        return templateRepository.findByCompanyId(cilj).stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("kopija \"" + name + "\" nije nastala"));
    }
}
