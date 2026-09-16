package com.financialhelper.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// fetch 도중 SourceRegistry가 변경됐는지 나중에 다시 검증할 수 있도록 수집 시작 시점의 source 정의를 Snapshot으로 보존
// 객체를 단계별로 나눠서 문제 발생 시 fetch / parse / save 중 어디서 발생했는지 특정 가능
public final class SourceIngestionData {

    private SourceIngestionData() {
    }

    public record Snapshot(
            UUID sourceRegistryId,
            String sourceKey,
            String organizationName,
            String officialDomain,
            String canonicalUrl,
            SourceAcquisitionType acquisitionType,
            String contentSelector
    ) {

        public static Snapshot from(
                SourceRegistry source
        ) {
            return new Snapshot(
                    source.getId(),
                    source.getSourceKey(),
                    source.getOrganizationName(),
                    source.getOfficialDomain(),
                    source.getCanonicalUrl(),
                    source.getAcquisitionType(),
                    source.getContentSelector()
            );
        }

    }

    public record Fetched(
            String resolvedUrl,
            String contentType,
            String httpEtag,
            String httpLastModified,
            byte[] originalContent,
            OffsetDateTime retrievedAt
    ) {
    }

    public record Parsed(
            String resolvedUrl,
            String title,
            LocalDate publishedAt,
            OffsetDateTime retrievedAt,
            String contentType,
            String httpEtag,
            String httpLastModified,
            String rawSha256,
            String contentSha256,
            byte[] originalContent,
            String normalizedContent
    ) {
    }

}