package com.example.demo.exception;

/**
 * Korisnik JEST prijavljen, ali njegova uloga ovo ne smije -> 403.
 *
 * Razlikuje se od 404 koji se koristi za tude retke: tamo se skriva i postojanje
 * resursa, a ovdje se skriva nista - da je ruta samo za ADMIN-a nije tajna.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
