package com.webauthn.app.exception;

public class AppRegistrationException extends RuntimeException {
    public AppRegistrationException(String message) {
        super(message);
    }

    public AppRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}