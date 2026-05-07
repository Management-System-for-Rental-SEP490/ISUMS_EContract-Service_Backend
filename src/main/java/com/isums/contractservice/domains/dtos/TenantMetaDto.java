package com.isums.contractservice.domains.dtos;

/**
 * Minimal tenant-facing metadata for the outsystem confirm page so it knows
 * which identity modal to route to (CccdModal vs PassportModal) and which
 * locale to boot its i18n in. Kept narrow on purpose — the confirm page
 * does NOT need the full contract state, and leaking extra fields (rent
 * amount, house id, etc.) over a magic-token-authenticated endpoint
 * would widen the attack surface if the token were ever exposed.
 *
 * @param tenantType        "VIETNAMESE" or "FOREIGNER" (enum name, not enum).
 *                          FOREIGNER → outsystem routes to PassportModal;
 *                          VIETNAMESE → CccdModal.
 * @param contractLanguage  "VI", "VI_EN", or "VI_JA" (enum name). Outsystem
 *                          can use this as a secondary boot-locale signal
 *                          if the `?lang=` query param got stripped.
 */
public record TenantMetaDto(
        String tenantType,
        String contractLanguage
) {}
