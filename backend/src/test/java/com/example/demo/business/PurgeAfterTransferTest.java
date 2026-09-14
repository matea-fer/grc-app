package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.dto.TransferTemplatesRequest;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.RecordChange;
import com.example.demo.model.Role;
import com.example.demo.model.Attachment;
import com.example.demo.model.AttachmentContent;
import com.example.demo.model.Codebook;
import com.example.demo.model.ColumnSequence;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.model.User;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.AttachmentContentRepository;
import com.example.demo.repository.AttachmentRepository;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.repository.ColumnSequenceRepository;
import com.example.demo.repository.RecordChangeRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.CompanyService;
import com.example.demo.service.TemplateTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUSINESS test - prazni li se firma do kraja i kad su obrasci u nju dosli PRIJENOSOM.
 *
 * Reprodukcija kvara nadenog u razvojnoj bazi: firma "Gamma Solutions" je ispraznjena, dnevnik
 * je zapisao "1 obraz.", firma je nestala - a obrazac koji je u nju bio prenesen ostao je u
 * bazi i dalje pokazujuci na firmu koje vise nema. Isti trag naden je i za jednu stariju firmu.
 *
 * NAMJERNO BEZ {@code @Transactional}: kvar je (ako postoji) u tome sto se preziviva commit,
 * a test koji se na kraju ponisti to ne bi ni mogao vidjeti. Zato se za sobom cisti rukom.
 */
@Tag("business")
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
class PurgeAfterTransferTest {

    @Autowired
    private CompanyService companyService;
    @Autowired
    private TemplateTransferService transferService;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private com.example.demo.service.AttachmentCleanup attachmentCleanup;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private AttachmentContentRepository attachmentContentRepository;
    @Autowired
    private RecordChangeRepository recordChangeRepository;
    @Autowired
    private ColumnSequenceRepository sequenceRepository;
    @Autowired
    private SurveyResultRepository surveyRepository;
    @Autowired
    private CodebookRepository codebookRepository;
    @Autowired
    private CodebookItemRepository codebookItemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuthContext authContext;

    private Long izvor;
    private Long cilj;

    @BeforeEach
    void setUp() {
        authContext.set(new AuthenticatedUser(1L, "repro-admin", Role.ADMIN, null));
        izvor = companyService.create(new CreateCompanyRequest("REPRO-izvor")).id();
        cilj = companyService.create(new CreateCompanyRequest("REPRO-cilj")).id();
    }

    /**
     * Ciscenje mora pokriti SVE sto test stvara, ne samo obrasce i firme.
     *
     * Test pise u pravu bazu i namjerno nema {@code @Transactional}, pa pad usred njega
     * ostavlja za sobom retke koje nitko vise nece pokupiti - a sljedeca provjera
     * cjelovitosti ih onda prijavi kao "krhotine", iako su nastale u testu.
     */
    @AfterEach
    void cleanUp() {
        for (Long id : List.of(izvor, cilj)) {
            List<Long> obrasci = templateRepository.findByCompanyId(id).stream()
                    .map(Template::getId).toList();
            if (!obrasci.isEmpty()) {
                sequenceRepository.deleteByTemplateIdIn(obrasci);
            }
            recordChangeRepository.deleteByCompanyId(id);
            // prilozi prije zapisa: nose i datoteku u pg_largeobject, koju oslobada tek
            // AttachmentCleanup - obicno brisanje retka ostavlja datoteku bez puta do nje
            attachmentCleanup.forCompany(id);
            // deleteAllInBatch, a ne nasi deleteByCompanyIdBulk: ti su @Modifying upiti i
            // traze otvorenu transakciju, koju purge ima a ovo ciscenje nema. deleteAllInBatch
            // je jednako bulk, ali nosi vlastitu transakciju.
            surveyRepository.deleteAllInBatch(surveyRepository.findByCompanyId(id));
            templateRepository.deleteAllInBatch(templateRepository.findByCompanyId(id));
            for (Codebook cb : codebookRepository.findByCompanyId(id)) {
                codebookItemRepository.deleteByCodebookId(cb.getId());
            }
            codebookRepository.deleteAllInBatch(codebookRepository.findByCompanyId(id));
            userRepository.deleteAllInBatch(userRepository.findByCompanyId(id));
            companyRepository.findById(id).ifPresent(companyRepository::delete);
        }
        authContext.clear();
    }

    private Long templateWithReference() {
        Template osobe = new Template(izvor, "REPRO Odgovorne osobe");
        osobe.setDefinitions(List.of(new ColumnEntry("ime", "string", null)));
        return templateRepository.save(osobe).getId();
    }

    /**
     * Tocno onaj slijed koji je ostavio trag u razvojnoj bazi: prijenos u firmu, pa arhiviranje
     * i praznjenje te firme.
     */
    @Test
    @DisplayName("prazna firma nakon prijenosa ne ostavlja obrazac")
    void purgeRemovesTransferredTemplate() {
        Long izvorniId = templateWithReference();
        transferService.transfer(new TransferTemplatesRequest(izvor, cilj, List.of(izvorniId)));

        assertThat(templateRepository.findByCompanyId(cilj))
                .as("prijenos je stvorio kopiju").hasSize(1);

        companyService.delete(cilj);   // arhiviraj
        companyService.purge(cilj);    // isprazni trajno

        assertThat(templateRepository.findByCompanyId(cilj))
                .as("nakon praznjenja firme ne smije ostati nijedan obrazac")
                .isEmpty();
        assertThat(companyRepository.findById(cilj))
                .as("firma je obrisana").isEmpty();
    }

