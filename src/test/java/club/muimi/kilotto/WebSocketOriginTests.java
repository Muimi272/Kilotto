package club.muimi.kilotto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "kilotto.websocket.allowed-origin-patterns=https://lottery.example.com,https://*.preview.example.com")
@ActiveProfiles("test")
class WebSocketOriginTests {
    @LocalServerPort int port;

    @ParameterizedTest
    @ValueSource(strings = {"https://lottery.example.com", "https://event.preview.example.com"})
    void configuredPublicOriginsCanUpgradeOverAnInternalHttpConnection(String origin) throws Exception {
        assertUpgradeAllowed(origin);
    }

    @Test
    void sameOriginRemainsAllowedWithConfiguredPublicOrigins() throws Exception {
        assertUpgradeAllowed("http://localhost:" + port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.invalid", "http://lottery.example.com",
            "https://lottery.example.com:8443", "https://lottery.example.com.evil.invalid"})
    void unlistedOriginsAreRejectedBeforeUpgrade(String origin) throws Exception {
        try (var http = sessionClient()) {
            assertThatThrownBy(() -> http.newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(socketUri(), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class)
                    .satisfies(error -> assertThat(((WebSocketHandshakeException) error.getCause())
                            .getResponse().statusCode()).isEqualTo(403));
        }
    }

    private void assertUpgradeAllowed(String origin) throws Exception {
        try (var http = sessionClient()) {
            var socket = http.newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(socketUri(), new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
            socket.abort();
        }
    }

    private HttpClient sessionClient() throws Exception {
        var http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        return http;
    }

    private URI socketUri() {
        // Room validation happens after upgrade; an absent room still exercises the real handshake.
        return URI.create("ws://localhost:" + port + "/ws/rooms/ZZZZZZZZ");
    }
}
