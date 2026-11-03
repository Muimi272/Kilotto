package club.muimi.kilotto.room.websocket;

import jakarta.servlet.http.HttpSession;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Detects a login, logout or expiry that happened after a WebSocket upgrade began. */
public record SessionBinding(HttpSession session, long revision) {
    public static final String ATTRIBUTE = "kilotto.sessionBinding";
    public static final String REVISION = "kilotto.sessionRevision";
    public static final String ENDED = "kilotto.sessionEnded";

    public static long revision(HttpSession session) {
        Object value = session.getAttribute(REVISION);
        return value instanceof Long number ? number : 0;
    }

    public boolean isCurrent() {
        try { return !Boolean.TRUE.equals(session.getAttribute(ENDED)) && revision(session) == revision; }
        catch (IllegalStateException expired) { return false; }
    }

    /** Read under the account lifecycle lock; the upgrade request may have captured an older identity. */
    public SecurityUser principal() {
        Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (value instanceof SecurityContext context && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal() instanceof SecurityUser user) return user;
        return null;
    }
}