    /** Kontrola: isti slijed, ali obrazac je nastao obicnim spremanjem, ne prijenosom. */
    @Test
    @DisplayName("kontrola - obrazac nastao mimo prijenosa se uredno briše")
    void purgeRemovesOrdinaryTemplate() {
        Template obicni = new Template(cilj, "REPRO obicni");
        obicni.setDefinitions(List.of(new ColumnEntry("ime", "string", null)));
        templateRepository.save(obicni);

        companyService.delete(cilj);
        companyService.purge(cilj);

        assertThat(templateRepository.findByCompanyId(cilj)).isEmpty();
    }

    /**
     * Isti oblik koda ({@code ucitaj pa deleteAll(entiteti)}) brise i zapise, sifrarnike i
     * korisnike. Kad je na obrascima tiho zakazao, mora se provjeriti i za ostale - inace se
     * popravi simptom, a kvar ostane na tri druga mjesta.
     */
    @Test
    @DisplayName("pražnjenje firme ne ostavlja ni zapise, ni šifrarnike, ni korisnike")
    void purgeRemovesEverythingElseToo() {
        Template obrazac = new Template(cilj, "REPRO sve");
        obrazac.setDefinitions(List.of(new ColumnEntry("ime", "string", null)));
        Long obrazacId = templateRepository.save(obrazac).getId();

        SurveyResult zapis = new SurveyResult();
        zapis.setCompanyId(cilj);
        zapis.setTemplateId(obrazacId);
        zapis.setData(new java.util.HashMap<>(java.util.Map.of("ime", "Ana")));
        surveyRepository.save(zapis);

        Codebook sifrarnik = codebookRepository.save(
                new Codebook("REPRO gradovi", CodebookScope.TENANT, cilj));
        codebookItemRepository.save(new CodebookItem(sifrarnik.getId(), "ZG", "Zagreb", true, 0));

        Attachment prilog = attachmentRepository.save(new Attachment(cilj, obrazacId,
                zapis.getId(), "dok", "dokaz.txt", "text/plain", 5, "repro"));
        attachmentContentRepository.save(new AttachmentContent(prilog.getId(),
                "dokaz".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        recordChangeRepository.save(new RecordChange(zapis.getId(), obrazacId, cilj, "ime",
                "staro", "novo", "repro", java.time.Instant.now()));
        // po id-u, ne po findByTemplateIdAndColumnKey: ta metoda nosi pesimisticku bravu i
        // trazi otvorenu transakciju, a ovaj test je namjerno nema
        Long brojacId = sequenceRepository.save(new ColumnSequence(obrazacId, "broj", 7L)).getId();

        User korisnik = new User();
        korisnik.setUsername("repro-" + System.nanoTime());
        korisnik.setPasswordHash("x");
        korisnik.setRole(Role.USER);
        korisnik.setCompanyId(cilj);
        userRepository.save(korisnik);

        // Prvo se potvrdi da SVE postoji. Bez ovoga bi test prosao i da se nesto nije ni
        // spremilo - provjere ispod su tvrdnje o praznini, a prazno je i ono cega nema.
        assertThat(templateRepository.findByCompanyId(cilj)).as("priprema: obrasci").hasSize(1);
        assertThat(surveyRepository.findByCompanyId(cilj)).as("priprema: zapisi").hasSize(1);
        assertThat(codebookRepository.findByCompanyId(cilj)).as("priprema: sifrarnici").hasSize(1);
        assertThat(userRepository.findByCompanyId(cilj)).as("priprema: korisnici").hasSize(1);
        assertThat(attachmentRepository.findByTemplateIdOrderByUploadedAtAsc(obrazacId))
                .as("priprema: prilozi").hasSize(1);
        assertThat(attachmentContentRepository.findById(prilog.getId()))
                .as("priprema: sadrzaj priloga").isPresent();
        assertThat(recordChangeRepository.findBySurveyIdOrderByChangedAtDescIdDesc(zapis.getId()))
                .as("priprema: povijest").hasSize(1);
        assertThat(sequenceRepository.findById(brojacId)).as("priprema: brojac").isPresent();

        companyService.delete(cilj);
        companyService.purge(cilj);

        assertThat(templateRepository.findByCompanyId(cilj)).as("obrasci").isEmpty();
        assertThat(surveyRepository.findByCompanyId(cilj)).as("zapisi").isEmpty();
        assertThat(codebookRepository.findByCompanyId(cilj)).as("sifrarnici").isEmpty();
        assertThat(userRepository.findByCompanyId(cilj)).as("korisnici").isEmpty();
        assertThat(codebookItemRepository.findByCodebookIdOrderBySortOrderAsc(sifrarnik.getId()))
                .as("stavke sifrarnika").isEmpty();
        // Prilog je najskuplji ostatak: uz redak nosi i datoteku u pg_largeobject, do koje
        // nakon brisanja retka vise ne vodi nijedan put - ni za onoga tko bi je htio maknuti.
        assertThat(attachmentRepository.findByTemplateIdOrderByUploadedAtAsc(obrazacId))
                .as("prilozi").isEmpty();
        assertThat(attachmentContentRepository.findById(prilog.getId()))
                .as("sadrzaj priloga").isEmpty();
        assertThat(recordChangeRepository.findBySurveyIdOrderByChangedAtDescIdDesc(zapis.getId()))
                .as("povijest izmjena").isEmpty();
        assertThat(sequenceRepository.findById(brojacId)).as("brojac rednih brojeva").isEmpty();
    }
}
