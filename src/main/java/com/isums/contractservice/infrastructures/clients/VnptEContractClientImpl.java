package com.isums.contractservice.infrastructures.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.configurations.VnptEcontractProperties;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@Slf4j
public class VnptEContractClientImpl implements VnptEContractClient {

    private final RestClient vnptRestClient;
    private final RestClient directVnptRestClient;
    private final VnptEcontractProperties props;
    private final ObjectMapper mapper;

    public VnptEContractClientImpl(
            @Qualifier("vnptRestClient") RestClient vnptRestClient,
            @Qualifier("directVnptRestClient") RestClient directVnptRestClient,
            VnptEcontractProperties props,
            ObjectMapper mapper
    ) {
        this.vnptRestClient = vnptRestClient;
        this.directVnptRestClient = directVnptRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    public record ProcessCodeLoginRequest(String processCode) {
    }

    private static String httpMethod(RestClientResponseException ex, HttpMethod method, String uri) {
        String body = ex.getResponseBodyAsString();
        return "HTTP " + ex.getStatusCode().value() + " " + ex.getStatusText()
                + "\n" + method + " " + uri
                + (body.isBlank() ? "" : "\n" + body);
    }

    private <T> VnptResult<T> safeCall(HttpMethod method, String uri, Supplier<VnptResult<T>> call) {
        try {
            return call.get();
        } catch (RestClientResponseException ex) {
            return VnptResult.error(httpMethod(ex, method, uri));
        } catch (Exception ex) {
            return VnptResult.error("Unexpected error: " + ex.getMessage());
        }
    }

    private String gatewayCall(String vnptPath, HttpMethod method, Object body, Map<String, String> headers) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("path", vnptPath);
            payload.put("method", method.name());

            if (body == null) {
                payload.put("body", null);
            } else if (body instanceof String s) {
                payload.put("body", s);
            } else {
                payload.put("body", mapper.writeValueAsString(body));
            }

            payload.put("headers", headers == null ? Map.of() : headers);

            return vnptRestClient.post()
                    .uri("/internal/vnpt/forward")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", props.getGatewayToken())
                    .body(payload)
                    .retrieve()
                    .body(String.class);

        } catch (Exception ex) {
            throw new IllegalStateException("Gateway call failed for " + method + " " + vnptPath + ": " + ex.getMessage(), ex);
        }
    }

    private String directCall(String vnptPath, HttpMethod method, Object body, Map<String, String> headers) {
        try {
            RestClient.RequestBodySpec request = directVnptRestClient.method(method).uri(vnptPath);
            if (headers != null && !headers.isEmpty()) {
                request.headers(httpHeaders -> headers.forEach(httpHeaders::set));
            }

            if (body == null) {
                return request.retrieve().body(String.class);
            }

            return request.body(body).retrieve().body(String.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Direct VNPT call failed for " + method + " " + vnptPath + ": " + ex.getMessage(), ex);
        }
    }

    private String gatewayMultipartCall(String token, CreateDocumentDto create) {
        final String uri = "/api/documents/create";

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();

        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("path", uri);
        metadataMap.put("method", "POST");
        metadataMap.put("headers", Map.of("Authorization", "Bearer " + token));
        assert create.no() != null;
        assert create.subject() != null;
        metadataMap.put("formFields", Map.of(
                "No", create.no(),
                "Subject", create.subject(),
                "Description", create.description() == null ? "" : create.description(),
                "TypeId", String.valueOf(create.typeId()),
                "DepartmentId", String.valueOf(create.departmentId())
        ));

        String metadataJson;
        try {
            metadataJson = mapper.writeValueAsString(metadataMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize multipart metadata: " + e.getMessage(), e);
        }

        parts.add("metadata", metadataJson);
        parts.add("file", buildPdfPart(create).getBody());

        return vnptRestClient.post()
                .uri("/internal/vnpt/forward-multipart")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-Internal-Token", props.getGatewayToken())
                .body(parts)
                .exchange((req, res) -> {
                    byte[] bytes = res.getBody().readAllBytes();
                    String responseBody = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    log.info("[VNPT] createDocument via gateway status={} body={}", res.getStatusCode(), responseBody);
                    if (res.getStatusCode().isError()) {
                        throw new IllegalStateException("HTTP " + res.getStatusCode() + "\n" + responseBody);
                    }
                    return responseBody;
                });
    }

    private String directMultipartCall(String token, CreateDocumentDto create) {
        final String uri = "/api/documents/create";

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("No", create.no());
        parts.add("Subject", create.subject());
        parts.add("Description", create.description() == null ? "" : create.description());
        parts.add("TypeId", String.valueOf(create.typeId()));
        parts.add("DepartmentId", String.valueOf(create.departmentId()));
        parts.add("file", buildPdfPart(create));

        return directVnptRestClient.post()
                .uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(parts)
                .retrieve()
                .body(String.class);
    }

    private byte[] gatewayBinaryCall(String downloadUrl) {
        return vnptRestClient.post()
                .uri("/internal/vnpt/forward-binary")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Token", props.getGatewayToken())
                .body(Map.of(
                        "path", downloadUrl,
                        "method", "GET",
                        "headers", Map.of()
                ))
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw new IllegalStateException("Gateway binary download failed: HTTP " + res.getStatusCode());
                    }
                    return res.getBody().readAllBytes();
                });
    }

    private byte[] directBinaryCall(String downloadUrl) {
        return directVnptRestClient.get()
                .uri(downloadUrl)
                .retrieve()
                .body(byte[].class);
    }

    private boolean shouldFallbackToDirect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                int status = responseException.getStatusCode().value();
                if (status == 502 || status == 503 || status == 504) {
                    return true;
                }
            }

            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("502")
                        || lower.contains("503")
                        || lower.contains("504")
                        || lower.contains("bad gateway")
                        || lower.contains("gateway timeout")
                        || lower.contains("service unavailable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String gatewayCallWithFallback(String vnptPath, HttpMethod method, Object body, Map<String, String> headers) {
        try {
            return gatewayCall(vnptPath, method, body, headers);
        } catch (IllegalStateException ex) {
            if (!shouldFallbackToDirect(ex)) {
                throw ex;
            }
            log.warn("[VNPT] gateway failed for {} {}, fallback direct baseUrl={}", method, vnptPath, props.getBaseUrl());
            return directCall(vnptPath, method, body, headers);
        }
    }

    private String multipartCallWithFallback(String token, CreateDocumentDto create) {
        try {
            return gatewayMultipartCall(token, create);
        } catch (IllegalStateException ex) {
            if (!shouldFallbackToDirect(ex)) {
                throw ex;
            }
            log.warn("[VNPT] gateway multipart failed for createDocument, fallback direct baseUrl={}", props.getBaseUrl());
            return directMultipartCall(token, create);
        }
    }

    private byte[] binaryCallWithFallback(String downloadUrl) {
        try {
            return gatewayBinaryCall(downloadUrl);
        } catch (IllegalStateException ex) {
            if (!shouldFallbackToDirect(ex)) {
                throw ex;
            }
            log.warn("[VNPT] gateway binary download failed, fallback direct url={}", downloadUrl);
            return directBinaryCall(downloadUrl);
        }
    }

    @Override
    public VnptResult<VnptDocumentDto> createDocument(String token, CreateDocumentDto create) {
        final String uri = "/api/documents/create";

        return safeCall(HttpMethod.POST, uri, () -> {
            String response = multipartCallWithFallback(token, create);

            log.info("[VNPT] createDocument response raw={}", response);

            if (response == null || response.isBlank()) {
                return VnptResult.error("Gateway returned empty body");
            }

            try {
                return mapper.readValue(response, new TypeReference<VnptResult<VnptDocumentDto>>() {
                });
            } catch (Exception e) {
                return VnptResult.error("Cannot parse gateway/VNPT response: " + e.getMessage());
            }
        });
    }

    private HttpEntity<Resource> buildPdfPart(CreateDocumentDto req) {
        if (req == null || req.fileInfo() == null) {
            throw new IllegalArgumentException("fileInfo is required");
        }

        try {
            Resource resource;
            if (req.fileInfo().filePath() != null && !req.fileInfo().filePath().isBlank()) {
                Path path = Path.of(req.fileInfo().filePath());
                resource = new FileSystemResource(path.toFile());
            } else {
                byte[] bytes = req.fileInfo().file();
                if (bytes == null || bytes.length == 0) {
                    throw new IllegalStateException("File bytes is empty. Provide FileInfo.file or FileInfo.filePath.");
                }

                String filename = (req.fileInfo().fileName() != null && !req.fileInfo().fileName().isBlank())
                        ? req.fileInfo().fileName()
                        : "EContract-" + Instant.now() + ".pdf";

                resource = new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
            }
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.APPLICATION_PDF);

            return new HttpEntity<>(resource, partHeaders);

        } catch (Exception e) {
            throw new IllegalStateException("Cannot build PDF part: " + e.getMessage(), e);
        }
    }

    @Override
    @Cacheable(value = "vnptToken")
    public String getToken() {
        String userName = requireString(props.getUserName(), "Missing econtract.username");
        String password = requireString(props.getPassword(), "Missing econtract.password");
        final String uri = "/api/v2/auth/password-login";

        String raw = gatewayCallWithFallback(
                uri,
                HttpMethod.POST,
                new LoginVnptDto(userName, password),
                Map.of("Content-Type", "application/json")
        );

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty response body to get token");
        }

        return parseToken(raw);
    }

    @Override
    public VnptResult<List<VnptUserDto>> CreateOrUpdateUser(String token, VnptUserUpsert users) {
        final String uri = "/api/users/create-or-update";

        return safeCall(HttpMethod.POST, uri, () -> {
            if (token == null || token.isBlank()) {
                return VnptResult.error("Missing token");
            }
            if (users == null) {
                return VnptResult.error("Missing users");
            }

            String raw = gatewayCallWithFallback(
                    uri,
                    HttpMethod.POST,
                    List.of(users),
                    Map.of(
                            "Content-Type", "application/json",
                            "Authorization", "Bearer " + token
                    )
            );

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            try {
                return mapper.readValue(raw, new TypeReference<VnptResult<List<VnptUserDto>>>() {
                });
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    @Override
    @Cacheable(value = "vnptProcessCode", key = "#processCode")
    public String getAccessInfoByProcessCode(String processCode) {
        final String uri = "/api/auth/process-code-login";

        return gatewayCallWithFallback(
                uri,
                HttpMethod.POST,
                new ProcessCodeLoginRequest(processCode),
                Map.of("Content-Type", "application/json")
        );
    }

    @Override
    public VnptResult<VnptDocumentDto> UpdateProcess(String token, VnptUpdateProcessDTO update) {
        final String uri = "/api/documents/update-process";

        return safeCall(HttpMethod.POST, uri, () -> {
            String raw = gatewayCallWithFallback(
                    uri,
                    HttpMethod.POST,
                    update,
                    Map.of(
                            "Content-Type", "application/json",
                            "Authorization", "Bearer " + token
                    )
            );

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            try {
                return mapper.readValue(raw, new TypeReference<VnptResult<VnptDocumentDto>>() {
                });
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    @Override
    public VnptResult<VnptDocumentDto> sendProcess(String token, String documentId) {
        final String uri = "/api/documents/send-process/" + documentId;

        return safeCall(HttpMethod.POST, uri, () -> {
            String raw = gatewayCallWithFallback(
                    uri,
                    HttpMethod.POST,
                    null,
                    Map.of(
                            "Authorization", "Bearer " + token
                    )
            );

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            try {
                return VnptResult.success(mapper.readValue(raw, VnptDocumentDto.class));
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    @Override
    public VnptResult<ProcessResponse> signProcess(VnptProcessDto process) {
        final String uri = "/api/documents/process";

        return safeCall(HttpMethod.POST, uri, () -> {
            String raw = gatewayCallWithFallback(
                    uri,
                    HttpMethod.POST,
                    process,
                    Map.of(
                            "Content-Type", "application/json",
                            "Authorization", "Bearer " + process.token()
                    )
            );

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            return parseResult(raw, ProcessResponse.class);
        });
    }

    @Override
    public VnptResult<VnptDocumentDto> getEContractById(String documentId, String token) {
        final String uri = "/api/documents/" + documentId;

        return safeCall(HttpMethod.GET, uri, () -> {
            String raw = gatewayCallWithFallback(
                    uri,
                    HttpMethod.GET,
                    null,
                    Map.of(
                            "Authorization", "Bearer " + token,
                            "Accept", "application/json"
                    )
            );

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            try {
                JsonNode root = mapper.readTree(raw);
                JsonNode dataNode = root.get("data");

                if (dataNode == null || dataNode.isNull()) {
                    return VnptResult.error("Missing data field");
                }

                VnptDocumentDto dto = mapper.treeToValue(dataNode, VnptDocumentDto.class);
                return VnptResult.success(dto);

            } catch (Exception e) {
                return VnptResult.error("Cannot parse: " + e.getMessage());
            }
        });
    }

    @Override
    public byte[] downloadSignedPdf(String downloadUrl) {
        try {
            log.info("[VNPT] Downloading signed PDF downloadUrl={}", downloadUrl);

            byte[] pdfBytes = binaryCallWithFallback(downloadUrl);

            if (pdfBytes == null || pdfBytes.length == 0)
                throw new IllegalStateException("Downloaded PDF is empty");

            log.info("[VNPT] Signed PDF downloaded size={}KB", pdfBytes.length / 1024);
            return pdfBytes;

        } catch (Exception e) {
            throw new IllegalStateException("Failed to download signed PDF: " + e.getMessage(), e);
        }
    }

    private String parseToken(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                throw new IllegalStateException("Missing data: " + body);
            }

            String token = null;
            if (data.isTextual()) {
                token = data.asText();
            }

            if (token == null && data.isObject()) {
                token = asString(data.get("token"));
                if (token == null) {
                    token = asString(data.get("accessToken"));
                }
            }
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Missing token: " + body);
            }

            return token;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse token: " + body, e);
        }
    }

    private static String asString(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    private static String requireString(String string, String msg) {
        if (string == null || string.isBlank()) {
            throw new IllegalArgumentException(msg);
        }
        return string;
    }

    private <T> VnptResult<T> parseResult(String raw, Class<T> dataClass) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode dataNode = root.get("data");
            if (dataNode == null || dataNode.isNull()) {
                return VnptResult.error("Missing data field. RAW=" + raw.substring(0, Math.min(500, raw.length())));
            }
            T dto = mapper.treeToValue(dataNode, dataClass);
            return VnptResult.success(dto);
        } catch (Exception e) {
            return VnptResult.error("Cannot parse: " + e.getMessage());
        }
    }
}
