package com.schwab.urlshortener.web;

import com.schwab.urlshortener.model.ServiceExceptions.AliasConflictException;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.model.ServiceExceptions.UrlGoneException;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps domain exceptions to HTTP status codes + a structured error body, in
 * one place -- the Spring equivalent of the original {@code ExceptionMapper},
 * now applied declaratively across every controller instead of being called
 * from each handler's catch block.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<Object> handleInvalid(InvalidUrlException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(AliasConflictException.class)
    public ResponseEntity<Object> handleConflict(AliasConflictException e) {
        return errorResponse(HttpStatus.CONFLICT, "ALIAS_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(UrlNotFoundException e) {
        return errorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(UrlGoneException.class)
    public ResponseEntity<Object> handleGone(UrlGoneException e) {
        return errorResponse(HttpStatus.GONE, "GONE", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleMalformedBody(HttpMessageNotReadableException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body must be valid JSON");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private static ResponseEntity<Object> errorResponse(HttpStatus status, String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCode);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
