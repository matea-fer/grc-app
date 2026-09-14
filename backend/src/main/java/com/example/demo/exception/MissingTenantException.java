package com.example.demo.exception;

/**
 * Zasticena ruta je pozvana bez valjanog TenantID zaglavlja (nedostaje,
 * nije broj, ili pokazuje na firmu koja ne postoji). Preslikava se u 400.
 *
 * Namjerno se NE javlja "koja firma nedostaje" niti se otkriva postoji li neki
 * id - poziv bez ispravnog tenanta ne smije nista saznati o podacima.
 */
public class MissingTenantException extends RuntimeException {
    public MissingTenantException() {
        super("Nedostaje ili je nevažeće zaglavlje TenantID.");
    }
}
