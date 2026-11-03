package club.muimi.kilotto.room.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final RoomWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Default origin policy is same-origin; do not use wildcard origins with session cookies.
        registry.addHandler(handler, "/ws/rooms/{code}").addInterceptors(new RoomHandshakeInterceptor());
    }
}
