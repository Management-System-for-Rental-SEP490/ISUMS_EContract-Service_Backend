package com.isums.contractservice.infrastructures.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import com.isums.contractservice.domains.dtos.VnptEcontractProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    private static void bearer(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
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

    @Override
    public VnptResult<VnptDocumentDto> createDocument(String token, CreateDocumentDto create) {
        final String uri = "/api/documents/create";

        return safeCall(HttpMethod.POST, uri, () -> {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("No", create.no());
            parts.add("Subject", create.subject());
            parts.add("Description", create.description() == null ? "" : create.description());
            parts.add("TypeId", String.valueOf(create.typeId()));
            parts.add("DepartmentId", String.valueOf(create.departmentId()));

            HttpEntity<Resource> filePath = buildPdfPart(create);
            parts.add("File", filePath);

            String response = vnptRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> bearer(h, token))
                    .body(parts)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            int max = 4000;
            String clipped = response.length() > max ? response.substring(0, max) + "..." : response;
            System.out.println("VNPT raw response: " + clipped);

            try {
                return mapper.readValue(response, new TypeReference<VnptResult<VnptDocumentDto>>() {
                });
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage()
                        + "\nRAW=" + clipped);
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
                        : "EContract-" + Instant.now().toString() + ".pdf";

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

    @Cacheable(value = "vnptToken")
    public String getToken() {
        String userName = requireString(props.getUserName(), "Missing econtract.username");
        String password = requireString(props.getPassword(), "Missing econtract.password");
        final String uri = "/api/v2/auth/password-login";

        try {
            LoginVnptDto payload = new LoginVnptDto(userName, password);
            String body = vnptRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                throw new IllegalStateException("Empty response body to get token");
            }

            return parseToken(body);
        } catch (RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            throw new IllegalStateException("VNPT login failed: " + ex.getStatusCode().value()
                    + " " + ex.getStatusText() + (body.isBlank() ? "" : "\n" + body), ex);
        }
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

            String raw = vnptRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> bearer(h, token))
                    .body(List.of(users))
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }
            int max = 4000;
            String clipped = raw.length() > max ? raw.substring(0, max) + "..." : raw;

            try {
                return mapper.readValue(raw, new TypeReference<VnptResult<List<VnptUserDto>>>() {
                });
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nRAW=" + clipped);
            }
        });
    }

    @Override
    @Cacheable(value = "vnptProcessCode", key = "#processCode")
    public String getAccessInfoByProcessCode(String processCode) {
        try {
            String uri = "/api/auth/process-code-login";

            ProcessCodeLoginRequest payload = new ProcessCodeLoginRequest(processCode);

            String body = vnptRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                throw new IllegalStateException("Empty response body process-code-login");
            }

            return body;

        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse VNPT response: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public VnptResult<VnptDocumentDto> UpdateProcess(String token, VnptUpdateProcessDTO update) {
        final String uri = "/api/documents/update-process";

        return safeCall(HttpMethod.POST, uri, () -> {
            if (token == null || token.isBlank()) {
                return VnptResult.error("Missing token");
            }

            if (update == null) {
                return VnptResult.error("Missing update payload");
            }

            try {
                log.info("UpdateProcess REQUEST: {}", mapper.writeValueAsString(update));
            } catch (JsonProcessingException e) {
                log.warn("Cannot serialize update payload");
            }

            String raw = vnptRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> bearer(h, token))
                    .body(update)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            log.info("UpdateProcess RESPONSE: {}", raw);

            int max = 4000;
            String clipped = raw.length() > max ? raw.substring(0, max) + "..." : raw;

            try {
                return mapper.readValue(raw, new TypeReference<VnptResult<VnptDocumentDto>>() {
                });
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nRAW=" + clipped);
            }
        });
    }

    public VnptResult<VnptDocumentDto> sendProcess(String token, String documentId) {
        final String uri = "/api/documents/send-process/{documentId}";
        return safeCall(HttpMethod.POST, uri, () -> {
            if (token == null || token.isBlank()) {
                return VnptResult.error("Missing token");
            }

            if (documentId == null || documentId.isBlank()) {
                return VnptResult.error("Missing documentId");
            }

            String raw = vnptRestClient.post()
                    .uri(uri, documentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> bearer(h, token))
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                return VnptResult.error("VNPT returned empty body");
            }

            int max = 4000;
            String clipped = raw.length() > max ? raw.substring(0, max) + "..." : raw;
            try {
                return VnptResult.success(mapper.readValue(raw, VnptDocumentDto.class));
            } catch (Exception e) {
                return VnptResult.error("Cannot parse VNPT response: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nRAW=" + clipped);
            }
        });
    }

    @Override
    public VnptResult<ProcessResponse> signProcess(VnptProcessDto process) {
        final String uri = "/api/documents/process";
        return safeCall(HttpMethod.POST, uri, () -> {
            var entity = vnptRestClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> bearer(h, process.token()))
                    .body(process)
                    .retrieve()
                    .toEntity(String.class);

            var status = entity.getStatusCode();
            var raw = entity.getBody();

            if (!status.is2xxSuccessful()) {
                return VnptResult.error("HTTP " + status.value() + " " + HttpMethod.POST + " " + uri
                        + (raw == null ? "" : "\n" + raw));
            }
            if (raw == null || raw.isBlank()) return VnptResult.error("VNPT returned empty body");
            return parseResult(raw, ProcessResponse.class);
        });
    }

    @Override
    public VnptResult<VnptDocumentDto> getEContractById(String documentId, String token) {
        final String uri = "/api/documents/{id}";
        return safeCall(HttpMethod.GET, uri, () -> {

            var raw = vnptRestClient.get()
                    .uri(uri, documentId)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> bearer(h, token))
                    .retrieve()
                    .body(String.class);

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
                log.info("Parsed dto id={}", dto.id());
                return VnptResult.success(dto);

            } catch (Exception e) {
                log.error("Parse failed: {}", e.getMessage());
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
        if (string == null || string.isBlank())
            throw new IllegalArgumentException(msg);

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
            log.error("Parse failed: {}", e.getMessage());
            return VnptResult.error("Cannot parse: " + e.getMessage());
        }
    }

    private <T> VnptResult<List<T>> parseListResult(String raw, Class<T> elementClass) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode dataNode = root.get("data");
            if (dataNode == null || dataNode.isNull()) {
                return VnptResult.error("Missing data field");
            }
            List<T> list = mapper.treeToValue(dataNode,
                    mapper.getTypeFactory().constructCollectionType(List.class, elementClass));
            return VnptResult.success(list);
        } catch (Exception e) {
            log.error("Parse failed: {}", e.getMessage());
            return VnptResult.error("Cannot parse: " + e.getMessage());
        }
    }
}
