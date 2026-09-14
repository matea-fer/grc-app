package com.example.demo;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CodebookResponse;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.Role;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.service.CodebookService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provjera SHEME, ne poslovnih pravila - zato uz {@link DemoApplicationTests}, a ne
 * medu core/business testovima: jedini je test koji stvarno pise u bazu.
 *
 * Postoji zato sto je zasebna tablica (umjesto jsonb niza) odabrana bas zbog jednog
 * konkretnog jamstva: {@code (codebook_id, code)}. Ako to ogranicenje ne bi stvarno
 * postojalo u bazi, cijela odluka gubi smisao, a to se ne bi vidjelo nigdje drugdje -
 * duplikat bi tiho prosao.
 *
 * Baza se stvara s {@code ddl-auto=update}, pa ovaj test ujedno potvrduje da je
 * Hibernate ogranicenje uistinu napravio.
 */
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
class CodebookSchemaTest {

    @Autowired
    private CodebookRepository codebookRepository;

    @Autowired
    private CodebookItemRepository itemRepository;

    @Autowired
    private CodebookService codebookService;

    @Autowired
    private AuthContext authContext;

    @Autowired
    private TenantContext tenantContext;

    private final List<Long> createdCodebooks = new java.util.ArrayList<>();

    /** Pokreni dohvat kao globalni administrator koji je u traci odabrao zadanu firmu. */
    private <T> T asAdminOfCompany(Long companyId, java.util.function.Supplier<T> action) {
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
        tenantContext.set(companyId);
        try {
            return action.get();
        } finally {
            authContext.clear();
            tenantContext.clear();
        }
    }

    @AfterEach
    void cleanUp() {
        // deleteAll(popis) umjesto deleteByCodebookId: izvedeni deleteBy... trazi otvorenu
        // transakciju, a ovdje je svaki poziv repozitorija svoja. Isti nacin koristi i
        // TemplateService.delete.
        for (Long id : createdCodebooks) {
            itemRepository.deleteAll(itemRepository.findByCodebookIdOrderBySortOrderAsc(id));
            codebookRepository.deleteById(id);
        }
        createdCodebooks.clear();
    }

