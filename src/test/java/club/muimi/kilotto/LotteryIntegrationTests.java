package club.muimi.kilotto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LotteryIntegrationTests {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired club.muimi.kilotto.auth.dao.UserDao userDao;
    @Autowired club.muimi.kilotto.room.RoomService roomService;
    static final String PASSWORD = "test-host-password-2026";

    @Test
    void publicPageAndCsrfAreAccessibleButAnonymousCannotCreateOrRegister() throws Exception {
        Client guest = new Client();
        assertThat(guest.get("/").statusCode()).isEqualTo(200);
        for (String path : List.of("/join", "/login", "/rooms", "/rooms/new", "/admin/users",
                "/admin/users/new", "/room/ABCDEFGH", "/room/ABCDEFGH/history")) {
            var page = guest.get(path);
            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.body()).contains("id=\"theme-select\"", "src=\"/theme.js\"");
        }
        assertThat(guest.get("/theme.js").statusCode()).isEqualTo(200);
        assertThat(guest.get("/opening.js").statusCode()).isEqualTo(200);
        assertThat(guest.get("/fonts/noto-sans-sc/font.css").statusCode()).isEqualTo(200);
        assertThat(guest.get("/fonts/noto-sans-sc/noto-sans-sc-000.woff2").statusCode()).isEqualTo(200);
        assertThat(guest.get("/api/rooms").statusCode()).isEqualTo(401);
        assertThat(guest.get("/api/rooms/ABCDEFGH").statusCode()).isEqualTo(401);
        assertThat(guest.get("/api/admin/users").statusCode()).isEqualTo(401);
        assertThat(guest.get("/health").statusCode()).isEqualTo(200);
        assertThat(guest.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(guest.get("/api/admin/rooms").statusCode()).isEqualTo(401);
        assertThat(guest.post("/api/rooms", Map.of("title", "Anonymous"), true).statusCode()).isEqualTo(401);
        assertThat(guest.post("/api/auth/register", Map.of(), true).statusCode()).isIn(401, 403);
        assertThat(guest.post("/api/auth/login", Map.of("username", "test-admin", "password", "test-admin-password-2026"), false).statusCode()).isEqualTo(403);
    }

    @Test
    void loginRotatesSessionAndLogoutRequiresCsrf() throws Exception {
        Client client = new Client();
        client.token();
        String oldSession = client.sessionId();
        assertThat(client.login("test-admin", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(client.login("test-admin", "密".repeat(72)).statusCode()).isEqualTo(401);
        assertThat(client.login("test-admin", "test-admin-password-2026").statusCode()).isEqualTo(200);
        assertThat(client.sessionId()).isNotEqualTo(oldSession);
        assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(client.post("/api/auth/logout", null, false).statusCode()).isEqualTo(403);
        assertThat(client.post("/api/auth/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void guestJoinsByCodeAndReceivesWinnersWithoutLogin() throws Exception {
        Client host = host();
        String code = host.createRoom();
        Client guest = new Client();
        try (Socket observer = host.connect(code, ""); Socket participant = guest.connect(code, "小明 + friend")) {
            JsonNode joined = participant.awaitRoom(r -> r.path("participants").size() == 1);
            assertThat(joined.path("participants").get(0).path("nickname").asString()).isEqualTo("小明 + friend");
            assertThat(joined.path("participants").get(0).path("online").asBoolean()).isTrue();
            assertThat(host.get("/api/rooms/" + code).statusCode()).isEqualTo(200);
            var draw = host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true);
            assertThat(draw.statusCode()).isEqualTo(200);
            JsonNode result = participant.awaitRoom(r -> r.path("winners").size() == 1);
            assertThat(result.path("winners").get(0).path("nickname").asString()).isEqualTo("小明 + friend");
            observer.awaitRoom(r -> r.path("winners").size() == 1);
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void reconnectKeepsIdentityAndCannotWinTwice() throws Exception {
        Client host = host();
        String code = host.createRoom();
        Client guest = new Client();
        String id;
        try (Socket first = guest.connect(code, "原昵称")) {
            id = first.awaitRoom(r -> r.path("participants").size() == 1).path("participants").get(0).path("id").asString();
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(200);
        }
        try (Socket second = guest.connect(code, "改昵称")) {
            JsonNode view = second.awaitRoom(r -> r.path("participants").size() == 1);
            assertThat(view.path("participants").get(0).path("id").asString()).isEqualTo(id);
            assertThat(view.path("participants").get(0).path("nickname").asString()).isEqualTo("原昵称");
            assertThat(view.path("participants").get(0).path("won").asBoolean()).isTrue();
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void offlineGuestsAreExcludedAndSameSessionReplacesPreviousTab() throws Exception {
        Client host = host();
        String code = host.createRoom();
        Client guest = new Client();
        try (Socket observer = host.connect(code, ""); Socket first = guest.connect(code, "访客")) {
            first.awaitRoom(r -> r.path("participants").size() == 1);
            try (Socket second = guest.connect(code, "访客")) {
                second.awaitRoom(r -> r.path("participants").size() == 1);
                assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4001);
            }
            observer.awaitRoom(r -> r.path("participants").size() == 1 && !r.path("participants").get(0).path("online").asBoolean());
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void otherHostsCannotInspectDrawOrCloseAndAdminsCanInspectAndClose() throws Exception {
        Client owner = host();
        Client other = host();
        Client admin = admin();
        String code = owner.createRoom();
        assertThat(other.get("/api/rooms/" + code).statusCode()).isEqualTo(403);
        assertThat(other.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(403);
        assertThat(other.post("/api/rooms/" + code + "/close", null, true).statusCode()).isEqualTo(403);
        assertThat(other.get("/api/admin/rooms").statusCode()).isEqualTo(403);
        assertThat(other.post("/api/admin/users", Map.of("username", "forbidden", "password", PASSWORD, "role", "ADMIN"), true).statusCode()).isEqualTo(403);
        assertThat(admin.get("/api/admin/rooms").statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/rooms/" + code).statusCode()).isEqualTo(200);
        assertThat(admin.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(403);
        try (Socket guest = new Client().connect(code, "Guest"); Socket observer = admin.connect(code, "Admin")) {
            assertThat(observer.awaitRoom(r -> r.path("participants").size() == 1).path("participants").size()).isEqualTo(1);
            assertThat(admin.post("/api/rooms/" + code + "/close", null, true).statusCode()).isEqualTo(200);
            JsonNode ended = guest.awaitRoom(r -> r.path("status").asString().equals("CLOSED"));
            assertThat(ended.path("closeReason").asString()).contains("管理员");
            assertThat(guest.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
        }
        assertThat(owner.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(410);
        try (Socket guest = new Client().connect(code, "Late")) {
            assertThat(guest.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
    }

    @Test
    void disablingUserRejectsExistingSessionsAndEndsTheirRoomsButCannotDisableSelf() throws Exception {
        Client admin = admin();
        Client host = host();
        JsonNode hostInfo = json.readTree(host.get("/api/auth/me").body());
        long hostId = hostInfo.path("id").asLong();
        String code = host.createRoom();
        try (Socket guest = new Client().connect(code, "Guest")) {
            guest.awaitRoom(r -> r.path("participants").size() == 1);
            var disabled = admin.request("/api/admin/users/" + hostId + "/enabled", "PATCH", Map.of("enabled", false), true);
            assertThat(disabled.statusCode()).isEqualTo(200);
            guest.awaitRoom(r -> r.path("status").asString().equals("CLOSED"));
            assertThat(host.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(host.login(hostInfo.path("username").asString(), PASSWORD).statusCode()).isEqualTo(401);
        }
        long self = json.readTree(admin.get("/api/auth/me").body()).path("id").asLong();
        assertThat(admin.request("/api/admin/users/" + self + "/enabled", "PATCH", Map.of("enabled", false), true).statusCode()).isEqualTo(403);
        assertThat(admin.request("/api/admin/users/" + hostId + "/enabled", "PATCH", Map.of("enabled", true), true).statusCode()).isEqualTo(200);
        assertThat(host.login(hostInfo.path("username").asString(), PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void reEnablingUserDoesNotResurrectOldSessions() throws Exception {
        Client admin = admin();
        Client host = host();
        long id = json.readTree(host.get("/api/auth/me").body()).path("id").asLong();
        assertThat(admin.request("/api/admin/users/" + id + "/enabled", "PATCH", Map.of("enabled", false), true).statusCode()).isEqualTo(200);
        assertThat(admin.request("/api/admin/users/" + id + "/enabled", "PATCH", Map.of("enabled", true), true).statusCode()).isEqualTo(200);
        assertThat(host.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void loginAndLogoutDisconnectExistingSocketsForThatBrowserSession() throws Exception {
        Client admin = admin();
        String code = admin.createRoom();
        Client guest = new Client();
        try (Socket participant = guest.connect(code, "Guest")) {
            participant.awaitRoom(r -> r.path("participants").size() == 1);
            assertThat(guest.login("test-admin", "test-admin-password-2026").statusCode()).isEqualTo(200);
            assertThat(participant.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4002);
        }
        try (Socket observer = guest.connect(code, "")) {
            observer.awaitRoom(r -> r.path("participants").size() == 1);
            assertThat(guest.post("/api/auth/logout", null, true).statusCode()).isEqualTo(204);
            // Tomcat may close an authenticated socket itself as the HTTP session is invalidated.
            assertThat(observer.closed.get(5, TimeUnit.SECONDS)).isIn(4002, 1008);
        }
    }

    @Test
    void malformedTypesAndPaginationReturnBadRequest() throws Exception {
        Client admin = admin();
        String code = admin.createRoom();
        assertThat(admin.post("/api/rooms/" + code + "/draw", Map.of("count", 1.9), true).statusCode()).isEqualTo(400);
        assertThat(admin.post("/api/rooms/" + code + "/draw", Map.of("count", "1"), true).statusCode()).isEqualTo(400);
        assertThat(admin.request("/api/admin/users/1/enabled", "PATCH", Map.of("enabled", "false"), true).statusCode()).isEqualTo(400);
        assertThat(admin.get("/api/admin/users?page=abc").statusCode()).isEqualTo(400);
        assertThat(admin.get("/api/admin/users?page=-1").statusCode()).isEqualTo(400);
        assertThat(admin.get("/api/admin/users?page=2147483647").statusCode()).isEqualTo(400);
    }

    @Test
    void sameDrawRequestCanBeRetriedWithoutStartingAnotherRound() throws Exception {
        Client host = host();
        String code = host.createRoom();
        String requestId = UUID.randomUUID().toString();
        try (Socket one = new Client().connect(code, "One"); Socket two = new Client().connect(code, "Two")) {
            two.awaitRoom(r -> r.path("participants").size() == 2);
            host.token();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> host.post("/api/rooms/" + code + "/draw", Map.of("count", 1, "requestId", requestId), true).statusCode());
                var retry = executor.submit(() -> host.post("/api/rooms/" + code + "/draw", Map.of("count", 1, "requestId", requestId), true).statusCode());
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(retry.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
            assertThat(json.readTree(host.get("/api/rooms/" + code).body()).path("winners").size()).isEqualTo(1);
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 2, "requestId", requestId), true).statusCode()).isEqualTo(409);
            assertThat(host.post("/api/rooms/" + code + "/close", null, true).statusCode()).isEqualTo(200);
            var retryAfterClose = host.post("/api/rooms/" + code + "/draw", Map.of("count", 1, "requestId", requestId), true);
            assertThat(retryAfterClose.statusCode()).isEqualTo(200);
            assertThat(json.readTree(retryAfterClose.body()).path("winners").size()).isEqualTo(1);
        }
    }

    @Test
    void concurrentDisableAndCreateCannotLeaveAnOpenRoomForADisabledOwner() throws Exception {
        Client admin = admin();
        Client host = host();
        JsonNode info = json.readTree(host.get("/api/auth/me").body());
        long id = info.path("id").asLong();
        var principal = club.muimi.kilotto.auth.security.entity.SecurityUser.from(userDao.findById(id).orElseThrow());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creator = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    try { roomService.create("Racing room " + i, principal); }
                    catch (org.springframework.web.server.ResponseStatusException denied) {
                        assertThat(denied.getStatusCode().value()).isIn(401, 429);
                    }
                }
                return null;
            });
            var disable = executor.submit(() -> {
                start.await();
                return admin.request("/api/admin/users/" + id + "/enabled", "PATCH", Map.of("enabled", false), true).statusCode();
            });
            start.countDown();
            assertThat(disable.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            creator.get(10, TimeUnit.SECONDS);
        }
        JsonNode rooms = json.readTree(admin.get("/api/admin/rooms").body());
        for (JsonNode room : rooms) {
            if (room.path("ownerId").asLong() == id) assertThat(room.path("status").asString()).isEqualTo("CLOSED");
        }
    }

    @Test
    void oversizedBodiesAreRejectedIncludingChunkedRequests() throws Exception {
        Client host = host();
        host.token();
        String body = json.writeValueAsString(Map.of("title", "x".repeat(20_000)));
        var knownLength = host.post("/api/rooms", Map.of("title", "x".repeat(20_000)), true);
        assertThat(knownLength.statusCode()).isEqualTo(413);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms"))
                .timeout(Duration.ofSeconds(10)).header(host.header, host.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).build();
        assertThat(host.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
    }

    @Test
    void securityHeadersAndSessionCookieApplyToActualResponses() throws Exception {
        Client guest = new Client();
        var response = guest.get("/");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("default-src 'self'", "frame-ancestors 'none'");
        assertThat(response.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
        var csrf = guest.get("/api/auth/csrf");
        assertThat(csrf.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
    }

    @Test
    void concurrentDrawsDoNotPickTheSameGuestTwice() throws Exception {
        Client host = host();
        String code = host.createRoom();
        try (Socket guest = new Client().connect(code, "Only candidate")) {
            guest.awaitRoom(r -> r.path("participants").size() == 1);
            host.token();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var one = executor.submit(() -> host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode());
                var two = executor.submit(() -> host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode());
                assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            }
            assertThat(json.readTree(host.get("/api/rooms/" + code).body()).path("winners").size()).isEqualTo(1);
        }
    }

    @Test
    void multipleWinnersAreUniqueAndSocketMessagesCannotTriggerADraw() throws Exception {
        Client host = host();
        String code = host.createRoom();
        try (Socket first = new Client().connect(code, "One");
             Socket second = new Client().connect(code, "Two");
             Socket third = new Client().connect(code, "Three")) {
            third.awaitRoom(r -> r.path("participants").size() == 3);
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 2), true).statusCode()).isEqualTo(200);
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 2), true).statusCode()).isEqualTo(409);
            assertThat(host.post("/api/rooms/" + code + "/draw", Map.of("count", 1), true).statusCode()).isEqualTo(200);
            JsonNode result = json.readTree(host.get("/api/rooms/" + code).body());
            Set<String> winnerIds = new HashSet<>();
            result.path("winners").forEach(winner -> winnerIds.add(winner.path("participantId").asString()));
            assertThat(winnerIds).hasSize(3);
            assertThat(result.path("winners").get(0).path("round").asInt()).isEqualTo(1);
            assertThat(result.path("winners").get(2).path("round").asInt()).isEqualTo(2);
            first.socket.sendText("{\"type\":\"draw\",\"count\":1}", true).get(5, TimeUnit.SECONDS);
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(json.readTree(host.get("/api/rooms/" + code).body()).path("winners").size()).isEqualTo(3);
        }
    }

    @Test
    void roomQuotaAndSummaryKeepListingsBounded() throws Exception {
        Client host = host();
        List<String> codes = new ArrayList<>();
        for (int index = 0; index < 10; index++) codes.add(host.createRoom());
        assertThat(host.post("/api/rooms", Map.of("title", "Too many"), true).statusCode()).isEqualTo(429);
        JsonNode list = json.readTree(host.get("/api/rooms").body());
        assertThat(list.size()).isEqualTo(10);
        assertThat(list.get(0).has("onlineCount")).isTrue();
        assertThat(list.get(0).has("participants")).isFalse();
        assertThat(host.post("/api/rooms/" + codes.getFirst() + "/close", null, true).statusCode()).isEqualTo(200);
        assertThat(host.post("/api/rooms", Map.of("title", "Now allowed"), true).statusCode()).isEqualTo(201);
    }

    @Test
    void invalidInputIsRejectedAndResponsesNeverExposePasswordHashes() throws Exception {
        Client admin = admin();
        String name = unique();
        Map<String, String> input = Map.of("username", name, "password", PASSWORD, "role", "USER");
        var created = admin.post("/api/admin/users", input, true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("password", "$2a$", "$2b$");
        assertThat(admin.get("/api/admin/users").body()).doesNotContain("password", "$2a$", "$2b$");
        assertThat(admin.post("/api/admin/users", input, true).statusCode()).isEqualTo(409);
        assertThat(admin.post("/api/admin/users", Map.of("username", unique(), "password", "密码".repeat(20), "role", "USER"), true).statusCode()).isEqualTo(400);
        assertThat(admin.post("/api/admin/users", Map.of("username", unique(), "password", "short", "role", "USER"), true).statusCode()).isEqualTo(400);
        assertThat(admin.post("/api/rooms", Map.of("title", " "), true).statusCode()).isEqualTo(400);
        String code = admin.createRoom();
        assertThat(admin.post("/api/rooms/" + code + "/draw", Map.of("count", 0), true).statusCode()).isEqualTo(400);
        assertThat(admin.post("/api/rooms/" + code + "/draw", Map.of("count", 101), true).statusCode()).isEqualTo(400);
    }

    @Test
    void nonexistentRoomAndUnauthorizedWatchReturnSocketErrorsAndCrossOriginIsRejected() throws Exception {
        try (Socket missing = new Client().connect("ZZZZZZZZ", "")) {
            assertThat(missing.await(event -> event.path("type").asString().equals("error")).path("detail").asString()).contains("不存在");
            assertThat(missing.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
        String code = host().createRoom();
        Client guest = new Client();
        try (Socket forbidden = guest.connect(code + "?mode=watch", "")) {
            assertThat(forbidden.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
        assertThatThrownBy(() -> guest.http.newWebSocketBuilder().header("Origin", "https://example.invalid")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/rooms/" + code), new Socket()).join())
                .hasCauseInstanceOf(WebSocketHandshakeException.class);
    }

    private String unique() { return "user-" + UUID.randomUUID().toString().substring(0, 12); }

    private Client admin() throws Exception {
        Client client = new Client();
        assertThat(client.login("test-admin", "test-admin-password-2026").statusCode()).isEqualTo(200);
        return client;
    }

    private Client host() throws Exception {
        Client admin = admin();
        String username = unique();
        assertThat(admin.post("/api/admin/users", Map.of("username", username, "password", PASSWORD, "role", "USER"), true).statusCode()).isEqualTo(201);
        Client client = new Client();
        assertThat(client.login(username, PASSWORD).statusCode()).isEqualTo(200);
        return client;
    }

    private class Client {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        String token;
        String header;

        String sessionId() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("KILOTTO_SESSION")).findFirst().orElseThrow().getValue(); }

        void token() throws Exception {
            JsonNode csrf = json.readTree(get("/api/auth/csrf").body());
            token = csrf.path("token").asString();
            header = csrf.path("headerName").asString();
        }

        HttpResponse<String> login(String username, String password) throws Exception {
            token();
            var response = post("/api/auth/login", Map.of("username", username, "password", password), true);
            token();
            return response;
        }

        HttpResponse<String> get(String path) throws Exception { return request(path, "GET", null, false); }
        HttpResponse<String> post(String path, Object body, boolean csrf) throws Exception { return request(path, "POST", body, csrf); }

        HttpResponse<String> request(String path, String method, Object body, boolean csrf) throws Exception {
            if (csrf && token == null) token();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
            if (csrf) request.header(header, token);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        String createRoom() throws Exception {
            var response = post("/api/rooms", Map.of("title", "Test lottery"), true);
            assertThat(response.statusCode()).isEqualTo(201);
            return json.readTree(response.body()).path("code").asString();
        }

        Socket connect(String code, String nickname) throws Exception {
            token(); // Establish the HTTP session before upgrading.
            Socket listener = new Socket();
            String path = "/ws/rooms/" + code + (code.contains("?") ? "&" : "?") + "nickname=" + URLEncoder.encode(nickname, java.nio.charset.StandardCharsets.UTF_8);
            listener.socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + path), listener).get(5, TimeUnit.SECONDS);
            return listener;
        }
    }

    private class Socket implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { events.add(json.readTree(buffer.toString())); buffer.setLength(0); }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }

        JsonNode awaitRoom(Predicate<JsonNode> condition) throws Exception {
            return await(event -> event.path("type").asString().equals("room") && condition.test(event.path("room"))).path("room");
        }

        JsonNode await(Predicate<JsonNode> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode event = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (event == null) break;
                if (condition.test(event)) return event;
            }
            throw new AssertionError("Expected WebSocket event not received; closed=" + closed.isDone());
        }

        @Override
        public void close() throws Exception {
            if (socket != null && !socket.isOutputClosed() && !socket.isInputClosed() && !closed.isDone()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").exceptionally(error -> null).get(5, TimeUnit.SECONDS);
            }
        }
    }
}
