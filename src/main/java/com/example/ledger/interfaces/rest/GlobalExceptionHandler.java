package com.example.ledger.interfaces.rest;

import com.example.ledger.domain.account.AccountDomainException;
import com.example.ledger.infrastructure.persistence.ConcurrentModificationException;
import com.example.ledger.interfaces.rest.dto.Dtos.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<ApiError> handleConcurrency(ConcurrentModificationException e) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", e.getMessage());
    }

    @ExceptionHandler(AccountDomainException.AccountNotFound.class)
    public ResponseEntity<ApiError> handleNotFound(AccountDomainException.AccountNotFound e) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(AccountDomainException.AccountAlreadyExists.class)
    public ResponseEntity<ApiError> handleAlreadyExists(AccountDomainException.AccountAlreadyExists e) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler({
            AccountDomainException.InsufficientFunds.class,
            AccountDomainException.AccountFrozenException.class,
            AccountDomainException.InvalidAmount.class
    })
    public ResponseEntity<ApiError> handleBusinessRule(AccountDomainException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message));
    }
}
