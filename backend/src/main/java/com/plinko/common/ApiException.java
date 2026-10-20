package com.plinko.common;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

/** Error returned to the game client as {@code {"code": ..., "message": ..., "balance": ...}}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final BigDecimal balance;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, BigDecimal balance) {
        super(message);
        this.status = status;
        this.code = code;
        this.balance = balance;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public BigDecimal balance() {
        return balance;
    }
}
