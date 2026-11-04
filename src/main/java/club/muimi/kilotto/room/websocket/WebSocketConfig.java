package club.muimi.kilotto.room.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {
    private final RoomWebSocketHandler handler;
    private final String[] allowedOriginPatterns;

    public WebSocketConfig(RoomWebSocketHandler handler,
                           @Value("${kilotto.websocket.allowed-origin-patterns:}") String[] allowedOriginPatterns) {
        this.handler = handler;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // An empty list keeps Spring's same-origin default; deployments can allow their public origins.
        registry.addHandler(handler, "/ws/rooms/{code}")
                .setAllowedOriginPatterns(allowedOriginPatterns)
                .addInterceptors(new RoomHandshakeInterceptor());
    }
}
