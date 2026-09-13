package com.pitchquery.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * Any failure that should surface to the frontend as a clean
 * {@code {"error": "..."}} JSON body with a specific HTTP status,
 * rather than a 500 stack trace.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
