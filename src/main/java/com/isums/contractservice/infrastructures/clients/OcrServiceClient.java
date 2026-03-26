package com.isums.contractservice.infrastructures.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.domains.dtos.TenantIdentityDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Component
@RequiredArgsConstructor
public class OcrServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${ocr.service.url:http://ocr-service:9000}")
    private String ocrServiceUrl;

    public TenantIdentityDto extractCccd(MultipartFile image) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(image.getBytes()) {
                @Override
                public String getFilename() {
                    return image.getOriginalFilename();
                }
            };
            body.add("image", resource);

            String response = restClient.post()
                    .uri(ocrServiceUrl + "/ocr/cccd")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            TenantIdentityDto result = objectMapper.readValue(response, TenantIdentityDto.class);
            log.info("[OCR] Extracted identity: {}, name: {}",
                    result.getIdentityNumber(), result.getFullName());
            return result;

        } catch (Exception e) {
            log.error("[OCR] Failed to extract CCCD: {}", e.getMessage());
            throw new RuntimeException("OCR service unavailable: " + e.getMessage(), e);
        }
    }
}