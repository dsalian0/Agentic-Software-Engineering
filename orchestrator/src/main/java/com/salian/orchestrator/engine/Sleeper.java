package com.salian.orchestrator.engine;

/** Injectable delay so retry backoff is real in production and instant in tests. */
@FunctionalInterface
public interface Sleeper {

    Sleeper REAL = Thread::sleep;

    void sleep(long millis) throws InterruptedException;
}
