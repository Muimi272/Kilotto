package club.muimi.kilotto;

import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.entity.User;
import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import club.muimi.kilotto.room.RoomService;
import club.muimi.kilotto.room.websocket.SessionBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomBoundaryTests {
    private MutableClock clock;
    private RoomService rooms;
    private SecurityUser owner;

    @BeforeEach
    void setup() {
        clock = new MutableClock();
        User user = new User(); user.setId(1L); user.setUsername("owner"); user.setPassword("unused");
        owner = SecurityUser.from(user);
        UserDao users = mock(UserDao.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        JsonMapper json = mock(JsonMapper.class);
        when(json.writeValueAsString(any())).thenReturn("{}");
        rooms = new RoomService(json, new AccountAccess(users), clock);
    }

    private WebSocketSession socket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    @Test
    void expiryIsEnforcedBeforeScheduledCleanupAndFreesHostQuota() {
        String code = rooms.create("Expires", owner).code();
        for (int i = 1; i < 10; i++) rooms.create("Quota " + i, owner);
        clock.advance(Duration.ofHours(24));
        assertThatThrownBy(() -> rooms.draw(code, 1, owner)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(410));
        assertThat(rooms.create("New after expiry", owner).status()).isEqualTo("OPEN");
        clock.advance(Duration.ofHours(1));
        assertThatThrownBy(() -> rooms.inspect(code, owner)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void missingPongsRemoveStaleGuestsFromTheDrawPool() throws Exception {
        String code = rooms.create("Heartbeat", owner).code();
        var socket = socket("stale");
        rooms.connect(code, UUID.randomUUID().toString(), "Guest", false, null, socket);
        clock.advance(Duration.ofSeconds(75));
        assertThatThrownBy(() -> rooms.draw(code, 1, owner)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        rooms.maintain();
        assertThat(rooms.inspect(code, owner).participants().getFirst().online()).isFalse();
        assertThatThrownBy(() -> rooms.draw(code, 1, owner)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verify(socket).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void pongsKeepLiveGuestsEligible() {
        String code = rooms.create("Heartbeat", owner).code();
        rooms.connect(code, UUID.randomUUID().toString(), "Guest", false, null, socket("live"));
        clock.advance(Duration.ofSeconds(74));
        rooms.pong(code, "live");
        clock.advance(Duration.ofSeconds(74));
        rooms.maintain();
        assertThat(rooms.draw(code, 1, owner).winners()).hasSize(1);
    }

    @Test
    void repeatedCreateAndCloseCannotConsumeTheWholeGlobalRoomCapacity() {
        for (int i = 0; i < 50; i++) {
            String code = rooms.create("Closed " + i, owner).code();
            rooms.close(code, owner);
        }
        assertThatThrownBy(() -> rooms.create("Excess", owner)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        clock.advance(Duration.ofHours(1));
        assertThat(rooms.create("Capacity recovered", owner).status()).isEqualTo("OPEN");
    }

    @Test
    void handshakeCapturedBeforeSessionChangeCannotJoinAfterwards() {
        String code = rooms.create("Session race", owner).code();
        var socket = socket("old-handshake");
        var session = new MockHttpSession();
        socket.getAttributes().put(SessionBinding.ATTRIBUTE, new SessionBinding(session, 0));
        session.setAttribute(SessionBinding.REVISION, 1L);
        assertThatThrownBy(() -> rooms.connect(code, UUID.randomUUID().toString(), "Guest", false, null, socket))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        assertThat(rooms.inspect(code, owner).participants()).isEmpty();
    }

    @Test
    void logoutSealsTheSessionBeforeTheContainerClearsItsAttributes() {
        String code = rooms.create("Logout race", owner).code();
        var session = new MockHttpSession();
        // Model a late upgrade after the destruction listener, before the container invalidates attributes.
        session.setAttribute(SessionBinding.ENDED, true);
        var socket = socket("late-upgrade");
        socket.getAttributes().put(SessionBinding.ATTRIBUTE, new SessionBinding(session, 0));
        assertThatThrownBy(() -> rooms.connect(code, UUID.randomUUID().toString(), "Guest", false, null, socket))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        assertThat(rooms.inspect(code, owner).participants()).isEmpty();
    }

    @Test
    void upgradeUsesTheCurrentSessionIdentityWhenTheRequestPrincipalIsStale() {
        String code = rooms.create("Login race", owner).code();
        var session = new MockHttpSession();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(owner, null, owner.getAuthorities()));
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        var socket = socket("upgrade-during-login");
        socket.getAttributes().put(SessionBinding.ATTRIBUTE, new SessionBinding(session, 0));
        // The HTTP upgrade began anonymously, but login finished before its binding was captured.
        rooms.connect(code, UUID.randomUUID().toString(), "Guest", false, null, socket);
        assertThat(rooms.inspect(code, owner).participants()).isEmpty();
    }
}
