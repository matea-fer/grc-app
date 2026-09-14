package com.example.demo.service;

import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tko koristi sifrarnik - jedino mjesto koje na to pitanje odgovara.
 *
 * Postoji zato sto isto pitanje postavljaju dvije razlicite radnje, a odgovor im nije
 * isti: brisanje CIJELOG sifrarnika smeta vec i sam stupac koji na njega pokazuje (makar
 * u njega nista nije upisano), dok brisanje jedne STAVKE smeta tek ako je bas ta sifra
 * negdje upisana. Da svaki servis to racuna za sebe, dvije bi provjere s vremenom
 * razisle - a upravo ta razlika je smisao pravila.
 *
 * Doseg pretrazivanja je doseg sifrarnika: globalni sifrarnik smije koristiti bilo koja
 * firma, pa se gledaju SVI obrasci; firmin moze koristiti samo ta firma. Gledati manje
 * znacilo bi dopustiti brisanje necega sto tuda firma jos koristi.
 */
@Component
public class CodebookUsage {

    private final TemplateRepository templateRepository;
    private final SurveyResultRepository surveyRepository;

    public CodebookUsage(TemplateRepository templateRepository, SurveyResultRepository surveyRepository) {
        this.templateRepository = templateRepository;
        this.surveyRepository = surveyRepository;
    }

    /** Stupac koji se poziva na sifrarnik, uz obrazac u kojem stoji. */
    public record ColumnUse(Template template, ColumnEntry column) {

        /** Mjesto stupca za poruku korisniku: obrazac po nazivu, stupac po nazivu za korisnika. */
        public String describe() {
            return "obrazac \"" + template.getName() + "\", stupac \"" + column.displayLabel() + "\"";
        }
    }

    /** Svi stupci koji pokazuju na ovaj sifrarnik, u dosegu u kojem ga se uopce smije koristiti. */
    public List<ColumnUse> columnsUsing(Codebook codebook) {
        List<ColumnUse> uses = new ArrayList<>();
        for (Template template : candidateTemplates(codebook)) {
            List<ColumnEntry> definitions = template.getDefinitions();
            if (definitions == null) {
                continue;
            }
            for (ColumnEntry column : definitions) {
                if ("codebook".equals(column.type()) && codebook.getId().equals(column.codebookId())) {
                    uses.add(new ColumnUse(template, column));
                }
            }
        }
        return uses;
    }

    /**
     * Gdje je sifra pronadena.
     *
     * Razlog nije kozmetika: odreduje savjet koji korisnik dobije. Sifru upisanu u zapise
     * se rjesava ISKLJUCIVANJEM stavke (stari zapisi ostaju citljivi), a sifru koja je
     * necija zadana vrijednost time se NE rjesava - stupac bi i dalje predpopunjavao
     * iskljucenu stavku, pa bi prvo sljedece uredivanje tog stupca palo. Nju treba maknuti
     * sa stupca.
     *
     * @param where          opis mjesta: sifra, obrazac i stupac
     * @param asDefaultValue je li nadena kao zadana vrijednost stupca (inace: upisana u zapis)
     */
    public record CodeUse(String where, boolean asDefaultValue) {
    }

    /**
     * Prva sifra iz popisa koja se stvarno negdje koristi.
     *
     * Vraca se prvi nalaz, a ne svi: korisniku treba jedan konkretan dokaz da sifra nije
     * neiskoristena, a ne popis svih zapisa koji je nose.
     *
     * Zadane vrijednosti se gledaju PRVE jer stoje u vec ucitanim definicijama stupaca -
     * sifra koja je necija zadana vrijednost tako ne kosta nijedan upit nad zapisima.
     *
     * @return nalaz, ili null ako se nijedna sifra iz popisa nigdje ne koristi
     */
    public CodeUse firstCodeInUse(Codebook codebook, Set<String> codes) {
        if (codes.isEmpty()) {
            return null;
        }
        List<ColumnUse> uses = columnsUsing(codebook);

        for (ColumnUse use : uses) {
            String defaultValue = use.column().defaultValue();
            String code = defaultValue == null ? null : defaultValue.trim();
            if (code != null && codes.contains(code)) {
                return new CodeUse("šifra \"" + code + "\" (" + use.describe() + ")", true);
            }
        }

        for (ColumnUse use : uses) {
            String columnKey = use.column().key();
            for (SurveyResult survey : surveyRepository.findByTemplateId(use.template().getId())) {
                Map<String, Object> data = survey.getData();
                Object value = data == null ? null : data.get(columnKey);
                if (value instanceof String code && codes.contains(code)) {
                    return new CodeUse("šifra \"" + code + "\" (" + use.describe() + ")", false);
                }
            }
        }
        return null;
    }

    /**
     * Obrasci koji ovaj sifrarnik uopce smiju koristiti.
     *
     * Globalni je vidljiv svima, pa se ne smije ograniciti na firmu iz konteksta: brisao bi
     * ga globalni administrator, a koristi ga firma koju on trenutno nije odabrao.
     */
    private List<Template> candidateTemplates(Codebook codebook) {
        return codebook.getScope() == CodebookScope.GLOBAL
                ? templateRepository.findAll()
                : templateRepository.findByCompanyId(codebook.getCompanyId());
    }
}
