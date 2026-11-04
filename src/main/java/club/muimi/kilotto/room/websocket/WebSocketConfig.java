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
        // Register the WebSocket handler for the "/ws/rooms/{code}" endpoint, allowing all origins and adding a handshake interceptor.
        registry.addHandler(handler, "/ws/rooms/{code}")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new RoomHandshakeInterceptor());
    }
}
