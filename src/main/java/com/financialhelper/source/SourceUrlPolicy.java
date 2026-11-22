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
        return validate(uri, officialDomain, false);
    }

    /** Canonicalize user-submitted registry URLs without relaxing fetch or redirect validation. */
    public URI normalizeRegisteredUrl(String url, String officialDomain) {
        try {
            URI original = URI.create(url);
            validate(original, officialDomain, true);
            var kept = new java.util.ArrayList<String>();
            if (original.getRawQuery() != null) for (String parameter : original.getRawQuery().split("&", -1)) {
                String key = parameter.split("=", 2)[0];
                if (!java.util.Set.of("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid", "fbclid").contains(key)) kept.add(parameter);
            }
            String query = kept.isEmpty() ? "" : "?" + String.join("&", kept);
            String path = original.getRawPath() == null ? "" : original.getRawPath();
            URI canonical = URI.create("https://" + original.getHost().toLowerCase(Locale.ROOT) + path + query).normalize();
            return validate(canonical, officialDomain);
        } catch (IllegalArgumentException exception) {
            throw new SourceIngestionException("INVALID_SOURCE_URL", "Source URL is invalid", exception);
        }
    }

    private URI validate(URI uri, String officialDomain, boolean allowFragment) {
        String scheme =
                uri.getScheme();

        String host =
                uri.getHost();

        if (
                !"https".equalsIgnoreCase(scheme)
                        || host == null
                        || uri.getUserInfo() != null
                        || uri.getPort() != -1 && uri.getPort() != 443
                        || !allowFragment && uri.getFragment() != null
                        || officialDomain == null || officialDomain.isBlank()
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

        // Registry entries must be domain names, never IP literals or local hostnames.
        if (!normalizedDomain.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                || normalizedDomain.matches("[0-9.]+")
                || normalizedDomain.endsWith(".local") || normalizedDomain.endsWith(".localhost")) {
            throw new SourceIngestionException("SOURCE_DOMAIN_NOT_ALLOWED", "Official domain is not public");
        }

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
