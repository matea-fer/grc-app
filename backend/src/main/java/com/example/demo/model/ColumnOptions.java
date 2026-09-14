package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Postavke stupca koje ovise o njegovom TIPU.
 *
 * Stoje odvojeno od {@link ColumnEntry} iz jednog razloga: atributa koji vrijede samo
 * za jedan tip ima puno i stalno ih dolazi jos. Da su svi u samom {@code ColumnEntry},
 * on bi narastao na dvadesetak komponenti i svaki bi ga poziv morao nabrojati u
 * cijelosti - ukljucujuci desetak {@code null}-ova koji s tim stupcem nemaju veze.
 *
 * Polje koje se odabranog tipa ne tice je {@code null} i ne smije biti postavljeno -
 * to provjerava {@code ColumnDefinitionService.validateDefinition}. Razlog nije urednost
 * nego to sto bi postavka koju nitko ne cita ostala kao tiha neistina u shemi: stupac bi
 * tvrdio da ima raspon ili uzorak, a nista ga ne bi provodilo.
 *
 * Ovo NIJE @Entity - zivi kao dio jsonb dokumenta u {@link Template#getDefinitions()}.
 * Zato dodavanje nove postavke ne trazi migraciju baze: {@code @JsonIgnoreProperties}
 * cuva citanje starijih zapisa, a polje kojeg u njima nema Jackson popuni s null.
 *
 * @param autoIncrement  vrijednost dodjeljuje server pri spremanju (broj); korisnik je ne unosi
 * @param numberMode     "int" (cijeli broj) ili "float" (decimalni); null se ponasa kao "float"
 * @param min            najmanja dopustena vrijednost broja
 * @param max            najveca dopustena vrijednost broja
 * @param numberFormat   uzorak prikaza broja, npr. "#.##0,00"; ne dira spremljenu vrijednost
 * @param pattern        regularni izraz koji vrijednost mora zadovoljiti (tekst i broj)
 * @param dateMode       "date" (samo datum) ili "datetime" (datum i vrijeme); null = "date"
 * @param pickerMode     kako se bira iz sifrarnika: "dropdown", "checkbox" ili "popup"
 * @param multiple       smije li se odabrati vise stavki sifrarnika (vrijednost postaje niz sifara)
 * @param allowNewValues smije li se upisati vrijednost koje u sifrarniku jos nema (dodaje se u njega)
 * @param formula        izraz kojim se racuna vrijednost stupca, npr. "[cijena] * [kolicina]"
 * @param buttonLabel    natpis na gumbu kod stupca tipa "button"
 * @param buttonAction   sto gumb radi na klik: "history", "lock" ili "related"
 *                       (vidi {@link #ACTION_HISTORY}, {@link #ACTION_LOCK} i {@link #ACTION_RELATED})
 * @param targetTemplateId  obrazac na ciji zapis stupac tipa "reference" pokazuje; inace null.
 *                          U redak se sprema ID zapisa, ne njegov naziv - naziv se smije
 *                          mijenjati, id ne.
 * @param displayColumnKey  kljuc stupca ciljanog obrasca koji se prikazuje umjesto id-a
 * @param onTargetDelete    sto se dogada kad se ciljani zapis brise: "restrict" (zadano, brisanje
 *                          se odbija) ili "clear" (veza se prekine) - vidi {@link #ON_DELETE_RESTRICT}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnOptions(
        boolean autoIncrement,
        String numberMode,
        BigDecimal min,
        BigDecimal max,
        String numberFormat,
        String pattern,
        String dateMode,
        String pickerMode,
        boolean multiple,
        boolean allowNewValues,
        String formula,
        String buttonLabel,
        String buttonAction,
        Long targetTemplateId,
        String displayColumnKey,
        String onTargetDelete
) {

    /**
     * Gumb otvara revizijski trag retka - koje se polje kada i iz cega u sto promijenilo.
     *
     * Prva radnja, ali postavka je od pocetka NAZIV radnje, a ne zastavica: gumb bez
     * radnje je bio prazan drzac mjesta, a gumb s jednom zastavicom bi znacio da druga radnja
     * trazi novu postavku i novo grananje na svakom mjestu. Ovako druga radnja dodaje samo
     * jednu vrijednost - sto je {@link #ACTION_LOCK} i pokazao.
     */
    public static final String ACTION_HISTORY = "history";

    /**
     * Gumb zakljucava zapis: nakon toga se redak ne smije mijenjati ni brisati, niti mu se
     * smiju dirati prilozi. Otkljucati ga smije samo administrator (globalni ili firmin).
     *
     * Zakljucanost je stanje ZAPISA, a ne vrijednost stupca - stoji u vlastitim stupcima
     * tablice ({@code survey_result.locked_at}), ne u jsonb podacima. Da stoji u podacima,
     * mijenjala bi se istim PUT-om koji zakljucavanje treba sprijeciti, i nestala bi zajedno
     * sa stupcem kad bi se gumb maknuo iz sheme.
     */
    public static final String ACTION_LOCK = "lock";

    /**
     * Gumb otvara sve zapise koji pokazuju na ovaj zapis - iz bilo kojeg obrasca.
     *
     * Cita vezu UNATRAG. Smjer naprijed vec pokazuje sama celija referentnog stupca, pa bi ondje
     * gumb bio suvisan; "sto sve visi o ovom procesu" se drukcije ne moze dobiti, jer vezu drzi
     * samo dijete.
     *
     * NEMA nikakvih postavki, i to je namjerno: koji stupci na ovaj obrazac pokazuju vec pise u
     * shemama, pa bi trazenje da se to upise jos jednom rukom bilo ponavljanje koje se povrh
     * svega moze RAZICI sa stvarnoscu - stupac se preimenuje ili makne, a gumb i dalje tvrdi da
     * ga gleda. Popis skupina racuna {@code ReferenceLookup.groupsFor}.
     */
    public static final String ACTION_RELATED = "related";

    /** Brisanje ciljanog zapisa se odbija dok veze na njega postoje. Zadano ponasanje. */
    public static final String ON_DELETE_RESTRICT = "restrict";

    /** Ciljani zapis se brise, a veze na njega se prekinu - za neobavezne veze. */
    public static final String ON_DELETE_CLEAR = "clear";

    /** Stupac bez ijedne postavke ovisne o tipu - takvi su svi stupci nastali prije ovog recorda. */
    public static final ColumnOptions EMPTY =
            new ColumnOptions(false, null, null, null, null, null, null, null, false, false, null, null, null);

    /**
     * Postavke stupca kakve su bile prije nego su uvedene veze medu obrascima.
     *
     * Postoji iz istog razloga kao skraceni konstruktori u {@link ColumnEntry}: bez njega bi
     * svaki zatecen poziv morao nabrojati pet {@code null}-ova koji s tim stupcem nemaju veze.
     */
    public ColumnOptions(boolean autoIncrement, String numberMode, BigDecimal min, BigDecimal max,
                         String numberFormat, String pattern, String dateMode, String pickerMode,
                         boolean multiple, boolean allowNewValues, String formula,
                         String buttonLabel, String buttonAction) {
        this(autoIncrement, numberMode, min, max, numberFormat, pattern, dateMode, pickerMode,
                multiple, allowNewValues, formula, buttonLabel, buttonAction, null, null, null);
    }

    /** Iste postavke s drugim izrazom formule - za preimenovanje stupca na koji se poziva. */
    public ColumnOptions withFormula(String newFormula) {
        return new ColumnOptions(autoIncrement, numberMode, min, max, numberFormat, pattern,
                dateMode, pickerMode, multiple, allowNewValues, newFormula, buttonLabel,
                buttonAction, targetTemplateId, displayColumnKey, onTargetDelete);
    }

    /**
     * Iste postavke s drugim ciljem veze - za prijenos obrasca u drugu firmu, gdje isti
     * obrazac postoji pod drugim id-em. {@code null} znaci da veza ostaje bez cilja, jer
     * ciljni obrazac nije usao u prijenos.
     */
    public ColumnOptions withTargetTemplateId(Long newTargetTemplateId) {
        return new ColumnOptions(autoIncrement, numberMode, min, max, numberFormat, pattern,
                dateMode, pickerMode, multiple, allowNewValues, formula, buttonLabel,
                buttonAction, newTargetTemplateId, displayColumnKey, onTargetDelete);
    }

    /** Postavke, ili prazne kad ih stupac nema - da pozivatelji ne moraju svuda paziti na null. */
    public static ColumnOptions orEmpty(ColumnOptions options) {
        return options != null ? options : EMPTY;
    }

    /** Cijeli broj se trazi samo kad je izricito zatrazen; zateceni stupci su decimalni. */
    public boolean isInteger() {
        return "int".equals(numberMode);
    }

    /** Datum s vremenom se trazi samo kad je izricito zatrazen; zateceni stupci su samo datum. */
    public boolean isDateTime() {
        return "datetime".equals(dateMode);
    }

    /**
     * Smije li se ciljani zapis obrisati uz prekid veze.
     *
     * Zadano je NE (zabrana): postavka koja nije upisana - jer je stupac stariji od ove
     * znacajke ili ju je netko izostavio - ne smije znaciti da se veze tiho gube.
     */
    public boolean clearsOnTargetDelete() {
        return ON_DELETE_CLEAR.equals(onTargetDelete);
    }
}
