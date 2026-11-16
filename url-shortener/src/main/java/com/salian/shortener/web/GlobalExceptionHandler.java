package com.salian.shortener.web;

import com.salian.shortener.link.AliasAlreadyExistsException;
import com.salian.shortener.link.CodeSpaceExhaustedException;
import com.salian.shortener.link.InvalidTargetUrlException;
import com.salian.shortener.link.LinkNotFoundException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps domain failures to RFC 7807 problem details; never leaks stack traces to clients. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LinkNotFoundException.class)
    public ProblemDetail handleNotFound(LinkNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", ex.getMessage());
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ProblemDetail handleAliasConflict(AliasAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, "Alias already in use", ex.getMessage());
    }

    @ExceptionHandler(InvalidTargetUrlException.class)
    public ProblemDetail handleInvalidTarget(InvalidTargetUrlException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid target URL", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Invalid value for parameter " + ex.getName());
    }

    @ExceptionHandler(CodeSpaceExhaustedException.class)
    public ProblemDetail handleExhausted(CodeSpaceExhaustedException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unable to allocate a code", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        return problemDetail;
    }
}
