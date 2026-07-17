package uz.greenwhite.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void genericHandlerHidesInternalDetailButGivesCorrelationRef() {
        ResponseEntity<ErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("jdbc:oracle://secret-host:1521 timeout"));

        assertEquals(500, response.getStatusCode().value());
        String message = response.getBody().getMessage();
        assertFalse(message.contains("secret-host"), "internal detail must not leak");
        assertTrue(message.contains("ref:"), "must include a correlation ref");
    }
}
