package com.financialhelper.source;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
public class SourceUrlPolicy {

    public URI validate(
            String url,
            String officialDomain
    ) {
        try {
            return validate(
                    URI.create(url),
                    officialDomain
            );
        } catch (
                IllegalArgumentException exception
        ) {
            throw new SourceIngestionException(
                    "INVALID_SOURCE_URL",
                    "Source URL is invalid",
                    exception
            );
        }
    }

    public URI validate(
            URI uri,
            String officialDomain
    ) {
        String scheme =
                uri.getScheme();

        String host =
                uri.getHost();

        if (
                !"https".equalsIgnoreCase(scheme)
                        || host == null
                        || uri.getUserInfo() != null
        ) {
            throw new SourceIngestionException(
                    "SOURCE_URL_NOT_ALLOWED",
                    "Only credential-free HTTPS official URLs are allowed"
            );
        }

        String normalizedHost =
                host.toLowerCase(
                        Locale.ROOT
                );

        String normalizedDomain =
                officialDomain.toLowerCase(
                        Locale.ROOT
                );

        boolean matchesOfficialDomain =
                normalizedHost.equals(
                        normalizedDomain
                )
                        || normalizedHost.endsWith(
                        "."
                                + normalizedDomain
                );

        if (!matchesOfficialDomain) {
            throw new SourceIngestionException(
                    "SOURCE_DOMAIN_NOT_ALLOWED",
                    "Source URL is outside the configured official domain"
            );
        }

        return uri.normalize();
    }
}