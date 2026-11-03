package club.muimi.kilotto.auth.config;

import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.room.RoomService;
import club.muimi.kilotto.room.websocket.RoomHandshakeInterceptor;
import club.muimi.kilotto.room.websocket.SessionBinding;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionLifecycleConfig {
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> socketSessionListener(RoomService rooms, AccountAccess access) {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                String guestId = (String) event.getSession().getAttribute(RoomHandshakeInterceptor.GUEST_ID);
                access.exclusive(() -> {
                    // Containers notify listeners before clearing attributes. Seal the session before releasing the lock.
                    event.getSession().setAttribute(SessionBinding.ENDED, true);
                    rooms.sessionEnded(guestId);
                    return null;
                });
            }
        });
    }
}
