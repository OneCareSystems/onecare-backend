package com.onecare.backend.exception;

public class InvalidStockException extends RuntimeException {

    public InvalidStockException(String message) {
        super(message);
    }
}