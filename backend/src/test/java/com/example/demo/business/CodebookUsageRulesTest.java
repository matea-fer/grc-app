package com.example.demo.business;

import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.CodebookUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - "koristi li se ovaj sifrarnik" ima DVA razlicita odgovora, ovisno o tome
 * sto se brise.
 *
 * Brisanje cijelog sifrarnika smeta vec i sam stupac koji na njega pokazuje, makar u njega
 * jos nista nije upisano: stupac bi ostao tvrditi da mu vrijednosti dolaze iz necega cega
 * nema. Brisanje jedne stavke smeta tek ako se bas ta sifra koristi - upisana u zapis ili
 * postavljena kao zadana vrijednost stupca; inace nema razloga cuvati stavku koju nitko ne
 * koristi.
 *
 * Doseg pretrazivanja je doseg sifrarnika, i to je pravilo koje se lako previdi: globalni
 * sifrarnik brise globalni administrator, a koristi ga firma koju on u tom trenutku nije
 * odabrao. Gledati samo firmu iz konteksta znacilo bi dopustiti brisanje pod nogama tude firme.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodebookUsageRulesTest {

    private static final Long CODEBOOK_ID = 7L;
    private static final Long OWN_COMPANY = 3L;

    @Mock
    private TemplateRepository templateRepository;

    @Mock
    private SurveyResultRepository surveyRepository;

    private CodebookUsage usage() {
        return new CodebookUsage(templateRepository, surveyRepository);
    }

    private Codebook codebook(CodebookScope scope, Long companyId) {
        Codebook codebook = new Codebook("Gradovi", scope, companyId);
        codebook.setId(CODEBOOK_ID);
        return codebook;
    }

    private Template template(Long id, Long companyId, ColumnEntry... columns) {
        Template template = new Template(companyId, "Obrazac " + id);
        template.setId(id);
        template.setDefinitions(new ArrayList<>(List.of(columns)));
        return template;
    }

    private SurveyResult survey(Long id, Long templateId, Map<String, Object> data) {
        SurveyResult survey = new SurveyResult();
        survey.setId(id);
        survey.setCompanyId(OWN_COMPANY);
        survey.setTemplateId(templateId);
        survey.setData(new HashMap<>(data));
        return survey;
    }

    // ===================== KOJI STUPCI POKAZUJU NA SIFRARNIK =====================

    @Test
    @DisplayName("nalazi samo stupce koji pokazuju na taj šifrarnik")
    void findsOnlyColumnsPointingAtThisCodebook() {
        when(templateRepository.findByCompanyId(OWN_COMPANY)).thenReturn(List.of(template(1L, OWN_COMPANY,
                new ColumnEntry("grad", "codebook", CODEBOOK_ID),
                new ColumnEntry("status", "codebook", 99L),
                new ColumnEntry("ime", "string", null))));

        List<CodebookUsage.ColumnUse> uses = usage().columnsUsing(codebook(CodebookScope.TENANT, OWN_COMPANY));

        assertThat(uses).singleElement()
                .satisfies(use -> assertThat(use.column().key()).isEqualTo("grad"));
    }

    /**
     * Globalni sifrarnik smiju koristiti sve firme, pa se mora pretraziti sve. Da se gledala
     * samo firma iz konteksta, administrator bi mogao obrisati sifrarnik koji tuda firma
     * upravo koristi - i to bez ijedne poruke.
     */
    @Test
    @DisplayName("globalni šifrarnik se traži po SVIM obrascima, ne samo po jednoj firmi")
    void globalCodebookIsSearchedAcrossAllCompanies() {
        when(templateRepository.findAll()).thenReturn(List.of(
                template(1L, OWN_COMPANY, new ColumnEntry("ime", "string", null)),
                template(2L, 99L, new ColumnEntry("grad", "codebook", CODEBOOK_ID))));

        List<CodebookUsage.ColumnUse> uses = usage().columnsUsing(codebook(CodebookScope.GLOBAL, null));

        assertThat(uses).singleElement()
                .satisfies(use -> assertThat(use.template().getId()).isEqualTo(2L));
        verify(templateRepository, never()).findByCompanyId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("šifrarnik firme se traži samo po obrascima te firme")
    void tenantCodebookIsSearchedWithinItsCompanyOnly() {
        when(templateRepository.findByCompanyId(OWN_COMPANY)).thenReturn(List.of());

        assertThat(usage().columnsUsing(codebook(CodebookScope.TENANT, OWN_COMPANY))).isEmpty();

        verify(templateRepository, never()).findAll();
    }

    // ===================== JE LI SIFRA UPISANA U ZAPISE =====================

    @Test
    @DisplayName("šifra upisana u zapis se prijavljuje, uz obrazac i stupac")
    void writtenCodeIsReported() {
        when(templateRepository.findByCompanyId(OWN_COMPANY)).thenReturn(List.of(
                template(1L, OWN_COMPANY, new ColumnEntry("grad", "codebook", CODEBOOK_ID))));
        when(surveyRepository.findByTemplateId(1L)).thenReturn(List.of(
                survey(10L, 1L, Map.of("grad", "ST"))));

        CodebookUsage.CodeUse found = usage().firstCodeInUse(codebook(CodebookScope.TENANT, OWN_COMPANY), Set.of("ST"));

        assertThat(found).isNotNull();
        assertThat(found.where()).contains("ST").contains("grad");
        assertThat(found.asDefaultValue()).isFalse();
    }

    /**
     * Rupa zatvorena 31.07.2026.: prije se gledalo samo je li šifra UPISANA u zapis, pa se
     * stavka koja je nečija zadana vrijednost dala obrisati. Stupac bi ostao predpopunjavati
     * šifru koje nema i prvi sljedeći unos bi pao - a nijedan zapis je pritom nije nosio.
     */
    @Test
    @DisplayName("šifra koja je zadana vrijednost stupca se prijavljuje i bez ijednog zapisa")
    void defaultValueCodeIsReportedWithoutAnySurvey() {
        ColumnEntry withDefault = new ColumnEntry("grad", "codebook", CODEBOOK_ID,
                null, false, false, "ST", false);
        when(templateRepository.findByCompanyId(OWN_COMPANY))
                .thenReturn(List.of(template(1L, OWN_COMPANY, withDefault)));

        CodebookUsage.CodeUse found = usage().firstCodeInUse(codebook(CodebookScope.TENANT, OWN_COMPANY), Set.of("ST"));

        assertThat(found).isNotNull();
        assertThat(found.where()).contains("ST").contains("grad");
        // savjet se razlikuje: isključivanje ovdje ne pomaže, zadanu vrijednost treba maknuti
        assertThat(found.asDefaultValue()).isTrue();
    }

    /** Zadane vrijednosti stoje u već učitanim definicijama - nema razloga dirati zapise. */
    @Test
    @DisplayName("nalaz u zadanoj vrijednosti ne čita zapise")
    void defaultValueHitSkipsSurveyLookup() {
        ColumnEntry withDefault = new ColumnEntry("grad", "codebook", CODEBOOK_ID,
                null, false, false, "ST", false);
        when(templateRepository.findByCompanyId(OWN_COMPANY))
                .thenReturn(List.of(template(1L, OWN_COMPANY, withDefault)));

        usage().firstCodeInUse(codebook(CodebookScope.TENANT, OWN_COMPANY), Set.of("ST"));

        verify(surveyRepository, never()).findByTemplateId(org.mockito.ArgumentMatchers.any());
    }

    /** Zadana vrijednost DRUGE šifre ne smije zaključati brisanje ove. */
    @Test
    @DisplayName("zadana vrijednost druge šifre ne blokira brisanje")
    void unrelatedDefaultValueDoesNotBlock() {
        ColumnEntry withDefault = new ColumnEntry("grad", "codebook", CODEBOOK_ID,
                null, false, false, "ZG", false);
        when(templateRepository.findByCompanyId(OWN_COMPANY))
                .thenReturn(List.of(template(1L, OWN_COMPANY, withDefault)));
        when(surveyRepository.findByTemplateId(1L)).thenReturn(List.of());

        assertThat(usage().firstCodeInUse(codebook(CodebookScope.TENANT, OWN_COMPANY), Set.of("ST"))).isNull();
    }

    /**
     * Stupac koji na sifrarnik pokazuje, ali u koji ta sifra jos nije upisana, NE blokira
     * brisanje stavke. To je cijela razlika izmedu dva mjerila: stavku koju nitko ne koristi
     * nema razloga cuvati.
     */
    @Test
    @DisplayName("stupac koji na šifrarnik pokazuje, ali šifru ne sadrži, ne blokira brisanje stavke")
    void unusedCodeIsNotReported() {
        when(templateRepository.findByCompanyId(OWN_COMPANY)).thenReturn(List.of(
                template(1L, OWN_COMPANY, new ColumnEntry("grad", "codebook", CODEBOOK_ID))));
        when(surveyRepository.findByTemplateId(1L)).thenReturn(List.of(
                survey(10L, 1L, Map.of("grad", "ZG"))));

        assertThat(usage().firstCodeInUse(codebook(CodebookScope.TENANT, OWN_COMPANY), Set.of("ST"))).isNull();
    }

    /** Bez ijedne šifre za provjeru nema ni razloga čitati zapise. */
    @Test
    @DisplayName("prazan popis šifara ne dira zapise")
    void emptyCodeSetSkipsSurveys() {
        assertThat(usage().firstCodeInUse(codebook(CodebookScope.TENANT, OWN_COMPANY), Set.of())).isNull();

        verify(surveyRepository, never()).findByTemplateId(org.mockito.ArgumentMatchers.any());
    }
}
