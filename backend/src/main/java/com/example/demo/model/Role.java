package com.example.demo.model;

/**
 * Uloga korisnika. Tri razine, po tome koliko siroko vrijede:
 *
 * <ul>
 *   <li>{@code ADMIN} - globalni administrator. Nije vezan uz firmu
 *       ({@code companyId = null}) i firmu bira u gornjoj traci. Jedini smije
 *       stvarati firme i globalne administratore.</li>
 *   <li>{@code TENANT_ADMIN} - administrator jedne firme. Pripada tocno jednoj
 *       firmi (kao i USER) i nju dobiva iz tokena. Unutar NJE upravlja
 *       korisnicima i radi sve sto radi i obican korisnik. Izvan nje ne vidi
 *       nista.</li>
 *   <li>{@code USER} - obican korisnik. Pripada tocno jednoj firmi i u njoj
 *       radi s obrascima i zapisima.</li>
 * </ul>
 *
 * Granica izmedu ADMIN-a i TENANT_ADMIN-a nije "koliko smije", nego "nad cim":
 * prvi radi nad svim firmama, drugi nad jednom. Zato TENANT_ADMIN prolazi kroz
 * istu izolaciju po firmi kao i USER - firma mu dolazi iz tokena i zaglavlje
 * {@code TenantID} mu se ignorira.
 */
public enum Role {
    ADMIN,
    TENANT_ADMIN,
    USER
}
