package com.example.demo.dto;

import java.util.List;

/**
 * Sto ce prijenos napraviti - ili sto je napravio.
 *
 * Isti oblik sluzi za oba: najava (koja nista ne mijenja) i izvjestaj nakon izvrsenja.
 * Namjerno je jedan tip, jer bi dva ostavila prostor da se najava i stvarni ishod raziduju -
 * a cijela je svrha najave u tome da su isti.
 *
 * @param templates         obrasci koji nastaju u ciljnoj firmi
 * @param codebooks         sifrarnici koji nastaju ili se ponovno koriste
 * @param brokenReferences  veze koje se prekidaju jer im cilj nije u odabiru
 */
public record TransferPlanResponse(
        List<TemplatePlan> templates,
        List<CodebookPlan> codebooks,
        List<BrokenReference> brokenReferences
) {

    /**
     * Jedan obrazac u prijenosu.
     *
     * @param sourceId   id obrasca u izvornoj firmi
     * @param sourceName naziv kakav ima ondje
     * @param targetName naziv pod kojim nastaje - razlicit od izvornog ako ga je ciljna
     *                   firma vec imala zauzetog
     * @param renamed    je li naziv morao biti promijenjen
     * @param columnCount koliko stupaca nosi sa sobom
     */
    public record TemplatePlan(Long sourceId, String sourceName, String targetName,
                               boolean renamed, int columnCount) {
    }

    /**
     * Jedan sifrarnik na koji kopirani stupci pokazuju.
     *
     * @param name      naziv sifrarnika
     * @param scope     GLOBAL ili TENANT
     * @param reused    true = ciljna firma ga vec ima pod tim nazivom, pa se stupci vezu na
     *                  postojeci; false = stvara se nov, sa stavkama
     * @param itemCount koliko stavki ima (kod ponovno koristenog: koliko ih ima postojeci)
     */
    public record CodebookPlan(String name, String scope, boolean reused, int itemCount) {
    }

    /**
     * Veza koja ne moze preziviti prijenos.
     *
     * Nastaje kad stupac tipa {@code reference} pokazuje na obrazac koji NIJE u odabiru: u
     * ciljnoj firmi tog obrasca nema, pa veza nema na sto pokazivati. Stupac se prenosi, ali
     * bez cilja - i to mora pisati u najavi, jer se poslije ne vidi da je nesto izgubljeno.
     *
     * @param templateName obrazac u kojem stupac stoji
     * @param columnKey    kljuc stupca
     * @param targetName   naziv obrasca na koji je pokazivao
     */
    public record BrokenReference(String templateName, String columnKey, String targetName) {
    }
}
