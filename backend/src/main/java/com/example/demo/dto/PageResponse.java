package com.example.demo.dto;

import java.util.List;

/**
 * Jedna stranica rezultata.
 *
 * <b>Zasto vlastiti oblik, a ne Springov {@code Page}.</b> {@code Page} je unutarnji razred
 * podatkovnog sloja; njegov JSON oblik nije dio nijednog ugovora i vec se mijenjao izmedu
 * verzija Springa. Da ga saljemo van, nadogradnja knjiznice bi tiho promijenila API i srusila
 * sucelje. Ovako se van salje ono sto smo mi obecali, a {@code Page} ostaje unutra.
 *
 * <b>Zasto {@code totalElements} uopce treba.</b> Bez ukupnog broja se ne da nacrtati ni
 * "1-50 od 1.234" ni popis stranica - klijent bi znao samo da postoji jos nesto. Cijena je
 * jedan dodatni {@code COUNT} upit po dohvatu; to je svjesna zamjena.
 *
 * @param content       redci ove stranice
 * @param page          redni broj stranice, od 0
 * @param size          najveci broj redaka po stranici
 * @param totalElements ukupan broj redaka koji odgovaraju upitu (ne samo na ovoj stranici)
 * @param totalPages    ukupan broj stranica; 0 kad nema nijednog retka
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    /** Prazna stranica - kad se vec iz upita zna da rezultata nema (npr. filtar bez ijednog pogotka). */
    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0);
    }
}
