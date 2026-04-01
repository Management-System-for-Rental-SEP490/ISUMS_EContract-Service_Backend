package com.isums.contractservice.exceptions;

import lombok.Getter;

@Getter
public class OcrValidationException extends RuntimeException {
    private final String errorCode;

    public OcrValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static final String NOT_FRONT_SIDE = "OCR_NOT_FRONT_SIDE";
    public static final String CANNOT_READ_ID = "OCR_CANNOT_READ_ID";
    public static final String ID_MISMATCH = "OCR_ID_MISMATCH";
    public static final String NAME_MISMATCH = "OCR_NAME_MISMATCH";
    public static final String NOT_BACK_SIDE = "OCR_NOT_BACK_SIDE";
    public static final String IMAGE_NOT_READABLE = "OCR_IMAGE_NOT_READABLE";
}
