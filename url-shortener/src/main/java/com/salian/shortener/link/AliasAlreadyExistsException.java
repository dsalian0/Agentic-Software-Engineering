package com.salian.shortener.link;

public class AliasAlreadyExistsException extends RuntimeException {

    public AliasAlreadyExistsException(String alias) {
        super("Alias already in use: " + alias);
    }
}
