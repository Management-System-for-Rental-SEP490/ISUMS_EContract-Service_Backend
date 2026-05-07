package com.isums.contractservice.domains.enums;

import java.util.Arrays;

/**
 * Allowlist of legal template keys recognised by the system. Pinning to an enum
 * prevents typos at admin write-time (POST /api/legal-templates) from creating
 * orphan rows that no service consumer can resolve.
 *
 * <p>Adding a new key requires:
 * <ol>
 *   <li>Adding a value here.</li>
 *   <li>Seeding at least the VI text via a Flyway migration.</li>
 *   <li>Wiring up a service consumer that calls
 *       {@code LegalTemplateService.resolveSnapshot(KEY.name(), contractLang)}.</li>
 * </ol>
 */
public enum LegalTemplateKey {
    RELOCATION_LANDLORD_FAULT_BASIS,
    RELOCATION_ACTIVE_LEASE_UPGRADE_BASIS;

    public static boolean isValid(String key) {
        if (key == null) return false;
        return Arrays.stream(values()).anyMatch(k -> k.name().equals(key));
    }
}
