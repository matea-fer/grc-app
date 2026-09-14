package com.example.demo.auth;

import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

/**
 * Drzi prijavljenog korisnika za trenutni HTTP zahtjev.
 *
 * Isti obrazac kao {@link com.example.demo.tenant.TenantContext}, i iz istog razloga:
 * korisnika ne treba provlaciti kao parametar kroz svaki sloj. {@link JwtAuthFilter}
 * ga na pocetku zahtjeva postavi, servisi ga citaju (audit treba znati TKO), filter
 * ga u {@code finally} obrise.
 *
 * {@link ThreadLocal} jer svaki zahtjev ide na svojoj niti - dva istovremena zahtjeva
 * razlicitih korisnika se tako ne mijesaju. Bean je (umjesto statickih metoda) da ga
 * servisi dobiju injekcijom i da se u testovima lako zamijeni mockom.
 */
@Component
public class AuthContext {

    private final ThreadLocal<AuthenticatedUser> current = new ThreadLocal<>();

    public void set(AuthenticatedUser user) {
        current.set(user);
    }

    /** Nit koja je zavrsila zahtjev mora ocistiti vrijednost, inace bi je naslijedio sljedeci zahtjev na istoj niti. */
    public void clear() {
        current.remove();
    }

    /** Prijavljeni korisnik ili {@code null} za pozive izvan prijave (npr. sama prijava). */
    public AuthenticatedUser get() {
        return current.get();
    }

    /**
     * Prijavljeni korisnik za pozive koji bez njega ne smiju proci. Baca
     * {@link UnauthorizedException} (-> 401) umjesto da tiho vrati null. Za zasticene
     * rute filter je vec postavio vrijednost, pa je ovo zadnja crta obrane.
     */
    public AuthenticatedUser require() {
        AuthenticatedUser user = current.get();
        if (user == null) {
            throw new UnauthorizedException("Potrebna je prijava.");
        }
        return user;
    }

    /**
     * Prijavljeni korisnik, ali samo ako je GLOBALNI administrator. Cuva rute koje
     * se ticu svih firmi odjednom (CRUD firmi) - njih admin jedne firme ne smije
     * ni vidjeti.
     *
     * @throws com.example.demo.exception.ForbiddenException ako je prijavljen, ali nije ADMIN
     */
    public AuthenticatedUser requireAdmin() {
        AuthenticatedUser user = require();
        if (!user.isAdmin()) {
            throw new ForbiddenException("Ova radnja je dopuštena samo administratoru.");
        }
        return user;
    }

    /**
     * Prijavljeni korisnik, ali samo ako smije upravljati korisnickim racunima -
     * dakle globalni administrator ILI administrator firme.
     *
     * Namjerno propusta oboje i vraca korisnika: NAD KOJIM racunima smije raditi
     * odlucuje {@link com.example.demo.service.UserService} iz ove vracene uloge i
     * firme. Da se ta granica povlaci ovdje, kontekst bi morao znati za korisnike -
     * a on zna samo tko salje zahtjev.
     *
     * @throws com.example.demo.exception.ForbiddenException ako je prijavljen, ali je obican korisnik
     */
    public AuthenticatedUser requireUserManager() {
        AuthenticatedUser user = require();
        if (!user.canManageUsers()) {
            throw new ForbiddenException("Ova radnja je dopuštena samo administratoru.");
        }
        return user;
    }

    /**
     * Prijavljeni korisnik, ali samo ako je administrator - globalni ILI firmin.
     *
     * Cuva radnje koje obican korisnik ne smije, a nisu vezane uz jednu firmu vise nego
     * sto su to i sami podaci: otkljucavanje zapisa. Doseg (nad KOJIM zapisom) i dalje
     * odlucuje izolacija po firmi, kao i za svaki drugi poziv nad zapisom.
     *
     * @throws com.example.demo.exception.ForbiddenException ako je prijavljen, ali je obican korisnik
     */
    public AuthenticatedUser requireAnyAdmin() {
        AuthenticatedUser user = require();
        if (!user.isAnyAdmin()) {
            throw new ForbiddenException("Ova radnja je dopuštena samo administratoru.");
        }
        return user;
    }

    /** Korisnicko ime za audit, ili {@code null} ako zahtjev nije prijavljen. */
    public String username() {
        AuthenticatedUser user = current.get();
        return user != null ? user.username() : null;
    }
}
