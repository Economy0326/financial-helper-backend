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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@org.springframework.context.annotation.Primary
public class HttpSourceContentFetcher
        implements SourceContentFetcher {

    private static final Semaphore BODY_READ_SLOTS = new Semaphore(8);

    private final SourceIngestionProperties
            properties;

    private final SourceUrlPolicy
            urlPolicy;

    private final HttpClient
            httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpSourceContentFetcher(
            SourceIngestionProperties properties,
            SourceUrlPolicy urlPolicy
    ) {
        this(properties, urlPolicy, HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    HttpSourceContentFetcher(SourceIngestionProperties properties, SourceUrlPolicy urlPolicy, HttpClient httpClient) {
        this.properties =
                properties;

        this.urlPolicy =
                urlPolicy;

        this.httpClient = httpClient;
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
                                    "text/html,application/xhtml+xml;q=0.9,"
                                            + "application/pdf,application/x-hwp,"
                                            + "application/vnd.hancom.hwp,"
                                            + "application/octet-stream;q=0.5"
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
                        "SOURCE_HTTP_ERROR", "Official source returned HTTP " + status, status, currentUri.toString());
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

                    OffsetDateTime.now(ZoneOffset.UTC),
                    status
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

        } catch (java.net.http.HttpTimeoutException exception) {
            throw new SourceIngestionException("SOURCE_REQUEST_TIMEOUT", "Official source request timed out", exception);
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

        if (!BODY_READ_SLOTS.tryAcquire()) {
            closeQuietly(inputStream);
            throw new SourceIngestionException("SOURCE_RESPONSE_READER_BUSY", "Official source reader is busy");
        }
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().name("official-source-body-reader").start(() -> {
            try (inputStream) {
                completed.complete(inputStream.readNBytes((int) maxBytes + 1));
            } catch (IOException | RuntimeException exception) {
                completed.completeExceptionally(exception);
            } finally {
                BODY_READ_SLOTS.release();
            }
        });
        try {
            byte[] bytes = completed.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (bytes.length > maxBytes) {
                throw new SourceIngestionException("SOURCE_RESPONSE_TOO_LARGE", "Official source response exceeded the configured limit");
            }
            return bytes;
        } catch (TimeoutException exception) {
            reader.interrupt();
            throw new SourceIngestionException("SOURCE_REQUEST_TIMEOUT", "Official source response timed out", exception);
        } catch (InterruptedException exception) {
            reader.interrupt();
            Thread.currentThread().interrupt();
            throw new SourceIngestionException("SOURCE_REQUEST_INTERRUPTED", "Official source request was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new SourceIngestionException("SOURCE_RESPONSE_READ_FAILED", "Could not read official source response", exception.getCause());
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
