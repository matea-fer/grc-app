package com.example.demo.exception;

/** Korisnik ne zadovoljava pravila (uloga i firma se ne slazu, zadnji ADMIN...) -> 400. */
public class InvalidUserException extends RuntimeException {
    public InvalidUserException(String message) {
        super(message);
    }
}
