package com.admintools.storage;

/** Error de base de datos: envuelve SQLException para no ensuciar las firmas del plugin. */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
