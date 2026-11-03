package club.muimi.kilotto.room.websocket;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Ordered, bounded delivery. Socket writes and closes never run while the room's state lock is held. */
@Component
public class WebSocketDelivery implements AutoCloseable {
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "websocket-write-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    public WebSocketSession wrap(WebSocketSession session) { return new OrderedSession(session); }

    @PreDestroy
    public void close() {
        deadlines.shutdownNow();
        writers.shutdownNow();
    }

    private final class OrderedSession extends WebSocketSessionDecorator {
        private static final int MAX_BYTES = 512 * 1024;
        private final ArrayDeque<Delivery> queue = new ArrayDeque<>();
        private int queuedBytes;
        private boolean writing;
        private boolean closing;

        OrderedSession(WebSocketSession delegate) { super(delegate); }

        @Override
        public synchronized boolean isOpen() { return !closing && getDelegate().isOpen(); }

        @Override
        public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (!isOpen()) throw new IOException("Connection is closing");
            int bytes = message instanceof org.springframework.web.socket.TextMessage text ? text.asBytes().length : message.getPayloadLength();
            if (bytes > MAX_BYTES || queuedBytes + bytes > MAX_BYTES || queue.size() >= 128) {
                queue.clear();
                queuedBytes = 0;
                close(CloseStatus.SESSION_NOT_RELIABLE);
                throw new IOException("Slow client exceeded the delivery queue");
            }
            queue.addLast(new Delivery(message, null, bytes));
            queuedBytes += bytes;
            startWriter();
        }

        @Override
        public synchronized void close(CloseStatus status) {
            if (closing) return;
            closing = true;
            // Keep the latest state/error before closing, discard superseded queued snapshots.
            Delivery last = queue.peekLast();
            queue.clear();
            queuedBytes = 0;
            if (last != null && last.message != null) {
                queue.add(last);
                queuedBytes = last.bytes;
            }
            queue.addLast(new Delivery(null, status, 0));
            startWriter();
        }

        @Override
        public void close() { close(CloseStatus.NORMAL); }

        private void startWriter() {
            if (writing) return;
            writing = true;
            writers.execute(this::drain);
        }

        private void drain() {
            while (true) {
                Delivery delivery;
                synchronized (this) {
                    delivery = queue.pollFirst();
                    if (delivery == null) { writing = false; return; }
                    queuedBytes -= delivery.bytes;
                }
                // ConcurrentWebSocketSessionDecorator alone cannot interrupt its first blocked write.
                ScheduledFuture<?> timeout = deadlines.schedule(() -> writers.execute(this::abort), 5, TimeUnit.SECONDS);
                try {
                    if (delivery.status != null) {
                        getDelegate().close(delivery.status);
                        return;
                    }
                    getDelegate().sendMessage(delivery.message);
                } catch (Exception failure) {
                    abort();
                    return;
                } finally { timeout.cancel(false); }
            }
        }

        private void abort() {
            synchronized (this) { closing = true; queue.clear(); queuedBytes = 0; }
            try { getDelegate().close(CloseStatus.SESSION_NOT_RELIABLE); }
            catch (IOException ignored) { /* Peer is already gone. */ }
        }
    }

    private record Delivery(WebSocketMessage<?> message, CloseStatus status, int bytes) {}
}
