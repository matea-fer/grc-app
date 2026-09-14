package com.example.demo.dto;

import java.util.Map;

/**
 * Firma se NE prima iz tijela zahtjeva - uzima se iz tenant konteksta (zaglavlja),
 * jer se onome sto klijent posalje ne smije vjerovati (mogao bi upisati pod tudu firmu).
 *
 * {@code data} nema anotacija za validaciju jer se ne moze opisati unaprijed -
 * sto je u njemu dopusteno ovisi o shemi firme. Provjerava ga SchemaValidator.
 */
public record CreateSurveyRequest(
        Map<String, Object> data
) {
}
