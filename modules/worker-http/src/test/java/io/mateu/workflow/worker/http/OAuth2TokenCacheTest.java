package io.mateu.workflow.worker.http;

import io.mateu.workflow.worker.api.TaskFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2TokenCacheTest {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /**
     * A one-shot raw token endpoint: reads the request (head and form body), writes {@code response}, then
     * keeps the connection open until the client drops it. Completes with the nanoTime of that release.
     */
    private static CompletableFuture<Long> rawEndpoint(ServerSocket socket, byte[] response) {
        var released = new CompletableFuture<Long>();
        Thread.ofVirtual().start(() -> {
            try (socket; Socket peer = socket.accept()) {
                var in = peer.getInputStream();
                var window = 0;
                while (window != 0x0d0a0d0a) {
                    var b = in.read();
                    if (b < 0) break;
                    window = (window << 8) | b;
                }
                peer.getOutputStream().write(response);
                peer.getOutputStream().flush();
                while (in.read() >= 0) {
                    // drain (the form body) until the client drops the connection
                }
                released.complete(System.nanoTime());
            } catch (IOException e) {
                released.complete(System.nanoTime());
            }
        });
        return released;
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // before the fix, the read never ended
    void aTokenEndpointThatStallsItsBodyTimesOutWithinTheDeadlineAndReleasesTheConnection() throws Exception {
        var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var released = rawEndpoint(socket, ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n"
                + "{\"access_token\":").getBytes(StandardCharsets.UTF_8));
        var cache = new OAuth2TokenCache(client, () -> 1_000_000, Duration.ofMillis(200));

        var started = System.nanoTime();
        assertThatThrownBy(() -> cache.token("idp", "http://127.0.0.1:" + socket.getLocalPort() + "/token", "ec", "s", null))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).as("bounded by the 200 ms deadline").isLessThan(700);
        assertThat((released.get(2, TimeUnit.SECONDS) - started) / 1_000_000)
                .as("the endpoint saw the connection dropped").isLessThan(1_500);
    }

    @Test
    void aTokenResponseOverTheTokenCapIsRefusedEvenUnderALargerMaxResponseBytes() throws Exception {
        var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var body = "{\"access_token\":\"" + "x".repeat(70 * 1024) + "\",\"expires_in\":3600}";
        rawEndpoint(socket, ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
        var cache = new OAuth2TokenCache(client, () -> 10_000_000);

        assertThatThrownBy(() -> cache.token("idp", "http://127.0.0.1:" + socket.getLocalPort() + "/token", "ec", "s", null))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("HTTP_RESPONSE_TOO_LARGE");
                    assertThat(f.reason()).contains("token endpoint").contains(String.valueOf(64 * 1024));
                });
    }
}
