package com.isums.contractservice.infrastructures.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.domains.dtos.PassportIdentityDto;
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
            String response = postImage("/ocr/cccd", image);
            TenantIdentityDto result = objectMapper.readValue(response, TenantIdentityDto.class);
            log.info("[OCR] Extracted identity: {}, name: {}",
                    result.getIdentityNumber(), result.getFullName());
            return result;
        } catch (Exception e) {
            log.error("[OCR] Failed to extract CCCD: {}", e.getMessage());
            throw new RuntimeException("OCR service unavailable: " + e.getMessage(), e);
        }
    }

    public PassportIdentityDto extractPassport(MultipartFile image) {
        try {
            String response = postImage("/ocr/passport", image);
            PassportIdentityDto result = objectMapper.readValue(response, PassportIdentityDto.class);
            log.info("[OCR] Extracted passport: {}, name: {}, nationality: {}",
                    result.getPassportNumber(), result.getFullName(), result.getNationality());
            return result;
        } catch (Exception e) {
            log.error("[OCR] Failed to extract passport: {}", e.getMessage());
            throw new RuntimeException("OCR service unavailable: " + e.getMessage(), e);
        }
    }

    private String postImage(String path, MultipartFile image) throws java.io.IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(image.getBytes()) {
            @Override
            public String getFilename() {
                return image.getOriginalFilename();
            }
        };
        body.add("image", resource);

        return restClient.post()
                .uri(ocrServiceUrl + path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
