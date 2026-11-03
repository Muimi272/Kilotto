package club.muimi.kilotto;

import club.muimi.kilotto.room.websocket.WebSocketDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketDeliveryTests {
    @Test
    void slowSocketDoesNotBlockRoomUpdatesAndFinalStatePrecedesClose() throws Exception {
        var delivery = new WebSocketDelivery();
        var raw = mock(WebSocketSession.class);
        when(raw.isOpen()).thenReturn(true);
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            TextMessage message = call.getArgument(0);
            if (message.getPayload().equals("first")) {
                sending.countDown();
                assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            }
            received.add(message.getPayload());
            return null;
        }).when(raw).sendMessage(any());
        doAnswer(call -> { received.add("closed"); closed.countDown(); return null; }).when(raw).close(CloseStatus.NORMAL);
        WebSocketSession socket = delivery.wrap(raw);
        socket.sendMessage(new TextMessage("first"));
        assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                socket.sendMessage(new TextMessage("obsolete"));
                socket.sendMessage(new TextMessage("CLOSED"));
                socket.close(CloseStatus.NORMAL);
                return null;
            }).get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly("first", "CLOSED", "closed");
        delivery.close();
    }

    @Test
    void aBlockedFirstWriteIsClosedByTheDeadline() throws Exception {
        try (var delivery = new WebSocketDelivery()) {
            var raw = mock(WebSocketSession.class);
            when(raw.isOpen()).thenReturn(true);
            var released = new CountDownLatch(1);
            var closed = new CountDownLatch(1);
            doAnswer(call -> { released.await(8, TimeUnit.SECONDS); return null; }).when(raw).sendMessage(any());
            doAnswer(call -> { released.countDown(); closed.countDown(); return null; }).when(raw).close(CloseStatus.SESSION_NOT_RELIABLE);
            delivery.wrap(raw).sendMessage(new TextMessage("blocked"));
            assertThat(closed.await(7, TimeUnit.SECONDS)).isTrue();
        }
    }
}
