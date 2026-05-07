package com.isums.contractservice.exceptions;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final String errorCode;

    public BusinessException(String message) {
        this(null, message);
    }

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static final String INVALID_PROCESS_CODE = "INVALID_PROCESS_CODE";
    public static final String SIGNING_INFO_UNAVAILABLE = "SIGNING_INFO_UNAVAILABLE";
    public static final String CONTRACT_NOT_FOUND = "CONTRACT_NOT_FOUND";
    public static final String PDF_NOT_READY = "PDF_NOT_READY";
}
