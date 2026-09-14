package com.example.demo.config;

import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.SurveyResult;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.repository.SurveyResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prebacuje stupce ukinutih tipova ("boolean" i "select") na stupac vezan na sifrarnik.
 *
 * Zasto uopce postoji: oba tipa su nosila dopusteni popis vrijednosti U SEBI - boolean
 * precutno (true/false), select kroz {@code options}. Sad taj popis zivi u sifrarniku,
 * pa stari stupci nemaju na sto pokazati. Bez migracije bi ostali u bazi s tipom koji
 * vise ne postoji: Editor ih ne bi znao nacrtati, a vrijednosti u zapisima bi ostale
 * kao mrtav podatak.
 *
 * <p><b>Cita sirovi jsonb, ne {@code ColumnEntry}.</b> To je namjerno i bitno: migracija
 * mora vidjeti {@code options}, polje kojeg u danasnjem modelu vise NEMA. Kroz entitet
 * bi ga Jackson tiho odbacio prije nego sto ga itko stigne procitati, i popis dopustenih
 * vrijednosti bi nestao upravo u koraku koji ga treba spasiti. Migracija zato govori
 * jezikom baze u tom trenutku, a ne jezikom danasnjeg koda.
 *
 * <p>Sto radi:
 * <ul>
 *   <li><b>boolean</b> -> globalni sifrarnik "Da/Ne" (sifre DA i NE), a u zapisima
 *       {@code true -> "DA"}, {@code false -> "NE"}. Sifrarnik se stvara samo ako
 *       takvog stupca stvarno ima - inace bi svaka nova baza dobila sifrarnik koji
 *       nitko nije trazio.</li>
 *   <li><b>select</b> -> novi sifrarnik firme, nazvan po stupcu, sa stavkama iz
 *       {@code options}. Sifra je sama vrijednost, pa vec upisani zapisi ostaju
 *       tocni bez ijedne izmjene.</li>
 * </ul>
 *
 * <p>Sam sebe preskace: nakon prolaza vise nema stupca ni jednog ni drugog tipa, pa
 * sljedece pokretanje ne nade nista. Zato ne treba zastavicu "je li vec izvrseno".
 */
