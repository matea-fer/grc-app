package com.example.demo.exception;

/** Korisnicko ime je vec zauzeto -> 409. */
public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String username) {
        super("Korisničko ime \"" + username + "\" je već zauzeto.");
    }
}
