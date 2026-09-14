package com.example.demo.tenant;

import com.example.demo.exception.MissingTenantException;
import org.springframework.stereotype.Component;

/**
 * Drzi ID firme (tenanta) na koju se odnosi trenutni HTTP zahtjev.
 *
 * Postoji da se firma ne mora provlaciti kao parametar kroz svaki sloj: filter
 * je na pocetku zahtjeva postavi, servisi je citaju, filter je na kraju obrise.
 *
 * Vrijednost se cuva u {@link ThreadLocal} jer svaki zahtjev Spring obraduje na
 * svojoj niti - dva istovremena zahtjeva razlicitih firmi tako se ne mijesaju.
 * Sam bean je singleton (jedan za citavu aplikaciju), ali svaka nit kroz njega
 * vidi samo svoju vrijednost. Bean je (umjesto statickih metoda) da ga servisi
 * mogu dobiti injekcijom i da se u testovima lako zamijeni mockom.
 */
@Component
public class TenantContext {

    private final ThreadLocal<Long> current = new ThreadLocal<>();

    public void set(Long companyId) {
        current.set(companyId);
    }

    /** Nit koja je zavrsila zahtjev mora ocistiti vrijednost, inace bi je naslijedio sljedeci zahtjev na istoj niti. */
    public void clear() {
        current.remove();
    }

    /** ID firme ili {@code null} ako nije postavljen (npr. poziv koji nije vezan uz firmu). */
    public Long get() {
        return current.get();
    }

    /**
     * ID firme za pozive koji bez nje ne smiju proci. Baca {@link MissingTenantException}
     * (-> 400) umjesto da tiho vrati null i pusti da se negdje dublje krivo protumaci.
     * Za zasticene rute filter je vec postavljen, pa je ovo zadnja crta obrane.
     */
    public Long require() {
        Long companyId = current.get();
        if (companyId == null) {
            throw new MissingTenantException();
        }
        return companyId;
    }
}
