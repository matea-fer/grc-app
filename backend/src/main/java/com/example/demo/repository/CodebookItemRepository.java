package com.example.demo.repository;

import com.example.demo.model.CodebookItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Stavke sifrarnika.
 *
 * Sve metode su kljucane po {@code codebookId}, i to je namjerno.
 *
 * Stavka u sebi ne nosi firmu - do nje se dolazi tek preko svog sifrarnika. Zato se
 * do stavki smije doci ISKLJUCIVO nakon sto je {@code CodebookService} potvrdio da je
 * taj sifrarnik dostupan pozivatelju, isto kao sto se do zapisa dolazi tek nakon
 * {@code TemplateService.requireOwned}.
 *
 * Ovdje NAMJERNO nema {@code findByCode}, {@code existsByCode} ni {@code deleteByCode}.
 * Takva metoda izgleda bezazleno, a Spring iz njezina naziva izvede upit bez
 * {@code codebook_id} - dakle preko svih firmi. Sifra je jedinstvena unutar jednog
 * sifrarnika, pa dvije firme legitimno mogu imati istu ("OPEN"); upit po samoj sifri
 * tada vrati tudu stavku, bez ijedne greske i nedeterministicki. Tko takav upit
 * ipak treba, neka ga napise svjesno - a ne da ga dobije time sto je metodu imenovao.
 */
public interface CodebookItemRepository extends JpaRepository<CodebookItem, Long> {

    List<CodebookItem> findByCodebookIdOrderBySortOrderAsc(Long codebookId);

    /**
     * Koliko stavki sifrarnik ima - za najavu prijenosa.
     *
     * Broji se upitom: najava ih samo prikazuje kao broj, a ucitati cijeli sifrarnik od
     * tisucu stavki da bi se ispisalo "1000" znaci povuci tisucu redaka nizasto.
     */
    long countByCodebookId(Long codebookId);

    void deleteByCodebookId(Long codebookId);

    /**
     * Broj stavki po sifrarniku, za cijeli popis odjednom.
     *
     * Popis sifrarnika prikazuje broj stavki uz svaki redak; da se broji po sifrarniku,
     * popis od N redaka bio bi N upita. Isti razlog zbog kojeg se u UserService nazivi
     * firmi dohvacaju jednom u mapu.
     */
    @Query("""
            select i.codebookId as codebookId, count(i) as itemCount
            from CodebookItem i
            where i.codebookId in :codebookIds
            group by i.codebookId
            """)
    List<CodebookItemCount> countByCodebookIds(@Param("codebookIds") Collection<Long> codebookIds);

    /** Redak rezultata gornjeg upita; nazivi getera moraju odgovarati aliasima u upitu. */
    interface CodebookItemCount {
        Long getCodebookId();

        long getItemCount();
    }
}
