package com.salian.shortener.link;

public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("Short link not found: " + code);
    }
}
