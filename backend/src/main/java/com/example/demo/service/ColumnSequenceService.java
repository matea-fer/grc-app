package com.example.demo.service;

import com.example.demo.model.ColumnSequence;
import com.example.demo.model.SurveyResult;
import com.example.demo.repository.ColumnSequenceRepository;
import com.example.demo.repository.SurveyResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Dodjeljuje redne brojeve stupcima s ukljucenim {@code autoIncrement}.
 *
 * Vidi {@link ColumnSequence} za razlog zbog kojeg brojac postoji kao vlastita tablica
 * umjesto sekvence u bazi.
 *
 * Brojac se namjerno NE brise ni pri brisanju stupca, ni pri preimenovanju, ni pri
 * brisanju obrasca. Razlog nije nemar:
 *   - brojac koji je ostao iza obrisanog stupca ne smeta nikome (novi obrazac dobiva novi
 *     id, pa se ne moze sudariti), a ako se stupac istog naziva vrati, numeriranje nastavlja
 *     ondje gdje je stalo - sto je sigurnije od ponovnog dijeljenja vec upotrijebljenih brojeva;
 *   - preimenovanje stupca se samo popravi: brojac pod novim nazivom nastaje pri prvom
 *     sljedecem unosu, a {@link #firstFreeValue} ga postavi iznad vrijednosti koje je
 *     migracija vec prenijela pod novi kljuc.
 */
@Service
public class ColumnSequenceService {
    private static final Logger log = LoggerFactory.getLogger(ColumnSequenceService.class);

    private final ColumnSequenceRepository repository;
    private final SurveyResultRepository surveyRepository;

    public ColumnSequenceService(ColumnSequenceRepository repository, SurveyResultRepository surveyRepository) {
        this.repository = repository;
        this.surveyRepository = surveyRepository;
    }

    /**
     * Sljedeci redni broj za stupac, uz pomak brojaca.
     *
     * Mora teci unutar transakcije spremanja zapisa: brava na retku brojaca vrijedi do
     * kraja transakcije, pa bi zasebna transakcija otpustila bravu prije nego je zapis
     * spremljen - i drugi bi unos u meduvremenu dobio isti broj.
     */
    @Transactional
    public long next(Long templateId, String columnKey) {
        ColumnSequence sequence = repository.findByTemplateIdAndColumnKey(templateId, columnKey)
                .orElseGet(() -> new ColumnSequence(templateId, columnKey, firstFreeValue(templateId, columnKey)));

        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1);
        repository.save(sequence);
        return value;
    }

    /**
     * Prvi broj koji jos nije upisan u zapise ovog stupca.
     *
     * Stupac je mogao imati rucno upisane brojeve prije nego je postao automatski, pa bi
     * krenuti od jedan znacilo dodijeliti vrijednost koja vec postoji - a takav stupac je
     * najcesce ujedno i jedinstven, pa bi prvi sljedeci unos pao. Racuna se jednom, pri
     * stvaranju brojaca; nakon toga brojac zna gdje je stao.
     */
    private long firstFreeValue(Long templateId, String columnKey) {
        long max = 0;
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData();
            Object value = data == null ? null : data.get(columnKey);
            if (value instanceof Number number) {
                max = Math.max(max, new BigDecimal(number.toString()).longValue());
            }
        }
        log.info("Brojac za stupac {} obrasca id={} krece od {}", columnKey, templateId, max + 1);
        return max + 1;
    }
}
