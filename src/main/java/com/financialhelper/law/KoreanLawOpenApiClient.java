package com.financialhelper.law;

import java.time.LocalDate;
import java.util.List;

/** 공식 Korean Law Open API의 typed 경계다. */
public interface KoreanLawOpenApiClient {

    List<LawVersion> searchLaw(String lawName);

    LawDocument getLawText(LawVersion version, String articleLocator);

    record LawVersion(
            String statuteName,
            String lawIdentifier,
            String mst,
            LocalDate promulgationDate,
            LocalDate effectiveDate,
            boolean current,
            String derivedUrl
    ) {
        public LawVersion {
            requireText(statuteName, "statuteName");
            requireText(lawIdentifier, "lawIdentifier");
            requireText(mst, "mst");
            if (promulgationDate == null || effectiveDate == null) {
                throw new IllegalArgumentException("law version dates are required");
            }
            requireText(derivedUrl, "derivedUrl");
        }
    }

    record LawDocument(
            String statuteName,
            String lawIdentifier,
            String mst,
            String articleLocator,
            String articleText,
            LocalDate promulgationDate,
            LocalDate effectiveDate,
            String derivedUrl
    ) {
        public LawDocument {
            requireText(statuteName, "statuteName");
            requireText(lawIdentifier, "lawIdentifier");
            requireText(mst, "mst");
            requireText(articleLocator, "articleLocator");
            requireText(articleText, "articleText");
            if (promulgationDate == null || effectiveDate == null) {
                throw new IllegalArgumentException("law document dates are required");
            }
            requireText(derivedUrl, "derivedUrl");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
