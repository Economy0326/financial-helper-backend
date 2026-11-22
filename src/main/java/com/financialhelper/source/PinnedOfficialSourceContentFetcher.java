package com.financialhelper.source;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Verification-only HTTPS transport. Each hop pins validated DNS results to the actual TLS socket. */
@Component("pinnedOfficialSourceContentFetcher")
public class PinnedOfficialSourceContentFetcher implements SourceContentFetcher {
    private final SourceIngestionProperties properties;
    private final SourceUrlPolicy urlPolicy;
    private final Resolver resolver;
    private final Predicate<InetAddress> addressPolicy;
    private final Consumer<OkHttpClient.Builder> testTlsConfiguration;

    @org.springframework.beans.factory.annotation.Autowired
    public PinnedOfficialSourceContentFetcher(SourceIngestionProperties properties, SourceUrlPolicy urlPolicy) {
        this(properties, urlPolicy, host -> Arrays.asList(InetAddress.getAllByName(host)),
                PublicIpAddressPolicy::isPublic, ignored -> {});
    }

    // Package-private solely for isolated TLS fixtures; Spring uses the production constructor above.
    PinnedOfficialSourceContentFetcher(SourceIngestionProperties properties, SourceUrlPolicy urlPolicy,
            Resolver resolver, Predicate<InetAddress> addressPolicy, Consumer<OkHttpClient.Builder> testTlsConfiguration) {
        this.properties = properties;
        this.urlPolicy = urlPolicy;
        this.resolver = resolver;
        this.addressPolicy = addressPolicy;
        this.testTlsConfiguration = testTlsConfiguration;
    }

