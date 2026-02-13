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

@Component
@RequiredArgsConstructor
public class EContractTemplateSeeder implements ApplicationRunner {

    private final EContractTemplateRepository templetRepository;
    private final ResourceLoader resourceLoader;

    private static final String CODE = "LEASE_HOUSE";
    private static final String NAME = "Hợp đồng thuê nhà";
    private static final String RESOURCE_PATH = "classpath:templates/econtract/lease-house-vn-v1.html";

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) throws Exception {
        String html = readUtf8(RESOURCE_PATH);

        templetRepository.findByCode(CODE).ifPresentOrElse(existing -> {
            boolean changed = !safeEquals(existing.getName(), NAME) || !safeEquals(existing.getContentHtml(), html);
            if (changed) {
                existing.setName(NAME);
                existing.setContentHtml(html);
                templetRepository.save(existing);
            }
        }, () -> {
            templetRepository.save(EContractTemplate.builder()
                    .code(CODE)
                    .name(NAME)
                    .contentHtml(html)
                    .createdAt(Instant.now())
                    .build());
        });
    }

    private String readUtf8(String path) throws Exception {
        Resource r = resourceLoader.getResource(path);
        if (!r.exists()) {
            throw new IllegalStateException("Template file not found: " + path);
        }

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
