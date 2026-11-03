package club.muimi.kilotto.room.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;
import java.util.UUID;

public class RoomHandshakeInterceptor implements HandshakeInterceptor {
    public static final String GUEST_ID = "kilotto.guestId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) return false;
        // A prior HTTP request must establish the cookie; an upgrade alone cannot give a stable guest identity.
        var session = servlet.getServletRequest().getSession(false);
        if (session == null) {
            response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
            return false;
        }
        synchronized (session) {
            String guestId = (String) session.getAttribute(GUEST_ID);
            if (guestId == null) {
                guestId = UUID.randomUUID().toString();
                session.setAttribute(GUEST_ID, guestId);
            }
            attributes.put(GUEST_ID, guestId);
            attributes.put(SessionBinding.ATTRIBUTE, new SessionBinding(session, SessionBinding.revision(session)));
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {}
}
