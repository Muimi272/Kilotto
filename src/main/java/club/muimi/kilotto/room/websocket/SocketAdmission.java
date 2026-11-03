package club.muimi.kilotto.room.websocket;

import club.muimi.kilotto.auth.security.RequestLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SocketAdmission {
    private final RequestLimits limits;
    private final Map<String, Entry> connections = new HashMap<>();

    public synchronized boolean admit(String guestId, String room, String connectionId) {
        if (connections.size() >= limits.connections()) return false;
        long own = connections.values().stream().filter(value -> value.guestId.equals(guestId)).count();
        boolean replacing = connections.values().stream().anyMatch(value -> value.guestId.equals(guestId) && value.room.equals(room));
        if (own >= limits.guestConnections() && !(replacing && own == limits.guestConnections())) return false;
        connections.put(connectionId, new Entry(guestId, room));
        return true;
    }

    public synchronized void release(String connectionId) { connections.remove(connectionId); }
    private record Entry(String guestId, String room) {}
}
