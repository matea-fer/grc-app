package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Brojac za stupac s automatskim rednim brojem (autoIncrement).
 *
 * Zasto uopce postoji: vrijednosti zapisa zive u jsonb dokumentu, a ne u stupcima tablice,
 * pa se sljedeci broj ne moze dobiti ni bazinom sekvencom ni jeftinim {@code MAX+1}. Racunanje
 * maksimuma po jsonb-u znacilo bi prolaz kroz sve zapise pri svakom unosu, i - gore od toga -
 * dva istovremena unosa procitala bi isti maksimum i dobila isti broj.
 *
 * Zato jedan redak po stupcu, koji se pri dodjeli zakljucava ({@code PESSIMISTIC_WRITE}):
 * drugi unos ceka da prvi zavrsi, pa isti broj ne moze biti dodijeljen dvaput.
 *
 * Brojac se pri prvoj upotrebi postavi IZNAD najvece vec upisane vrijednosti - stupac je
 * mogao postojati i prije nego je postao automatski, pa bi krenuti od jedan znacilo
 * sudarati se s onim sto je covjek rucno upisao.
 */
@Entity
@Table(
        name = "column_sequence",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_column_sequence_template_column",
                columnNames = {"template_id", "column_key"})
)
public class ColumnSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "column_key", nullable = false)
    private String columnKey;

    /** Broj koji ce dobiti sljedeci zapis. */
    @Column(name = "next_value", nullable = false)
    private long nextValue;

    public ColumnSequence() {
    }

    public ColumnSequence(Long templateId, String columnKey, long nextValue) {
        this.templateId = templateId;
        this.columnKey = columnKey;
        this.nextValue = nextValue;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getColumnKey() {
        return columnKey;
    }

    public void setColumnKey(String columnKey) {
        this.columnKey = columnKey;
    }

    public long getNextValue() {
        return nextValue;
    }

    public void setNextValue(long nextValue) {
        this.nextValue = nextValue;
    }
}
