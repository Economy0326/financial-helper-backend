package com.financialhelper.source;

import java.time.LocalDate;
import java.util.Optional;

/** A narrow attribution for one independently checked official attachment. */
public record VerifiedPdfMetadata(String title, LocalDate publishedAt, String publisher,
                           String metadataSourceUrl, String pdfUrl, String rawSha256) {
    public static final String DEPOSIT_PDF_URL =
            "https://www.fsc.go.kr/comm/getFile?srvcId=BBSTY1&upperNo=84975&fileTy=ATTACH&fileNo=1";
    public static final String DEPOSIT_POST_URL = "https://www.fsc.go.kr/po020201/84975";
    public static final String DEPOSIT_RAW_SHA256 =
            "1b9df7cae3c3563f08e26194abb1c9735cd81dbe08dd79c9d7198066bcd4e989";

    static Optional<VerifiedPdfMetadata> forFetched(SourceIngestionData.Snapshot source,
                                                     SourceIngestionData.Fetched fetched,
                                                     String rawSha256) {
        if (source.acquisitionType() != SourceAcquisitionType.PDF
                || !"fsc.go.kr".equals(source.officialDomain())
                || !"금융위원회".equals(source.organizationName())
                || !DEPOSIT_PDF_URL.equals(source.canonicalUrl())
                || !DEPOSIT_PDF_URL.equals(fetched.resolvedUrl())
                || !DEPOSIT_RAW_SHA256.equals(rawSha256)) {
            return Optional.empty();
        }
        // Title/date are attributed to the FSC HTML post, never PDF internals.
        return Optional.of(new VerifiedPdfMetadata("예금보호한도 상향 관련 주요 QA",
                LocalDate.of(2025, 7, 22), "금융위원회", DEPOSIT_POST_URL,
                DEPOSIT_PDF_URL, DEPOSIT_RAW_SHA256));
    }
}
