package com.isums.contractservice.infrastructures.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.configurations.VnptEcontractProperties;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class VnptEContractClientImpl implements VnptEContractClient {

    private final RestClient vnptRestClient;
    private final VnptEcontractProperties props;
    private final ObjectMapper mapper;

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

    @Override
    public VnptResult<VnptDocumentDto> createDocument(String token, CreateDocumentDto create) {
        final String uri = "/api/documents/create";

        return safeCall(HttpMethod.POST, uri, () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("X-Internal-Token", props.getGatewayToken());

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
                return VnptResult.error("Cannot serialize multipart metadata: " + e.getMessage());
            }

            parts.add("metadata", metadataJson);

            HttpEntity<Resource> filePart = buildPdfPart(create);
            parts.add("file", filePart.getBody());

            String response = vnptRestClient.post()
                    .uri("/internal/vnpt/forward-multipart")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("X-Internal-Token", props.getGatewayToken())
                    .body(parts)
                    .retrieve()
                    .body(String.class);

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

        String raw = gatewayCall(
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

            String raw = gatewayCall(
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

        return gatewayCall(
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
            String raw = gatewayCall(
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
            String raw = gatewayCall(
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
            String raw = gatewayCall(
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
            String raw = gatewayCall(
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