package com.isums.contractservice.infrastructures.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.common.i18n.TranslationMap;
import com.isums.common.i18n.events.TextTranslationResultEvent;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.EContractTemplate;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EContractTranslationResultListener {

    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    @KafkaListener(topics = EContractTranslationRequester.CALLBACK_TOPIC,
            groupId = "econtract-translation-result")
    @Transactional
    public void onResult(String payload, Acknowledgment ack) {
        try {
            TextTranslationResultEvent ev = objectMapper.readValue(payload, TextTranslationResultEvent.class);
            if (!TextTranslationResultEvent.STATUS_DONE.equals(ev.status())
                    || ev.translatedText() == null || ev.translatedText().isBlank()) {
                ack.acknowledge();
                return;
            }
            apply(ev);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to apply econtract translation result", e);
            ack.acknowledge();
        }
    }

    private void apply(TextTranslationResultEvent ev) {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put(ev.targetLanguage(), ev.translatedText());

        switch (ev.resourceType()) {
            case "econtract.name" -> {
                EContract c = entityManager.find(EContract.class, ev.resourceId());
                if (c == null) return;
                TranslationMap before = c.getNameTranslations() == null ? TranslationMap.empty() : c.getNameTranslations();
                c.setNameTranslations(before.mergeAutoFilled(patch));
                entityManager.merge(c);
            }
            case "econtract-template.name" -> {
                EContractTemplate t = entityManager.find(EContractTemplate.class, ev.resourceId());
                if (t == null) return;
                TranslationMap before = t.getNameTranslations() == null ? TranslationMap.empty() : t.getNameTranslations();
                t.setNameTranslations(before.mergeAutoFilled(patch));
                entityManager.merge(t);
            }
            default -> log.warn("Unknown resourceType for econtract translation: {}", ev.resourceType());
        }
    }
}
