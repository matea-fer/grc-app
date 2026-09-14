package com.example.demo.auth;

import com.example.demo.model.Role;

/**
 * Tko salje trenutni zahtjev - procitano iz potpisanog tokena, ne iz baze.
 *
 * Namjerno je odvojeno od {@link com.example.demo.model.User}: entitet nosi i hash
 * lozinke, a ovaj zapis putuje kroz slojeve i zavrsava u audit zapisu.
 *
 * @param companyId firma korisnika, ili null za globalnog ADMIN-a (on firmu bira
 *                  zaglavljem). TENANT_ADMIN je ovdje kao i USER - ima firmu.
 */
public record AuthenticatedUser(Long userId, String username, Role role, Long companyId) {

    /** Globalni administrator - onaj koji nije vezan ni uz jednu firmu. */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Administrator jedne firme - one iz {@link #companyId()}. */
    public boolean isTenantAdmin() {
        return role == Role.TENANT_ADMIN;
    }

    /**
     * Administrator bilo kojeg dosega - globalni ILI firmin.
     *
     * Postoji jer se vise pravila razlikuje po tome je li netko administrator, a ne nad
     * cime: upravljanje korisnicima, uredivanje sheme, otkljucavanje zapisa. Doseg
     * (nad KOJIM podacima) svugdje i dalje odlucuje izolacija po firmi, ne ovo.
     */
    public boolean isAnyAdmin() {
        return isAdmin() || isTenantAdmin();
    }

    /**
     * Smije li uopce dirati korisnicke racune. Nad KOJIMA se odlucuje posebno
     * ({@link com.example.demo.service.UserService}) - globalni admin nad svima,
     * admin firme samo nad svojom.
     */
    public boolean canManageUsers() {
        return isAnyAdmin();
    }
}
