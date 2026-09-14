package com.example.demo.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceName, Long id) {
        super(resourceName + " with id " + id + " not found");
    }

    // Stupci se vise ne traze po id-u, nego po nazivu unutar retka firme.
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
