package com.example.demo.exception;

/**
 * Definicija stupca ne zadovoljava pravila (nepoznat tip, ili padajuci izbornik
 * bez dovoljno vrijednosti). Frontend ista pravila vec provjerava, ali backend ih
 * mora braniti i za izravne API pozive. Mapira se na 400.
 */
public class InvalidColumnDefinitionException extends RuntimeException {
    public InvalidColumnDefinitionException(String message) {
        super(message);
    }
}
