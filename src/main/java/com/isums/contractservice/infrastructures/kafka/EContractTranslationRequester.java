package com.isums.contractservice.infrastructures.kafka;

import com.isums.common.i18n.SupportedLocales;
import com.isums.common.i18n.TranslationMap;
import com.isums.common.i18n.events.TextTranslationRequestedEvent;
import com.isums.common.i18n.events.TranslationIntent;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.EContractTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class EContractTranslationRequester {

    static final String CALLBACK_TOPIC = "text.translation.result.econtract";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${isums.i18n.econtract.auto-translate:true}")
    private boolean autoTranslate;

    @Value("${isums.i18n.econtract.required-locales:vi,en,ja}")
    private String requiredLocalesCsv;

    @Value("${isums.i18n.econtract.default-source:vi}")
    private String defaultSourceLanguage;

    public void requestForContract(EContract c) {
        if (!autoTranslate || c == null || c.getId() == null) return;
        if (c.getName() != null && !c.getName().isBlank()) {
            publish(c.getId(), "econtract.name", "name",
                    c.getName(), c.getNameTranslations());
        }
    }

    public void requestForTemplate(EContractTemplate t) {
        if (!autoTranslate || t == null || t.getId() == null) return;
        if (t.getName() != null && !t.getName().isBlank()) {
            publish(t.getId(), "econtract-template.name", "name",
                    t.getName(), t.getNameTranslations());
        }
    }

    private void publish(UUID id, String resourceType, String fieldName, String text, TranslationMap existing) {
        Set<String> required = parseLocales();
        Set<String> have = existing == null ? Set.of() : existing.languagesPresent();
        List<String> missing = new ArrayList<>();
        for (String loc : required) {
            if (loc.equals(defaultSourceLanguage)) continue;
            if (!have.contains(loc)) missing.add(loc);
        }
        if (missing.isEmpty()) return;

        TextTranslationRequestedEvent ev = new TextTranslationRequestedEvent(
                UUID.randomUUID(),
                resourceType,
                id,
                fieldName,
                text,
                defaultSourceLanguage,
                missing,
                TranslationIntent.LEGAL_REFERENCE,
                Boolean.FALSE,
                Instant.now(),
                CALLBACK_TOPIC);
        try {
            kafkaTemplate.send(TextTranslationRequestedEvent.TOPIC, id.toString(), ev);
        } catch (Exception ex) {
            log.warn("publish econtract translation request failed: {}", ex.toString());
        }
    }

    private Set<String> parseLocales() {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : requiredLocalesCsv.split(",")) {
            String c = TranslationMap.normalizeLanguage(raw);
            if (c != null && SupportedLocales.isSupported(c)) out.add(c);
        }
        if (out.isEmpty()) out.addAll(SupportedLocales.ALL);
        return out;
    }
}
