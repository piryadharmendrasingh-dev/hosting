package com.example.rankhomes.service;

/** Thrown when a player's homes could not be loaded from the database. */
public final class DataUnavailableException extends RuntimeException {
    public DataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
