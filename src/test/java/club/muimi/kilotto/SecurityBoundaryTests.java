package club.muimi.kilotto;

import club.muimi.kilotto.auth.security.*;
import club.muimi.kilotto.room.websocket.SocketAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class SecurityBoundaryTests {
    @Test
    void productionConfigurationDoesNotSeedAKnownAdministratorPassword() throws Exception {
        var properties = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) { properties.load(input); }
        assertThat(properties.getProperty("kilotto.bootstrap.username")).isEqualTo("${KILOTTO_ADMIN_USERNAME:}");
        assertThat(properties.getProperty("kilotto.bootstrap.password")).isEqualTo("${KILOTTO_ADMIN_PASSWORD:}");
    }

    @Test
    void loginLimitCannotBeBypassedWithForwardedHeadersAndRecovers() throws Exception {
        MutableClock clock = new MutableClock();
        var guard = new RequestGuardFilter(new RequestRateLimiter(clock), new RequestLimits(2, 2, 3, 2));
        AtomicInteger admitted = new AtomicInteger();
        for (int index = 0; index < 3; index++) {
            var request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setServletPath("/api/auth/login");
            request.setRemoteAddr("192.0.2.1");
            request.addHeader("X-Forwarded-For", "192.0.2." + (index + 2));
            var response = new MockHttpServletResponse();
            guard.doFilter(request, response, (req, res) -> admitted.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(index < 2 ? 200 : 429);
        }
        assertThat(admitted.get()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(1));
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login"); request.setRemoteAddr("192.0.2.1");
        var response = new MockHttpServletResponse();
        guard.doFilter(request, response, (req, res) -> admitted.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(admitted.get()).isEqualTo(3);
    }

    @Test
    void socketAdmissionIsBoundedAllowsReplacementAndReleasesCapacity() {
        var admission = new SocketAdmission(new RequestLimits(10, 10, 4, 2));
        assertThat(admission.admit("guest", "room-one", "a")).isTrue();
        assertThat(admission.admit("guest", "room-two", "b")).isTrue();
        assertThat(admission.admit("guest", "room-three", "c")).isFalse();
        assertThat(admission.admit("guest", "room-two", "replacement")).isTrue();
        assertThat(admission.admit("guest", "room-two", "excess-replacement")).isFalse();
        admission.release("b");
        assertThat(admission.admit("another-guest", "room-one", "d")).isTrue();
        assertThat(admission.admit("third-guest", "room-one", "e")).isTrue();
        assertThat(admission.admit("fourth-guest", "room-one", "f")).isFalse();
        admission.release("e");
        assertThat(admission.admit("fourth-guest", "room-one", "f")).isTrue();
    }
}
