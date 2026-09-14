package com.example.demo.exception;

/**
 * Radnja je odbijena jer je zapis zakljucan -> 409.
 *
 * Namjerno NIJE 403: 403 govori "ti to ne smijes", a ovdje ne smije NITKO dok je zapis u
 * ovom stanju - ni onaj tko ga smije otkljucati. Zato konflikt stanja, kao i kod pokusaja
 * praznjenja firme koja jos nije arhivirana.
 */
public class RecordLockedException extends RuntimeException {
    public RecordLockedException(String message) {
        super(message);
    }
}
