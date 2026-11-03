package club.muimi.kilotto.room.websocket;

import club.muimi.kilotto.auth.security.entity.SecurityUser;
import club.muimi.kilotto.room.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private final RoomService rooms;
    private final WebSocketDelivery delivery;
    private final SocketAdmission admission;

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        var socket = delivery.wrap(raw);
        raw.getAttributes().put("outbound", socket);
        raw.setTextMessageSizeLimit(1024);
        raw.setBinaryMessageSizeLimit(1024);
        try {
            var uri = UriComponentsBuilder.fromUri(Objects.requireNonNull(raw.getUri())).build();
            String code = uri.getPathSegments().getLast();
            if (!admission.admit((String) raw.getAttributes().get(RoomHandshakeInterceptor.GUEST_ID), code.toUpperCase(java.util.Locale.ROOT), raw.getId())) {
                rooms.sendError(socket, "连接数已达上限，请关闭其他房间后重试");
                socket.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            raw.getAttributes().put("roomCode", code.toUpperCase(java.util.Locale.ROOT));
            var query = uri.getQueryParams();
            String nickname = query.getFirst("nickname");
            if (nickname != null) nickname = java.net.URLDecoder.decode(nickname, java.nio.charset.StandardCharsets.UTF_8);
            SecurityUser actor = raw.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof SecurityUser user ? user : null;
            rooms.connect(code, (String) raw.getAttributes().get(RoomHandshakeInterceptor.GUEST_ID), nickname,
                    "watch".equals(query.getFirst("mode")), actor, socket);
        } catch (ResponseStatusException error) {
            rooms.sendError(socket, error.getReason());
            socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (IllegalArgumentException error) {
            rooms.sendError(socket, "无效的房间连接参数");
            socket.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Mutating commands use authenticated, CSRF-protected HTTP endpoints only.
        outbound(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try { outbound(session).close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        Object code = session.getAttributes().get("roomCode");
        if (code instanceof String value) rooms.pong(value, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        admission.release(session.getId());
        Object code = session.getAttributes().get("roomCode");
        if (code instanceof String value) rooms.disconnect(value, session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        outbound(session).close(CloseStatus.SERVER_ERROR);
    }

    private WebSocketSession outbound(WebSocketSession session) {
        Object outbound = session.getAttributes().get("outbound");
        return outbound instanceof WebSocketSession value ? value : session;
    }
}
