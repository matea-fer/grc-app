package com.example.demo.repository;

import com.example.demo.model.ColumnSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface ColumnSequenceRepository extends JpaRepository<ColumnSequence, Long> {

    /**
     * Brojac zakljucan za pisanje: drugi unos u isti stupac ceka da ovaj zavrsi.
     *
     * Bez zakljucavanja bi dva istovremena unosa procitala isti broj i oba ga upisala -
     * a redni broj koji se ponavlja nije redni broj. Kljucanje je pesimisticno jer je
     * sudar ovdje ocekivan (svi unosi u isti obrazac idu kroz isti redak), a ne rijedak.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ColumnSequence> findByTemplateIdAndColumnKey(Long templateId, String columnKey);

    /**
     * Brojaci svih obrazaca firme koja se trajno prazni.
     *
     * Inace se brojaci ne brisu (jedan redak nikome ne smeta i sam se popravi), ali ovdje
     * nestaje sve na sto pokazuju, pa bi ostali kao retci bez ijednog obrasca.
     */
    void deleteByTemplateIdIn(List<Long> templateIds);
}
