package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.ContractTranslation;
import com.isums.contractservice.infrastructures.repositories.ContractTranslationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.Formality;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;
import software.amazon.awssdk.services.translate.model.TranslationSettings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Translates contract text fragments between vi / en / ja using AWS Translate.
 * Caches results in contract_translations keyed by SHA-256 so boilerplate clauses
 * are billed once across all contracts.
 *
 * Legal tone: Formality=FORMAL on supported target languages.
 * AWS Translate supports FORMAL for ja but NOT for en — so we skip the setting
 * when the target doesn't support it (otherwise AWS rejects the request).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractTranslationService {

    public static final List<String> SUPPORTED = List.of("vi", "en", "ja");

    // AWS Translate accepts Formality only for a specific list of target languages.
    // ja is on the list, en is not. Source: AWS docs — "supported-languages-formality".
    private static final Set<String> FORMALITY_SUPPORTED_TARGETS = Set.of("ja");

    private final TranslateClient translateClient;
    private final ContractTranslationRepository translationRepository;

    @Transactional
    public String translate(String text, String sourceLanguage, String targetLanguage) {
        if (text == null || text.isBlank()) return text;
        if (sourceLanguage.equalsIgnoreCase(targetLanguage)) return text;

        String src = normalize(sourceLanguage);
        String tgt = normalize(targetLanguage);
        validateLanguage(src);
        validateLanguage(tgt);

        String hash = sha256(text);

        Optional<ContractTranslation> hit = translationRepository
                .findBySourceHashAndSourceLanguageAndTargetLanguage(hash, src, tgt);
        if (hit.isPresent()) {
            translationRepository.incrementHit(hit.get().getId());
            return hit.get().getTranslatedText();
        }

        String translated = callAws(text, src, tgt);

        ContractTranslation row = ContractTranslation.builder()
                .sourceHash(hash)
                .sourceLanguage(src)
                .targetLanguage(tgt)
                .sourceText(text)
                .translatedText(translated)
                .hitCount(1L)
                .build();
        translationRepository.save(row);
        return translated;
    }

    private String callAws(String text, String src, String tgt) {
        try {
            TranslateTextRequest.Builder builder = TranslateTextRequest.builder()
                    .text(text)
                    .sourceLanguageCode(src)
                    .targetLanguageCode(tgt);

            if (FORMALITY_SUPPORTED_TARGETS.contains(tgt)) {
                builder.settings(TranslationSettings.builder()
                        .formality(Formality.FORMAL)
                        .build());
            }

            return translateClient.translateText(builder.build()).translatedText();
        } catch (Exception ex) {
            log.warn("AWS Translate failed {} -> {}: {}", src, tgt, ex.getMessage());
            // Fallback: return source text so contract rendering does not break mid-flow.
            return text;
        }
    }

    private static String normalize(String lang) {
        return lang == null ? "" : lang.trim().toLowerCase();
    }

    private static void validateLanguage(String code) {
        if (!SUPPORTED.contains(code)) {
            throw new IllegalArgumentException("Unsupported contract language: " + code);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
