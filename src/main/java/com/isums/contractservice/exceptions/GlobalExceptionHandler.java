package com.isums.contractservice.exceptions;

import com.isums.contractservice.domains.dtos.ApiError;
import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;


@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDb(DataAccessException ex) {
        ex.getMostSpecificCause();
        String detail = ex.getMostSpecificCause().getMessage();

        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Database error",
                List.of(ApiError.builder()
                        .code("DB_ERROR")
                        .message(detail)
                        .build())
        );

        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(NotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponses.fail(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(java.lang.IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(java.lang.IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponses.fail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                List.of(ApiError.builder()
                        .code("BAD_REQUEST")
                        .message(ex.getMessage())
                        .build())
        );

        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleRestClient(RestClientResponseException ex) {

        log.error("Upstream HTTP error: status={} body={}", ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);

        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.BAD_GATEWAY;

        ApiResponse<Void> res = ApiResponses.fail(
                status,
                "Upstream service error",
                List.of(ApiError.builder()
                        .code("UPSTREAM_ERROR")
                        .message("HTTP " + ex.getStatusCode().value() + " " + ex.getStatusText())
                        .build())
        );

        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                List.of(ApiError.builder().code("CONFLICT").message(ex.getMessage()).build())
        );
        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);

        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error",
                List.of(ApiError.builder()
                        .code("INTERNAL_ERROR")
                        .message("An unexpected error occurred")
                        .build())
        );

        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(OcrValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleOcrValidation(OcrValidationException ex) {
        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ex.getMessage(),
                List.of(ApiError.builder()
                        .code("OCR_VALIDATION_FAILED")
                        .message(ex.getMessage())
                        .build())
        );
        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.FORBIDDEN,
                ex.getMessage(),
                List.of(ApiError.builder()
                        .code("FORBIDDEN")
                        .message(ex.getMessage())
                        .build())
        );
        return ResponseEntity.status(res.getStatusCode()).body(res);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ApiResponse<Void> res = ApiResponses.fail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ex.getMessage(),
                List.of(ApiError.builder()
                        .code("BUSINESS_RULE_VIOLATION")
                        .message(ex.getMessage())
                        .build())
        );
        return ResponseEntity.status(res.getStatusCode()).body(res);
    }
}