@Component
public class LegacyColumnTypeMigration implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyColumnTypeMigration.class);

    private static final String YES_NO_CODEBOOK = "Da/Ne";
    private static final String YES_CODE = "DA";
    private static final String NO_CODE = "NE";

    private final JdbcTemplate jdbc;
    private final CodebookRepository codebookRepository;
    private final CodebookItemRepository codebookItemRepository;
    private final SurveyResultRepository surveyRepository;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public LegacyColumnTypeMigration(JdbcTemplate jdbc,
                                     CodebookRepository codebookRepository,
                                     CodebookItemRepository codebookItemRepository,
                                     SurveyResultRepository surveyRepository) {
        this.jdbc = jdbc;
        this.codebookRepository = codebookRepository;
        this.codebookItemRepository = codebookItemRepository;
        this.surveyRepository = surveyRepository;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, company_id, definitions::text as definitions from form_template");

        int migratedColumns = 0;
        for (Map<String, Object> row : rows) {
            migratedColumns += migrateTemplate(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("company_id")).longValue(),
                    (String) row.get("definitions"));
        }

        if (migratedColumns > 0) {
            log.warn("Migracija ukinutih tipova stupaca: prebaceno {} stupac/stupaca na sifrarnik.", migratedColumns);
        }
    }

    /** @return broj stupaca ovog obrasca koji su prebaceni */
    private int migrateTemplate(Long templateId, Long companyId, String definitionsJson) throws Exception {
        if (definitionsJson == null || definitionsJson.isBlank()) {
            return 0;
        }
        JsonNode parsed = objectMapper.readTree(definitionsJson);
        if (!(parsed instanceof ArrayNode definitions)) {
            return 0;
        }

        int migrated = 0;
        for (JsonNode node : definitions) {
            if (!(node instanceof ObjectNode column)) {
                continue;
            }
            String type = column.path("type").asString("");
            if ("boolean".equals(type)) {
                migrateBooleanColumn(templateId, column);
                migrated++;
            } else if ("select".equals(type)) {
                migrateSelectColumn(templateId, companyId, column);
                migrated++;
            }
        }

        if (migrated > 0) {
            // version se podize rucno: redak se mijenja mimo Hibernatea, a @Version stupac
            // mora ostati u skladu s njim - inace bi prva sljedeca izmjena kroz aplikaciju
            // mislila da radi nad zastarjelim retkom.
            jdbc.update("update form_template set definitions = cast(? as jsonb), version = version + 1 where id = ?",
                    objectMapper.writeValueAsString(definitions), templateId);
        }
        return migrated;
    }

    /**
     * Da/Ne stupac -> stupac vezan na globalni sifrarnik "Da/Ne".
     *
     * Zadana vrijednost je bila tekst "true"/"false", pa se prevodi u sifru; sve ostalo
     * (npr. prazno) se ostavlja kakvo je - {@code null} default je i dalje "nema defaulta".
     */
    private void migrateBooleanColumn(Long templateId, ObjectNode column) {
        Codebook yesNo = yesNoCodebook();
        column.put("type", "codebook");
        column.put("codebookId", yesNo.getId());
        column.remove("options");

        JsonNode defaultValue = column.path("defaultValue");
        if (defaultValue.isString()) {
            String text = defaultValue.asString().trim();
            if ("true".equalsIgnoreCase(text)) {
                column.put("defaultValue", YES_CODE);
            } else if ("false".equalsIgnoreCase(text)) {
                column.put("defaultValue", NO_CODE);
            }
        }

        int changed = rewriteValues(templateId, column.path("key").asString(), value -> {
            if (value instanceof Boolean flag) {
                return flag ? YES_CODE : NO_CODE;
            }
            return value;
        });
        log.info("Stupac \"{}\" (obrazac id={}) prebacen s Da/Ne na sifrarnik id={}; prevedeno {} vrijednosti.",
                column.path("key").asString(), templateId, yesNo.getId(), changed);
    }

    /**
     * Padajuci izbornik -> stupac vezan na novi sifrarnik firme, sastavljen od njegovih
     * dotadasnjih {@code options}.
     *
     * Sifra stavke je sama vrijednost, a ne nova oznaka: u zapisima vec stoji ta vrijednost,
     * pa bi joj svaka druga sifra zahtijevala i prepisivanje podataka. Stupac bez upotrebljivih
     * vrijednosti postaje obican tekst - sifrarnik bez ijedne stavke ne bi dopustio nikakav unos.
     */
    private void migrateSelectColumn(Long templateId, Long companyId, ObjectNode column) {
        String columnKey = column.path("key").asString();
        List<String> options = new ArrayList<>();
        for (JsonNode option : column.path("options")) {
            String text = option.asString("").trim();
            if (!text.isEmpty() && !options.contains(text)) {
                options.add(text);
            }
        }
        column.remove("options");

        if (options.isEmpty()) {
            column.put("type", "string");
            log.warn("Stupac \"{}\" (obrazac id={}) bio je padajuci izbornik bez vrijednosti - postao je tekst.",
                    columnKey, templateId);
            return;
        }

        Codebook codebook = codebookRepository.save(
                new Codebook(freeTenantName(companyId, columnKey), CodebookScope.TENANT, companyId));
        int order = 0;
        for (String option : options) {
            CodebookItem item = new CodebookItem();
            item.setCodebookId(codebook.getId());
            item.setCode(option);
            item.setName(option);
            item.setActive(true);
            item.setSortOrder(order++);
            codebookItemRepository.save(item);
        }

        column.put("type", "codebook");
        column.put("codebookId", codebook.getId());
        log.info("Stupac \"{}\" (obrazac id={}) prebacen s padajuceg izbornika na novi sifrarnik \"{}\" (id={}, {} stavki).",
                columnKey, templateId, codebook.getName(), codebook.getId(), options.size());
    }

    /**
     * Globalni sifrarnik "Da/Ne", stvoren pri prvoj potrebi.
     *
     * Ako sifrarnik tog naziva vec postoji, preuzima se kakav jest - migracija ne dira
     * njegove stavke. Rucno napravljen "Da/Ne" je vjerojatno upravo ono sto korisnik zeli
     * da stari stupci koriste, a prepisivanje tudih stavki bi bilo gore od preuzimanja.
     */
    private Codebook yesNoCodebook() {
        return codebookRepository
                .findFirstByScopeAndCompanyIdIsNullAndNameIgnoreCase(CodebookScope.GLOBAL, YES_NO_CODEBOOK)
                .orElseGet(this::createYesNoCodebook);
    }

    private Codebook createYesNoCodebook() {
        Codebook codebook = codebookRepository.save(new Codebook(YES_NO_CODEBOOK, CodebookScope.GLOBAL, null));
        codebookItemRepository.save(item(codebook.getId(), YES_CODE, "Da", 0));
        codebookItemRepository.save(item(codebook.getId(), NO_CODE, "Ne", 1));
        log.warn("Stvoren globalni sifrarnik \"{}\" (id={}) za stupce koji su bili tipa Da/Ne.",
                YES_NO_CODEBOOK, codebook.getId());
        return codebook;
    }

    private CodebookItem item(Long codebookId, String code, String name, int sortOrder) {
        CodebookItem item = new CodebookItem();
        item.setCodebookId(codebookId);
        item.setCode(code);
        item.setName(name);
        item.setActive(true);
        item.setSortOrder(sortOrder);
        return item;
    }

    /** Naziv koji u toj firmi jos nije zauzet - inace bi migracija pala na provjeri naziva. */
    private String freeTenantName(Long companyId, String columnKey) {
        String base = columnKey.isBlank() ? "Šifrarnik" : columnKey;
        String candidate = base;
        int suffix = 2;
        while (codebookRepository.existsByScopeAndCompanyIdAndNameIgnoreCase(CodebookScope.TENANT, companyId, candidate)) {
            candidate = base + " " + suffix++;
        }
        return candidate;
    }

    /**
     * Prepisi vrijednost jednog kljuca u svim zapisima obrasca.
     *
     * @return broj zapisa u kojima se vrijednost stvarno promijenila
     */
    private int rewriteValues(Long templateId, String columnKey, java.util.function.UnaryOperator<Object> rewrite) {
        int changed = 0;
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData();
            if (data == null || !data.containsKey(columnKey)) {
                continue;
            }
            Object before = data.get(columnKey);
            Object after = rewrite.apply(before);
            if (java.util.Objects.equals(before, after)) {
                continue;
            }
            // novi Map, ne izmjena postojeceg: kod jsonb stupca Hibernate ne primijeti
            // pouzdano da je sadrzaj promijenjen u mjestu
            Map<String, Object> updated = new HashMap<>(data);
            updated.put(columnKey, after);
            survey.setData(updated);
            surveyRepository.save(survey);
            changed++;
        }
        return changed;
    }
}