    private Codebook codebook(String name, CodebookScope scope, Long companyId) {
        Codebook saved = codebookRepository.save(new Codebook(name, scope, companyId));
        createdCodebooks.add(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("ista sifra dvaput u istom sifrarniku se odbija na razini baze")
    void duplicateCodeInSameCodebookIsRejected() {
        Codebook status = codebook("Status (test)", CodebookScope.TENANT, 7L);
        itemRepository.save(new CodebookItem(status.getId(), "OPEN", "Otvoren", true, 0));

        assertThatThrownBy(() ->
                itemRepository.saveAndFlush(new CodebookItem(status.getId(), "OPEN", "Duplikat", true, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ista sifra u DVA sifrarnika prolazi - jedinstvenost vrijedi unutar sifrarnika")
    void sameCodeInDifferentCodebooksIsAllowed() {
        Codebook acme = codebook("Status Acme (test)", CodebookScope.TENANT, 7L);
        Codebook beta = codebook("Status Beta (test)", CodebookScope.TENANT, 8L);

        itemRepository.save(new CodebookItem(acme.getId(), "OPEN", "Otvoren", true, 0));
        itemRepository.saveAndFlush(new CodebookItem(beta.getId(), "OPEN", "U obradi", true, 0));

        assertThat(itemRepository.findByCodebookIdOrderBySortOrderAsc(acme.getId()))
                .extracting(CodebookItem::getName)
                .containsExactly("Otvoren");
        assertThat(itemRepository.findByCodebookIdOrderBySortOrderAsc(beta.getId()))
                .extracting(CodebookItem::getName)
                .containsExactly("U obradi");
    }

    @Test
    @DisplayName("stavke se vracaju poredane po sortOrder, ne redoslijedom upisa")
    void itemsComeBackInSortOrder() {
        Codebook status = codebook("Redoslijed (test)", CodebookScope.TENANT, 7L);
        itemRepository.save(new CodebookItem(status.getId(), "C", "Treci", true, 2));
        itemRepository.save(new CodebookItem(status.getId(), "A", "Prvi", true, 0));
        itemRepository.saveAndFlush(new CodebookItem(status.getId(), "B", "Drugi", true, 1));

        assertThat(itemRepository.findByCodebookIdOrderBySortOrderAsc(status.getId()))
                .extracting(CodebookItem::getCode)
                .containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("broj stavki se dohvaca za cijeli popis odjednom, jednim upitom")
    void countsComeBackForWholeList() {
        Codebook prvi = codebook("Prvi (test)", CodebookScope.GLOBAL, null);
        Codebook drugi = codebook("Drugi (test)", CodebookScope.TENANT, 7L);
        itemRepository.save(new CodebookItem(prvi.getId(), "A", "A", true, 0));
        itemRepository.save(new CodebookItem(prvi.getId(), "B", "B", true, 1));
        itemRepository.saveAndFlush(new CodebookItem(drugi.getId(), "A", "A", true, 0));

        var counts = itemRepository.countByCodebookIds(List.of(prvi.getId(), drugi.getId()));

        assertThat(counts)
                .extracting(c -> c.getCodebookId() + ":" + c.getItemCount())
                .containsExactlyInAnyOrder(prvi.getId() + ":2", drugi.getId() + ":1");
    }

    /**
     * Ide kroz SERVIS, a ne izravno kroz repozitorij - jer se ovdje provjerava bas ono
     * sto je jednom vec puklo: prvo ucitavanje ekrana, dakle popis BEZ pojma za pretragu.
     *
     * Dok je upit imao oblik {@code :search is null or lower(concat(...))}, null parametar
     * je u bazu stizao bez tipa, Postgres bi za concat pretpostavio bytea i upit bi pukao s
     * "function lower(bytea) does not exist". Testovi koji su gadali repozitorij uvijek su
     * slali neki pojam, pa se to nije vidjelo - a upravo je prazna pretraga pravilo, a ne
     * iznimka.
     */
    @Test
    @DisplayName("popis bez pretrage vraca sve dostupno - to je prvo stanje ekrana")
    void listWithoutSearchTermWorks() {
        codebook("Drzave (test)", CodebookScope.GLOBAL, null);
        codebook("Statusi (test)", CodebookScope.TENANT, 7L);

        List<String> imena = asAdminOfCompany(7L, () -> codebookService.list(null, null)).stream()
                .map(CodebookResponse::name)
                .toList();

        assertThat(imena).contains("Drzave (test)", "Statusi (test)");
    }

    @Test
    @DisplayName("suzenje na doseg bez pretrage jednako radi")
    void listByScopeWithoutSearchTermWorks() {
        codebook("Drzave (test)", CodebookScope.GLOBAL, null);
        codebook("Statusi (test)", CodebookScope.TENANT, 7L);

        List<String> imena = asAdminOfCompany(7L, () -> codebookService.list(CodebookScope.GLOBAL, null)).stream()
                .map(CodebookResponse::name)
                .toList();

        assertThat(imena).contains("Drzave (test)").doesNotContain("Statusi (test)");
    }

    @Test
    @DisplayName("pretraga po dijelu naziva radi i ne razlikuje velika slova")
    void searchMatchesPartOfName() {
        codebook("Drzave (test)", CodebookScope.GLOBAL, null);
        codebook("Statusi (test)", CodebookScope.TENANT, 7L);

        List<String> imena = asAdminOfCompany(7L, () -> codebookService.list(null, "STATUS")).stream()
                .map(CodebookResponse::name)
                .toList();

        assertThat(imena).contains("Statusi (test)").doesNotContain("Drzave (test)");
    }

    @Test
    @DisplayName("postotak u pretrazi je obican znak, a ne dzoker")
    void percentInSearchIsLiteral() {
        codebook("Popust 50% (test)", CodebookScope.TENANT, 7L);
        codebook("Statusi (test)", CodebookScope.TENANT, 7L);

        List<String> imena = asAdminOfCompany(7L, () -> codebookService.list(null, "50%")).stream()
                .map(CodebookResponse::name)
                .toList();

        // da se % ne ekranira, uzorak "%50%%" bi nasao samo ono sto sadrzi "50" - ali
        // pretraga "%" bi tada nasla apsolutno sve, sto korisnik sigurno ne ocekuje
        assertThat(imena).containsExactly("Popust 50% (test)");
    }

    @Test
    @DisplayName("globalni sifrarnik je vidljiv i bez odabrane firme, firmin nije")
    void globalIsVisibleWithoutCompany() {
        Codebook global = codebook("Drzave (test)", CodebookScope.GLOBAL, null);
        Codebook tenant = codebook("Interni status (test)", CodebookScope.TENANT, 7L);

        // repozitorij prima GOTOV LIKE uzorak, u malim slovima - sastavlja ga servis
        List<Long> bezFirme = codebookRepository.findVisible(null, "%(test)%").stream().map(Codebook::getId).toList();
        List<Long> zaFirmu7 = codebookRepository.findVisible(7L, "%(test)%").stream().map(Codebook::getId).toList();
        List<Long> zaFirmu8 = codebookRepository.findVisible(8L, "%(test)%").stream().map(Codebook::getId).toList();

        assertThat(bezFirme).contains(global.getId()).doesNotContain(tenant.getId());
        assertThat(zaFirmu7).contains(global.getId(), tenant.getId());
        // tuda firma vidi globalni, ali ne i sifrarnik firme 7
        assertThat(zaFirmu8).contains(global.getId()).doesNotContain(tenant.getId());
    }
}
