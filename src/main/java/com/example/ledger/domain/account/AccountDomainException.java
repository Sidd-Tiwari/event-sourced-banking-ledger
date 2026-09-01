package com.example.ledger.domain.account;

/** Base type for business-rule rejections (maps to HTTP 422 at the API layer, not 500). */
public class AccountDomainException extends RuntimeException {

    public AccountDomainException(String message) {
        super(message);
    }

    public static class InsufficientFunds extends AccountDomainException {
        public InsufficientFunds(String message) { super(message); }
    }

    public static class AccountFrozenException extends AccountDomainException {
        public AccountFrozenException(String message) { super(message); }
    }

    public static class InvalidAmount extends AccountDomainException {
        public InvalidAmount(String message) { super(message); }
    }

    public static class AccountAlreadyExists extends AccountDomainException {
        public AccountAlreadyExists(String message) { super(message); }
    }

    public static class AccountNotFound extends AccountDomainException {
        public AccountNotFound(String message) { super(message); }
    }
}
