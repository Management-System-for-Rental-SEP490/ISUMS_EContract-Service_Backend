package com.isums.contractservice.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class HcmNationalityClient {

    public record NationalityItem(
            @JsonProperty("code") String code,
            @JsonProperty("nameVi") String nameVi,
            @JsonProperty("nameEn") String nameEn,
            @JsonProperty("nameJa") String nameJa,
            @JsonProperty("used") Boolean used
    ) {

        @JsonProperty("name")
        public String name() { return nameVi; }

        @JsonProperty("description")
        public String description() { return nameEn; }
    }

    private static final Duration TTL = Duration.ofHours(24);
    private static final String STATIC_RESOURCE = "data/nationalities.json";

    private final String source;
    private final RestClient restCountries;

    private final RestClient hcmHttp;
    private final String hcmAuthHeader;
    private final ObjectMapper jackson = new ObjectMapper();

    private final AtomicReference<Cached> cache = new AtomicReference<>();
    private record Cached(List<NationalityItem> items, Instant expiresAt) { }

    public HcmNationalityClient(

            @Value("${country.source:static}") String source,
            @Value("${country.rest.base-url:https://restcountries.com}") String restBaseUrl,
            @Value("${hcm.esb.base-url:https://hcmesb-test.tphcm.gov.vn}") String hcmBaseUrl,
            @Value("${hcm.esb.access-key:rTkhYCBwHM}") String accessKey,
            @Value("${hcm.esb.secret-key:DWkQgY1YSS}") String secretKey,
            @Value("${hcm.esb.app-name:TPHCM}") String appName,
            @Value("${hcm.esb.partner-code:000.00.01.H29}") String partnerCode,
            @Value("${hcm.esb.partner-code-cus:000.00.01.H29}") String partnerCodeCus
    ) {
        this.source = source.toLowerCase(Locale.ROOT);
        this.restCountries = RestClient.builder().baseUrl(restBaseUrl).build();

        this.hcmHttp = RestClient.builder().baseUrl(hcmBaseUrl).build();
        String hcmPayload = String.format(
                "{\"AccessKey\":\"%s\",\"SecretKey\":\"%s\",\"AppName\":\"%s\","
                + "\"PartnerCode\":\"%s\",\"PartnerCodeCus\":\"%s\"}",
                accessKey, secretKey, appName, partnerCode, partnerCodeCus);
        this.hcmAuthHeader = Base64.getEncoder().encodeToString(
                hcmPayload.getBytes(StandardCharsets.UTF_8));

        log.info("[Countries] source={} rest={} hcm={}", this.source, restBaseUrl, hcmBaseUrl);
    }

    public List<NationalityItem> getAll() {
        Cached c = cache.get();
        if (c != null && Instant.now().isBefore(c.expiresAt())) {
            return c.items();
        }
        try {
            return refresh();
        } catch (Exception ex) {
            log.warn("[Countries] fetch failed — {}. Returning cached={}",
                    ex.getMessage(), c != null);
            return c != null ? c.items() : Collections.emptyList();
        }
    }

    private synchronized List<NationalityItem> refresh() {
        Cached c = cache.get();
        if (c != null && Instant.now().isBefore(c.expiresAt())) {
            return c.items();
        }
        List<NationalityItem> items = switch (source) {
            case "hcm"    -> fetchFromHcm();
            case "rest"   -> fetchFromRestCountries();
            case "static" -> loadFromResource();
            default        -> loadFromResource();
        };
        cache.set(new Cached(items, Instant.now().plus(TTL)));
        log.info("[Countries] cached {} countries (source={})", items.size(), source);
        return items;
    }

    private List<NationalityItem> loadFromResource() {
        log.info("[Countries] loading bundled catalogue from {}", STATIC_RESOURCE);
        ClassPathResource res = new ClassPathResource(STATIC_RESOURCE);
        try (InputStream in = res.getInputStream()) {
            JsonNode arr = jackson.readTree(in);
            if (!arr.isArray()) {
                log.error("[Countries] {} did not parse as a JSON array", STATIC_RESOURCE);
                return Collections.emptyList();
            }
            List<NationalityItem> out = new ArrayList<>(arr.size());
            for (JsonNode el : arr) {
                String code = textOrNull(el, "code");
                if (code == null || code.isBlank()) continue;
                String vi = textOrNull(el, "vi");
                String en = textOrNull(el, "en");
                String ja = textOrNull(el, "ja");
                out.add(new NationalityItem(
                        code,
                        firstNonBlank(vi, en, code),
                        firstNonBlank(en, vi, code),
                        firstNonBlank(ja, en, vi, code),
                        Boolean.TRUE));
            }
            Collator vn = Collator.getInstance(new Locale("vi"));
            out.sort((a, b) -> vn.compare(
                    a.nameVi() == null ? "" : a.nameVi(),
                    b.nameVi() == null ? "" : b.nameVi()));
            return out;
        } catch (Exception e) {

            log.error("[Countries] failed to load {}: {}", STATIC_RESOURCE, e.toString());
            return Collections.emptyList();
        }
    }

    private List<NationalityItem> fetchFromRestCountries() {
        log.info("[Countries] fetching from REST Countries /v3.1/all");
        String body = restCountries.get()
                .uri("/v3.1/all?fields=name,cca3,translations")
                .retrieve()
                .body(String.class);
        try {
            JsonNode arr = jackson.readTree(body);
            if (!arr.isArray()) return Collections.emptyList();

            Map<String, String> viByCode = loadViMapFromResource();

            List<NationalityItem> out = new ArrayList<>(arr.size());
            for (JsonNode el : arr) {
                String cca3 = textOrNull(el, "cca3");
                if (cca3 == null || cca3.isBlank()) continue;

                String enName = el.path("name").path("common").asText(null);
                String viName = viByCode.getOrDefault(cca3, enName);
                String jaName = el.path("translations").path("jpn").path("common").asText(null);
                if (jaName == null || jaName.isBlank()) jaName = enName;

                out.add(new NationalityItem(cca3, viName, enName, jaName, Boolean.TRUE));
            }
            Collator vn = Collator.getInstance(new Locale("vi"));
            out.sort((a, b) -> vn.compare(
                    a.nameVi() == null ? "" : a.nameVi(),
                    b.nameVi() == null ? "" : b.nameVi()));
            return out;
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("REST Countries response not valid JSON", e);
        }
    }

    private Map<String, String> loadViMapFromResource() {
        Map<String, String> m = new HashMap<>(220);
        try (InputStream in = new ClassPathResource(STATIC_RESOURCE).getInputStream()) {
            JsonNode arr = jackson.readTree(in);
            if (arr.isArray()) {
                for (JsonNode el : arr) {
                    String code = textOrNull(el, "code");
                    String vi = textOrNull(el, "vi");
                    if (code != null && vi != null) m.put(code, vi);
                }
            }
        } catch (Exception ignore) {

        }
        return m;
    }

    private List<NationalityItem> fetchFromHcm() {
        log.info("[Countries] fetching from HCM ESB /GetDanhMucQuocGia");
        String body = hcmHttp.get()
                .uri("/GetDanhMucQuocGia")
                .header(HttpHeaders.AUTHORIZATION, hcmAuthHeader)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = jackson.readTree(body);
            JsonNode status = root.get("Status");
            if (status == null || !"SUCCESS".equalsIgnoreCase(status.asText())) {
                String desc = root.has("Description") && !root.get("Description").isNull()
                        ? root.get("Description").asText() : "unknown";
                int code = root.has("StatusCode") ? root.get("StatusCode").asInt() : -1;
                throw new IllegalStateException(
                        "HCM ESB returned non-SUCCESS: StatusCode=" + code + " desc=" + desc);
            }
            JsonNode arr = root.get("ResultObject");
            if (arr == null || !arr.isArray()) return Collections.emptyList();
            List<NationalityItem> items = new ArrayList<>(arr.size());
            for (JsonNode el : arr) {
                String alpha3 = textOrNull(el, "MaAlpha3");
                String alpha2 = textOrNull(el, "MaQuocGia");
                String code = (alpha3 != null && !alpha3.isBlank()) ? alpha3 : alpha2;
                String nameVi = textOrNull(el, "TenQuocGiaVietGonBangTiengViet");
                String nameEn = textOrNull(el, "TenQuocGiaVietGonBangTiengAnh");
                String fullVi = textOrNull(el, "TenQuocGiaDayDuBangTiengViet");
                String fullEn = textOrNull(el, "TenQuocGiaDayDuBangTiengAnh");
                String viName = firstNonBlank(nameVi, fullVi, nameEn, fullEn, code);
                String enName = firstNonBlank(nameEn, fullEn, viName);
                String jaName = enName;
                Boolean used = el.has("Used") && !el.get("Used").isNull()
                        ? el.get("Used").asBoolean() : null;
                if (Boolean.FALSE.equals(used)) continue;
                items.add(new NationalityItem(code, viName, enName, jaName, used));
            }
            return items;
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("HCM ESB response not valid JSON", e);
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }
}

