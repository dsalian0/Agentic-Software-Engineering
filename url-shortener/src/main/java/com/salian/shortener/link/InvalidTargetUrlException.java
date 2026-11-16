package com.salian.shortener.link;

public class InvalidTargetUrlException extends RuntimeException {

    public InvalidTargetUrlException(String message) {
        super(message);
    }
}
