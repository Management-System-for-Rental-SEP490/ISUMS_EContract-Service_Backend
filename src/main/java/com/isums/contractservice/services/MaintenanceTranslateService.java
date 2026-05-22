package com.isums.contractservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceTranslateService {

    private final TranslateClient translateClient;

    public String translate(String sourceText, String targetLangCode) {
        if (sourceText == null || sourceText.isBlank()) return "";
        String target = targetLangCode.toLowerCase();
        try {
            return translateClient.translateText(TranslateTextRequest.builder()
                    .sourceLanguageCode("vi")
                    .targetLanguageCode(target)
                    .text(sourceText)
                    .build()).translatedText();
        } catch (Exception ex) {
            log.warn("[MaintenanceTranslate] fallback target={} len={} err={}",
                    target, sourceText.length(), ex.getMessage());
            return sourceText;
        }
    }
}