    @Override
    public SourceIngestionData.Fetched fetch(SourceIngestionData.Snapshot source) {
        if (properties.maxResponseBytes() >= Integer.MAX_VALUE) {
            throw new SourceIngestionException("SOURCE_CONFIGURATION_INVALID", "Source response limit is too large");
        }
        URI current = urlPolicy.validate(source.canonicalUrl(), source.officialDomain());
        for (int count = 0; count <= properties.maxRedirects(); count++) {
            if ("korea.kr".equalsIgnoreCase(source.officialDomain()) && !KoreaPolicyNews.allowed(current))
                throw new SourceIngestionException("SOURCE_URL_NOT_ALLOWED", "Only registered policy-news URLs are allowed");
            String host = current.getHost();
            int port = current.getPort() == -1 ? 443 : current.getPort();
            var pinnedDns = new PinnedDns(host, resolver, addressPolicy, properties.connectTimeout());
            var peerGuard = new PeerGuard(pinnedDns, port, addressPolicy);
            var builder = new OkHttpClient.Builder();
            testTlsConfiguration.accept(builder);
            // Apply these settings last so no global proxy or client setting bypasses the route checks.
            OkHttpClient client = builder.proxy(Proxy.NO_PROXY).dns(pinnedDns).eventListener(peerGuard)
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    .protocols(List.of(Protocol.HTTP_1_1))
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                    .connectTimeout(properties.connectTimeout()).readTimeout(properties.requestTimeout())
                    .callTimeout(properties.requestTimeout()).build();
            Request request = new Request.Builder().url(current.toString()).header("User-Agent", properties.userAgent())
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,application/pdf,application/octet-stream;q=0.5")
                    .header("Connection", "close").get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!peerGuard.verified) throw new SecurityException("TLS peer was not verified");
                int status = response.code();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.header("Location");
                    if (location == null) throw new SourceIngestionException("SOURCE_REDIRECT_WITHOUT_LOCATION", "Redirect location missing");
                    current = urlPolicy.validate(current.resolve(location), source.officialDomain());
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new SourceIngestionException("SOURCE_HTTP_ERROR", "Official source returned HTTP " + status,
                            status, current.toString());
                }
                if (response.body() == null) throw new SourceIngestionException("SOURCE_EMPTY_RESPONSE", "Empty source response");
                long length = response.body().contentLength();
                if (length > properties.maxResponseBytes()) {
                    throw new SourceIngestionException("SOURCE_RESPONSE_TOO_LARGE", "Official source response exceeded the configured limit");
                }
                byte[] bytes = response.body().byteStream().readNBytes((int) properties.maxResponseBytes() + 1);
                if (bytes.length > properties.maxResponseBytes()) {
                    throw new SourceIngestionException("SOURCE_RESPONSE_TOO_LARGE", "Official source response exceeded the configured limit");
                }
                if (bytes.length == 0) throw new SourceIngestionException("SOURCE_EMPTY_RESPONSE", "Empty source response");
                return new SourceIngestionData.Fetched(current.toString(), response.header("Content-Type"),
                        response.header("ETag"), response.header("Last-Modified"), bytes,
                        OffsetDateTime.now(ZoneOffset.UTC), status);
            } catch (SecurityException exception) {
                throw new SourceIngestionException("SOURCE_ADDRESS_NOT_ALLOWED", "Official source route failed verification", exception);
            } catch (IOException exception) {
                String code = exception instanceof java.net.SocketTimeoutException
                        || exception instanceof java.io.InterruptedIOException ? "SOURCE_REQUEST_TIMEOUT" : "SOURCE_REQUEST_FAILED";
                if (exception instanceof RejectedAddressException) code = "SOURCE_ADDRESS_NOT_ALLOWED";
                if (exception instanceof DnsTimeoutException) code = "SOURCE_REQUEST_TIMEOUT";
                throw new SourceIngestionException(code, "Official source request failed", exception);
            } finally {
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdown();
            }
        }
        throw new SourceIngestionException("SOURCE_TOO_MANY_REDIRECTS", "Official source exceeded the redirect limit");
    }

    @FunctionalInterface interface Resolver { List<InetAddress> lookup(String host) throws UnknownHostException; }
    static final class RejectedAddressException extends UnknownHostException {
        RejectedAddressException() { super("Official source DNS returned an unsafe address"); }
    }
    static final class DnsTimeoutException extends UnknownHostException {
        DnsTimeoutException() { super("Official source DNS lookup timed out or is unavailable"); }
    }

    static final class PinnedDns implements Dns {
        private static final Semaphore DNS_SLOTS = new Semaphore(8);
        private final String host;
        private final Resolver resolver;
        private final Predicate<InetAddress> policy;
        private final Duration timeout;
        private List<InetAddress> addresses;

        PinnedDns(String host, Resolver resolver, Predicate<InetAddress> policy) {
            this(host, resolver, policy, Duration.ofSeconds(5));
        }

        PinnedDns(String host, Resolver resolver, Predicate<InetAddress> policy, Duration timeout) {
            this.host = host.toLowerCase(Locale.ROOT);
            this.resolver = resolver;
            this.policy = policy;
            this.timeout = timeout;
        }

        @Override public synchronized List<InetAddress> lookup(String requested) throws UnknownHostException {
            if (!host.equals(requested.toLowerCase(Locale.ROOT))) throw new RejectedAddressException();
            if (addresses == null) {
                List<InetAddress> resolved = resolveBounded(requested);
                if (resolved == null || resolved.isEmpty() || resolved.stream().anyMatch(address -> !policy.test(address))) {
                    throw new RejectedAddressException();
                }
                addresses = List.copyOf(resolved);
            }
            return addresses;
        }

        private List<InetAddress> resolveBounded(String requested) throws UnknownHostException {
            if (!DNS_SLOTS.tryAcquire()) throw new DnsTimeoutException();
            CompletableFuture<List<InetAddress>> result = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try { result.complete(resolver.lookup(requested)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
                finally { DNS_SLOTS.release(); }
            });
            try {
                return result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException failure) {
                worker.interrupt();
                throw new DnsTimeoutException();
            } catch (InterruptedException failure) {
                worker.interrupt();
                Thread.currentThread().interrupt();
                throw new UnknownHostException("DNS lookup interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof UnknownHostException unknown) throw unknown;
                UnknownHostException unknown = new UnknownHostException("DNS lookup failed");
                unknown.initCause(failure.getCause());
                throw unknown;
            }
        }

        synchronized boolean contains(InetAddress address) { return addresses != null && addresses.contains(address); }
    }

    static final class PeerGuard extends EventListener {
        private final PinnedDns dns;
        private final int port;
        private final Predicate<InetAddress> policy;
        volatile boolean verified;

        PeerGuard(PinnedDns dns, int port, Predicate<InetAddress> policy) {
            this.dns = dns; this.port = port; this.policy = policy;
        }

        @Override public void connectStart(Call call, InetSocketAddress route, Proxy proxy) {
            check(route, proxy);
        }

        @Override public void connectionAcquired(Call call, Connection connection) {
            InetSocketAddress route = connection.route().socketAddress();
            check(route, connection.route().proxy());
            if (!(connection.socket() instanceof SSLSocket)
                    || !(connection.socket().getRemoteSocketAddress() instanceof InetSocketAddress actual)
                    || actual.getPort() != port || !route.getAddress().equals(actual.getAddress())
                    || !dns.contains(actual.getAddress()) || !policy.test(actual.getAddress())) {
                throw new SecurityException("Connected peer differs from pinned DNS route");
            }
            verified = true;
        }

        private void check(InetSocketAddress address, Proxy proxy) {
            if (!Proxy.NO_PROXY.equals(proxy) || address.isUnresolved() || address.getPort() != port
                    || !dns.contains(address.getAddress()) || !policy.test(address.getAddress())) {
                throw new SecurityException("Unsafe source route");
            }
        }
    }
}
