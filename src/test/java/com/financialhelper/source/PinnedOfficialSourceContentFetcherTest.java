package com.financialhelper.source;

import okhttp3.Connection;
import okhttp3.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PinnedOfficialSourceContentFetcherTest {
    @TempDir Path temporary;

    @Test void specialIpv4AndIpv6AddressesAreRejected() throws Exception {
        for (String address : List.of("0.1.2.3", "10.1.2.3", "100.64.0.1", "127.0.0.1",
                "169.254.169.254", "172.16.1.1", "192.0.0.7", "192.0.2.1", "192.31.196.1",
                "192.52.193.1", "192.175.48.1", "192.168.1.1",
                "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
                "::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1",
                "2001:0000::1", "2002::1", "2620:4f:8000::1", "3ffe::1", "3fff::1", "64:ff9b::808:808")) {
            assertThat(PublicIpAddressPolicy.isPublic(InetAddress.getByName(address))).as(address).isFalse();
        }
        assertThat(PublicIpAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(PublicIpAddressPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        byte[] mapped = new byte[16]; mapped[10] = (byte) 0xff; mapped[11] = (byte) 0xff;
        mapped[12] = 127; mapped[15] = 1;
        assertThat(PublicIpAddressPolicy.isPublic(Inet6Address.getByAddress(null, mapped, -1))).isFalse();
    }

    @Test void dnsAnswerIsPinnedAndPrivateMixedAnswersAreRejected() throws Exception {
        var publicIp = InetAddress.getByName("8.8.8.8");
        var privateIp = InetAddress.getByName("127.0.0.1");
        var lookups = new AtomicInteger();
        var dns = new PinnedOfficialSourceContentFetcher.PinnedDns("official.example",
                host -> lookups.incrementAndGet() == 1 ? List.of(publicIp) : List.of(privateIp),
                PublicIpAddressPolicy::isPublic);
        assertThat(dns.lookup("official.example")).containsExactly(publicIp);
        assertThat(dns.lookup("official.example")).containsExactly(publicIp);
        assertThat(lookups).hasValue(1);
        var mixed = new PinnedOfficialSourceContentFetcher.PinnedDns("official.example",
                host -> List.of(publicIp, privateIp), PublicIpAddressPolicy::isPublic);
        assertThatThrownBy(() -> mixed.lookup("official.example"))
                .isInstanceOf(PinnedOfficialSourceContentFetcher.RejectedAddressException.class);
    }

    @Test void stalledDnsLookupFailsWithinConfiguredTimeout() {
        var dns = new PinnedOfficialSourceContentFetcher.PinnedDns("official.example", host -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.net.UnknownHostException("interrupted"); }
            return List.of(InetAddress.getLoopbackAddress());
        }, address -> true, Duration.ofMillis(100));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> dns.lookup("official.example"))
                        .isInstanceOf(PinnedOfficialSourceContentFetcher.DnsTimeoutException.class));
    }

    @Test void actualPeerMustEqualPinnedDnsAndUseDirectTls() throws Exception {
        var publicIp = InetAddress.getByName("8.8.8.8");
        var dns = new PinnedOfficialSourceContentFetcher.PinnedDns("official.example",
                host -> List.of(publicIp), PublicIpAddressPolicy::isPublic);
        dns.lookup("official.example");
        var guard = new PinnedOfficialSourceContentFetcher.PeerGuard(dns, 443, PublicIpAddressPolicy::isPublic);
        var route = mock(Route.class);
        var connection = mock(Connection.class);
        var socket = mock(SSLSocket.class);
        when(route.socketAddress()).thenReturn(new InetSocketAddress(publicIp, 443));
        when(route.proxy()).thenReturn(Proxy.NO_PROXY);
        when(connection.route()).thenReturn(route);
        when(connection.socket()).thenReturn(socket);
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 443));
        assertThatThrownBy(() -> guard.connectionAcquired(null, connection)).isInstanceOf(SecurityException.class);
        assertThat(guard.verified).isFalse();
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress(publicIp, 443));
        guard.connectionAcquired(null, connection);
        assertThat(guard.verified).isTrue();
        assertThatThrownBy(() -> guard.connectStart(null, new InetSocketAddress(publicIp, 443),
                new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8888)))).isInstanceOf(SecurityException.class);
    }

    @Test void isolatedTlsUsesPinnedPeerHostnameAndIgnoresConfiguredProxy() throws Exception {
        try (var fixture = tlsFixture()) {
            int port = fixture.server().getLocalPort();
            var served = serveOnce(fixture.server(), "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK");
            var calls = new AtomicInteger();
            var fetcher = new PinnedOfficialSourceContentFetcher(properties(), fixturePolicy(port),
                    host -> { calls.incrementAndGet(); return List.of(InetAddress.getLoopbackAddress()); },
                    InetAddress::isLoopbackAddress, builder -> builder
                        .sslSocketFactory(fixture.client().getSocketFactory(), fixture.trust())
                        .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1))));
            var result = fetcher.fetch(source(port, "official.test"));
            assertThat(new String(result.originalContent(), StandardCharsets.UTF_8)).isEqualTo("OK");
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(calls).hasValue(1);
            served.get(10, TimeUnit.SECONDS);
            assertThat(PublicIpAddressPolicy.isPublic(InetAddress.getLoopbackAddress())).isFalse();
        }
    }

    @Test void redirectPerformsNewDnsCheckBeforeConnectingToPrivateTarget() throws Exception {
        try (var fixture = tlsFixture()) {
            int port = fixture.server().getLocalPort();
            var served = serveOnce(fixture.server(), "HTTP/1.1 302 Found\r\nLocation: https://redirect.official.test:"
                    + port + "/next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            var calls = new AtomicInteger();
            var fetcher = new PinnedOfficialSourceContentFetcher(properties(), fixturePolicy(port), host -> {
                calls.incrementAndGet();
                return host.equals("official.test") ? List.of(InetAddress.getLoopbackAddress())
                        : List.of(InetAddress.getByName("10.0.0.1"));
            }, InetAddress::isLoopbackAddress, builder ->
                    builder.sslSocketFactory(fixture.client().getSocketFactory(), fixture.trust()));
            assertThatThrownBy(() -> fetcher.fetch(source(port, "official.test")))
                    .isInstanceOf(SourceIngestionException.class).extracting("code").isEqualTo("SOURCE_ADDRESS_NOT_ALLOWED");
            assertThat(calls).hasValue(2);
            served.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void trustedCertificateForDifferentHostnameIsRejected() throws Exception {
        try (var fixture = tlsFixture()) {
            int port = fixture.server().getLocalPort();
            var served = serveOnce(fixture.server(), "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    .handle((ignored, failure) -> null);
            var fetcher = new PinnedOfficialSourceContentFetcher(properties(), fixturePolicy(port),
                    host -> List.of(InetAddress.getLoopbackAddress()), InetAddress::isLoopbackAddress,
                    builder -> builder.sslSocketFactory(fixture.client().getSocketFactory(), fixture.trust()));
            assertThatThrownBy(() -> fetcher.fetch(source(port, "redirect.official.test")))
                    .isInstanceOf(SourceIngestionException.class).extracting("code").isEqualTo("SOURCE_REQUEST_FAILED");
            served.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void isolatedTlsRejectsOversizedResponse() throws Exception {
        try (var fixture = tlsFixture()) {
            int port = fixture.server().getLocalPort();
            var served = serveOnce(fixture.server(), "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"
                    + "01234567890123456789012345678901").handle((ignored, failure) -> null);
            var limits = new SourceIngestionProperties(false, Duration.ofSeconds(3), Duration.ofSeconds(5), 10, 1, "fixture");
            var fetcher = new PinnedOfficialSourceContentFetcher(limits, fixturePolicy(port),
                    host -> List.of(InetAddress.getLoopbackAddress()), InetAddress::isLoopbackAddress,
                    builder -> builder.sslSocketFactory(fixture.client().getSocketFactory(), fixture.trust()));
            assertThatThrownBy(() -> fetcher.fetch(source(port, "official.test")))
                    .isInstanceOf(SourceIngestionException.class).extracting("code").isEqualTo("SOURCE_RESPONSE_TOO_LARGE");
            served.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void isolatedTlsTimesOutWhileResponseIsStalled() throws Exception {
        try (var fixture = tlsFixture()) {
            int port = fixture.server().getLocalPort();
            var requestReceived = new java.util.concurrent.CountDownLatch(1);
            var served = CompletableFuture.runAsync(() -> {
                try (var socket = (SSLSocket) fixture.server().accept()) {
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { /* headers only */ }
                    requestReceived.countDown();
                    Thread.sleep(3000);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            }).handle((ignored, failure) -> null);
            var limits = new SourceIngestionProperties(false, Duration.ofSeconds(3), Duration.ofSeconds(2), 4096, 1, "fixture");
            var fetcher = new PinnedOfficialSourceContentFetcher(limits, fixturePolicy(port),
                    host -> List.of(InetAddress.getLoopbackAddress()), InetAddress::isLoopbackAddress,
                    builder -> builder.sslSocketFactory(fixture.client().getSocketFactory(), fixture.trust()));
            assertThatThrownBy(() -> fetcher.fetch(source(port, "official.test")))
                    .isInstanceOf(SourceIngestionException.class).extracting("code").isEqualTo("SOURCE_REQUEST_TIMEOUT");
            assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
            served.get(10, TimeUnit.SECONDS);
        }
    }

    private SourceIngestionProperties properties() {
        return new SourceIngestionProperties(false, Duration.ofSeconds(3), Duration.ofSeconds(5), 4096, 2, "fixture");
    }

    private static SourceIngestionData.Snapshot source(int port, String host) {
        return new SourceIngestionData.Snapshot(UUID.randomUUID(), "fixture", "fixture", "official.test",
                "https://" + host + ":" + port + "/document", SourceAcquisitionType.HTML, null);
    }

    /** Isolated fixture only: production SourceUrlPolicy never permits this port or loopback address. */
    private static SourceUrlPolicy fixturePolicy(int port) {
        return new SourceUrlPolicy() {
            @Override public URI validate(URI uri, String domain) {
                if (!"https".equals(uri.getScheme()) || uri.getPort() != port
                        || !("official.test".equals(uri.getHost()) || "redirect.official.test".equals(uri.getHost()))) {
                    throw new SourceIngestionException("SOURCE_URL_NOT_ALLOWED", "Invalid fixture URL");
                }
                return uri;
            }
            @Override public URI validate(String url, String domain) { return validate(URI.create(url), domain); }
        };
    }

    private CompletableFuture<Void> serveOnce(SSLServerSocket server, String response) {
        return CompletableFuture.runAsync(() -> {
            try (var socket = (SSLSocket) server.accept()) {
                socket.setSoTimeout(5000);
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { /* headers only */ }
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }

    private TlsFixture tlsFixture() throws Exception {
        Path keyStorePath = temporary.resolve("fixture-" + UUID.randomUUID() + ".p12");
        String password = UUID.randomUUID().toString();
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(executable, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-dname", "CN=official.test", "-ext", "SAN=dns:official.test", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keyStorePath.toString(), "-storepass", password,
                "-keypass", password, "-noprompt").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Fixture key generation timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Fixture key generation failed");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) { store.load(input, password.toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(store);
        var trust = (X509TrustManager) java.util.Arrays.stream(trustManagers.getTrustManagers())
                .filter(X509TrustManager.class::isInstance).findFirst().orElseThrow();
        var server = SSLContext.getInstance("TLS"); server.init(keys.getKeyManagers(), null, null);
        var client = SSLContext.getInstance("TLS"); client.init(null, new javax.net.ssl.TrustManager[]{trust}, null);
        var socket = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(10000);
        return new TlsFixture(socket, client, trust);
    }

    private record TlsFixture(SSLServerSocket server, SSLContext client, X509TrustManager trust) implements AutoCloseable {
        @Override public void close() throws Exception { server.close(); }
    }
}
