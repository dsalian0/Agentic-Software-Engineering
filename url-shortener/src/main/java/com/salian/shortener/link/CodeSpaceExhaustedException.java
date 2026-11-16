package com.salian.shortener.link;

/** Raised when bounded code generation keeps colliding; signals capacity trouble, not a client error. */
public class CodeSpaceExhaustedException extends RuntimeException {

    public CodeSpaceExhaustedException(int attempts) {
        super("Could not allocate a unique code after " + attempts + " attempts");
    }
}
