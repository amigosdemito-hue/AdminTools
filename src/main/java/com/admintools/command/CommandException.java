package com.admintools.command;

/** Error controlado de un comando: se le muestra al emisor en vez de una traza. */
public class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message);
    }
}
