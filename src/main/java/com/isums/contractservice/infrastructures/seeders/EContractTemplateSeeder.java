package com.isums.contractservice.infrastructures.seeders;

import com.isums.contractservice.domains.entities.EContractTemplate;
import com.isums.contractservice.infrastructures.repositories.EContractTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Seeds the DB-backed lease-house templates from classpath resources on startup.
 * Upserts three codes:
 *   LEASE_HOUSE          — legacy VN-only template (kept for backward compat).
 *   LEASE_HOUSE_VI       — current VN-only lease-house-vn-v1.html.
 *   LEASE_HOUSE_BILINGUAL — two-column bilingual lease-house-bilingual-v1.html.
 */
@Component
@RequiredArgsConstructor
public class EContractTemplateSeeder implements ApplicationRunner {

    private final EContractTemplateRepository templetRepository;
    private final ResourceLoader resourceLoader;

    private record Seed(String code, String name, String resourcePath) {}

    private static final List<Seed> SEEDS = List.of(
            new Seed("LEASE_HOUSE", "Hợp đồng thuê nhà (legacy)",
                    "classpath:templates/econtract/lease-house-vn-v1.html"),
            new Seed("LEASE_HOUSE_VI", "Hợp đồng thuê nhà ở — VN",
                    "classpath:templates/econtract/lease-house-vn-v1.html"),
            new Seed("LEASE_HOUSE_BILINGUAL", "Hợp đồng thuê nhà ở — song ngữ",
                    "classpath:templates/econtract/lease-house-bilingual-v1.html")
    );

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) throws Exception {
        for (Seed s : SEEDS) {
            String html = readUtf8(s.resourcePath());
            templetRepository.findByCode(s.code()).ifPresentOrElse(existing -> {
                boolean changed = !safeEquals(existing.getName(), s.name())
                        || !safeEquals(existing.getContentHtml(), html);
                if (changed) {
                    existing.setName(s.name());
                    existing.setContentHtml(html);
                    templetRepository.save(existing);
                }
            }, () -> templetRepository.save(EContractTemplate.builder()
                    .code(s.code())
                    .name(s.name())
                    .contentHtml(html)
                    .createdAt(Instant.now())
                    .build()));
        }
    }

    private String readUtf8(String path) throws Exception {
        Resource r = resourceLoader.getResource(path);
        if (!r.exists()) throw new IllegalStateException("Template file not found: " + path);
        try (var is = r.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean safeEquals(String s1, String s2) {
        if (s1 == null && s2 == null) return true;
        if (s1 == null) return false;
        return s1.equals(s2);
    }
}
