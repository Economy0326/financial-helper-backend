package com.financialhelper.source;

import java.util.Map;
import java.util.UUID;

/** Existing seeded HTML sources retain their prior ingestion contract; not a rights approval. */
final class LegacyHtmlSourcePolicy {
    private static final Map<UUID, String> SEEDED = Map.ofEntries(
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000001"), "fsc-2026-alert"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000002"), "police-response"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000003"), "police-campaign"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000004"), "police-reporting"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000005"), "fsc-card"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000006"), "kisa-118"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000007"), "fsc-personal-info"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000104"), "kb-card-compensation-process"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000105"), "kb-card-loss-report-ars"),
            Map.entry(UUID.fromString("20000000-0000-0000-0000-000000000106"), "kb-card-terms-amendment-260402"));

    static boolean allowsLegacyPath(UUID id, String key) {
        return id != null && key != null && (OfficialInformationClaimSelector.supports(key) || key.equals(SEEDED.get(id)));
    }
    private LegacyHtmlSourcePolicy() {}
}
