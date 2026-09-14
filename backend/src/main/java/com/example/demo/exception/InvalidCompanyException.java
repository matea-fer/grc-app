package com.example.demo.exception;

/** Radnja se ne slaze sa stanjem firme (vracanje neabhivirane, praznjenje aktivne...) -> 400. */
public class InvalidCompanyException extends RuntimeException {
    public InvalidCompanyException(String message) {
        super(message);
    }
}
