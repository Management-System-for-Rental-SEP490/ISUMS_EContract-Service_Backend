package com.isums.contractservice.exceptions;

import com.isums.contractservice.domains.dtos.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler (econtract-service)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleNotFoundException returns 404")
    void notFound() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleNotFoundException(new NotFoundException("no contract"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().getMessage()).isEqualTo("no contract");
    }

    @Test
    @DisplayName("handleIllegalStateException returns 422 Unprocessable Content (fix: JDK class)")
    void illegalState() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleIllegalStateException(new IllegalStateException("invalid transition"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().getMessage()).isEqualTo("invalid transition");
    }

    @Test
    @DisplayName("handleBadRequest returns 400 with BAD_REQUEST code")
    void badRequest() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleBadRequest(new IllegalArgumentException("bad"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().getErrors().get(0).getCode()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("handleConflict returns 409 with CONFLICT code")
    void conflict() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleConflict(new ConflictException("dup"));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().getErrors().get(0).getCode()).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("handleRestClient mirrors upstream status when resolvable")
    void restClient422() {
        RestClientResponseException ex = new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY, "unp");
        ResponseEntity<ApiResponse<Void>> res = handler.handleRestClient(ex);
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().getErrors().get(0).getCode()).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    @DisplayName("handleRestClient falls back to 502 for non-standard status")
    void restClientFallback() {
        RestClientResponseException ex = new RestClientResponseException(
                "weird", HttpStatusCode.valueOf(599), "server", null, null, null);
        ResponseEntity<ApiResponse<Void>> res = handler.handleRestClient(ex);
        assertThat(res.getStatusCode().value()).isEqualTo(502);
    }

    @Test
    @DisplayName("handleDb returns 500 with DB_ERROR root-cause message")
    void db() {
        DataAccessException ex = new DataAccessException("outer", new RuntimeException("root")) {};
        ResponseEntity<ApiResponse<Void>> res = handler.handleDb(ex);
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().getErrors().get(0).getMessage()).isEqualTo("root");
    }

    @Test
    @DisplayName("handleGeneric returns 500 with sanitized message")
    void generic() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleGeneric(new Exception("sensitive"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().getMessage()).isEqualTo("Unexpected error");
        assertThat(res.getBody().getErrors().get(0).getMessage()).isEqualTo("An unexpected error occurred");
    }

    @Test
    @DisplayName("handleForbidden returns 403")
    void forbidden() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleForbidden(new ForbiddenException("nope"));
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody().getErrors().get(0).getCode()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("handleBusinessException returns 422")
    void business() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleBusinessException(new BusinessException("rule"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().getErrors().get(0).getCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    @Test
    @DisplayName("handleOcrValidation returns 422 with OCR_VALIDATION_FAILED")
    void ocr() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleOcrValidation(
                new OcrValidationException(OcrValidationException.ID_MISMATCH, "mismatch"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().getErrors().get(0).getCode()).isEqualTo("OCR_VALIDATION_FAILED");
    }
}
