package com.kbo.crawlerapi.api;

import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(org.springframework.web.server.ResponseStatusException exception,
            HttpServletRequest request) {
        var response = ResponseEntity.status(exception.getStatusCode());
        var status = HttpStatus.valueOf(exception.getStatusCode().value());
        if (exception.getStatusCode().value() == 429) {
            response.header("Retry-After", "30");
        }
        return response.body(new ApiErrorResponse(OffsetDateTime.now(), exception.getStatusCode().value(),
                status.getReasonPhrase(),
                status == HttpStatus.TOO_MANY_REQUESTS ? "RATE_LIMITED" : status.name(),
                exception.getReason(), request.getRequestURI()));
    }

    @ExceptionHandler({
            InvalidParameterException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "INVALID_PARAMETER",
                exception.getMessage(),
                request.getRequestURI()
        ));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                "GAME_NOT_FOUND",
                exception.getMessage(),
                request.getRequestURI()
        ));
    }
}
