package com.example.kvstore.dataplane.exception;

public class StorageInitializationException extends RuntimeException {

    public StorageInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
