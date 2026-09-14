package com.example.demo.core;

import com.example.demo.exception.ApiError;
import com.example.demo.exception.ApiExceptionHandler;
import com.example.demo.exception.InvalidColumnDefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORE test - mapiranje iznimaka na HTTP status u {@link ApiExceptionHandler}.
 */
@Tag("core")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("sudar izmjena (optimistic lock) -> 409")
    void optimisticLockMapsToConflict() {
        ResponseEntity<ApiError> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("stale"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("nevaljana definicija stupca -> 400")
    void invalidColumnDefinitionMapsToBadRequest() {
        ResponseEntity<ApiError> response =
                handler.handleInvalidColumnDefinition(new InvalidColumnDefinitionException("nepoznat tip"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("nepoznat tip");
    }
}
