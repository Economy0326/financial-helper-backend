package com.financialhelper.source;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class HttpSourceContentFetcher
        implements SourceContentFetcher {

    private final SourceIngestionProperties
            properties;

    private final SourceUrlPolicy
            urlPolicy;

    private final HttpClient
            httpClient;

    public HttpSourceContentFetcher(
            SourceIngestionProperties properties,
            SourceUrlPolicy urlPolicy
    ) {
        this.properties =
                properties;

        this.urlPolicy =
                urlPolicy;

        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                properties.connectTimeout()
                        )
                        // 직접 redirect 목적지 검사를 하기 위해 NEVER로 설정
                        .followRedirects(
                                HttpClient.Redirect.NEVER
                        )
                        .build();
    }

    @Override
    public SourceIngestionData.Fetched fetch(
            SourceIngestionData.Snapshot source
    ) {
        URI currentUri =
                urlPolicy.validate(
                        source.canonicalUrl(),
                        source.officialDomain()
                );

        for (
                int redirectCount = 0;
                redirectCount
                        <= properties.maxRedirects();
                redirectCount++
        ) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(currentUri)
                            .timeout(
                                    properties.requestTimeout()
                            )
                            .header(
                                    "User-Agent",
                                    properties.userAgent()
                            )
                            .header(
                                    "Accept",
                                    "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1"
                            )
                            .GET()
                            .build();

            // 실제 인터넷 요청 실행
            HttpResponse<InputStream> response =
                    send(request);

            int status =
                    response.statusCode();

            if (isRedirect(status)) {

                // 리소스 누수 막기
                closeQuietly(
                        response.body()
                );

                // 새로운 URL 가져오기
                String location =
                        response.headers()
                                .firstValue(
                                        "Location"
                                )
                                .orElseThrow(() ->
                                        new SourceIngestionException(
                                                "SOURCE_REDIRECT_WITHOUT_LOCATION",
                                                "Official source returned an invalid redirect"
                                        )
                                );
                URI redirectedUri =
                        currentUri.resolve(
                                location
                        );

                // Redirect 이후 새로운 URL 다시 검사
                currentUri =
                        urlPolicy.validate(
                                redirectedUri,
                                source.officialDomain()
                        );

                continue;
            }

            if (
                    status < 200
                            || status >= 300
            ) {
                closeQuietly(
                        response.body()
                );

                throw new SourceIngestionException(
                        "SOURCE_HTTP_ERROR",
                        "Official source returned HTTP "
                                + status
                );
            }

            // 응답 크기 제한
            byte[] body =
                    readBounded(
                            response.body(),
                            properties.maxResponseBytes()
                    );

            if (body.length == 0) {
                throw new SourceIngestionException(
                        "SOURCE_EMPTY_RESPONSE",
                        "Official source returned an empty response"
                );
            }

            return new SourceIngestionData.Fetched(
                    
                    // 최종 URL
                    currentUri.toString(),

                    response.headers()
                            .firstValue(
                                    "Content-Type"
                            )
                            .orElse(null),

                    response.headers()
                            .firstValue(
                                    "ETag"
                            )
                            .orElse(null),

                    response.headers()
                            .firstValue(
                                    "Last-Modified"
                            )
                            .orElse(null),

                    body,

                    OffsetDateTime.now(
                            ZoneOffset.UTC
                    )
            );
        }

        throw new SourceIngestionException(
                "SOURCE_TOO_MANY_REDIRECTS",
                "Official source exceeded the redirect limit"
        );
    }

    // 실제 HTTP 요청
    private HttpResponse<InputStream> send(
            HttpRequest request
    ) {
        try {
            return httpClient.send(
                    request,
                    HttpResponse.BodyHandlers
                            .ofInputStream()
            );
        } catch (
                InterruptedException exception
        ) {
            // catch 이후 interrupt 상태가 지워질 수도 있으니
            // 한 번 더 호출해서 상위 로직에 전달
            Thread.currentThread()
                    .interrupt();

            throw new SourceIngestionException(
                    "SOURCE_REQUEST_INTERRUPTED",
                    "Official source request was interrupted",
                    exception
            );

        } catch (
                IOException exception
        ) {
            throw new SourceIngestionException(
                    "SOURCE_REQUEST_FAILED",
                    "Official source request failed",
                    exception
            );
        }
    }

    private byte[] readBounded(
            InputStream inputStream,
            long maxBytes
    ) {
        if (
                maxBytes
                        >= Integer.MAX_VALUE
        ) {
            throw new SourceIngestionException(
                    "SOURCE_CONFIGURATION_INVALID",
                    "maxResponseBytes is too large"
            );
        }

        try (inputStream) {

            byte[] bytes =
                    inputStream.readNBytes(
                            (int) maxBytes + 1
                    );

            if (
                    bytes.length
                            > maxBytes
            ) {
                throw new SourceIngestionException(
                        "SOURCE_RESPONSE_TOO_LARGE",
                        "Official source response exceeded the configured limit"
                );
            }

            return bytes;

        } catch (
                IOException exception
        ) {
            throw new SourceIngestionException(
                    "SOURCE_RESPONSE_READ_FAILED",
                    "Could not read official source response",
                    exception
            );
        }
    }

    private boolean isRedirect(
            int status
    ) {
        return status == 301
                || status == 302
                || status == 303
                || status == 307
                || status == 308;
    }

    private void closeQuietly(
            InputStream inputStream
    ) {
        try {
            inputStream.close();

        } catch (
                IOException ignored
        ) {
            // Redirect body는 사용하지 않음.
        }
    }
}