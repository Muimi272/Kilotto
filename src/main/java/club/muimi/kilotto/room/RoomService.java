package club.muimi.kilotto.room;

import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.auth.enums.Role;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import club.muimi.kilotto.room.websocket.SessionBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RoomService {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int MAX_ROOMS = 500;
    private static final int MAX_PARTICIPANTS = 1000;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json;
    private final AccountAccess access;
    private final Clock clock;

    public RoomView create(String title, SecurityUser principal) {
        return access.asUser(principal, actor -> createRoom(title, actor));
    }

    private synchronized RoomView createRoom(String title, SecurityUser owner) {
        pruneExpired();
        if (rooms.size() >= MAX_ROOMS) fail(HttpStatus.TOO_MANY_REQUESTS, "房间数量已达上限，请稍后再试");
        long owned = rooms.values().stream().filter(room -> room.ownerId.equals(owner.id()) && !room.closed).count();
        if (owned >= 10) fail(HttpStatus.TOO_MANY_REQUESTS, "每位用户最多同时创建 10 个房间");
        long retained = rooms.values().stream().filter(room -> room.ownerId.equals(owner.id())).count();
        if (retained >= 50) fail(HttpStatus.TOO_MANY_REQUESTS, "每位用户最多保留 50 个房间，请等待已结束的房间过期");
        String code;
        do {
            StringBuilder value = new StringBuilder(8);
            for (int i = 0; i < 8; i++) value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = value.toString();
        } while (rooms.containsKey(code));
        Room room = new Room(code, title.strip(), owner.id(), owner.username(), clock.instant());
        rooms.put(code, room);
        return snapshot(room);
    }

    public List<RoomSummary> list(SecurityUser principal, boolean all) {
        return access.asUser(principal, actor -> listRooms(actor, all));
    }

    private List<RoomSummary> listRooms(SecurityUser actor, boolean all) {
        if (all && !actor.isAdmin()) fail(HttpStatus.FORBIDDEN, "需要管理员权限");
        pruneExpired();
        return rooms.values().stream().filter(room -> all || room.ownerId.equals(actor.id()))
                .map(room -> { synchronized (room) {
                    return new RoomSummary(room.code, room.title, room.ownerId, room.ownerName,
                            room.closed ? "CLOSED" : "OPEN", room.createdAt, onlineIds(room).size(),
                            room.participants.size(), room.winners.size());
                } })
                .sorted(Comparator.comparing(RoomSummary::createdAt).reversed()).toList();
    }

    public RoomView inspect(String code, SecurityUser principal) {
        return access.asUser(principal, actor -> inspectRoom(code, actor));
    }

    private RoomView inspectRoom(String code, SecurityUser principal) {
        Room room = find(code);
        synchronized (room) {
            authorize(room, principal, true);
            return snapshot(room);
        }
    }

    public RoomView draw(String code, int count, SecurityUser principal) {
        return draw(code, count, null, principal);
    }

    public RoomView draw(String code, int count, UUID requestId, SecurityUser principal) {
        return access.asUser(principal, actor -> drawRoom(code, count, requestId, actor));
    }

    private RoomView drawRoom(String code, int count, UUID requestId, SecurityUser principal) {
        Room room = find(code);
        synchronized (room) {
            authorize(room, principal, false);
            if (count < 1 || count > 100) fail(HttpStatus.BAD_REQUEST, "每次抽取人数需在 1–100 之间");
            if (requestId != null && room.drawRequests.containsKey(requestId)) {
                if (room.drawRequests.get(requestId) != count) fail(HttpStatus.CONFLICT, "同一抽奖请求不能更改人数");
                return snapshot(room);
            }
            open(room);
            Set<String> online = onlineIds(room);
            List<Participant> eligible = room.participants.values().stream()
                    .filter(person -> !person.won && online.contains(person.id)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (eligible.size() < count) fail(HttpStatus.CONFLICT, "在线且尚未中奖的人数不足");
            // Fisher–Yates with SecureRandom: each eligible guest has equal probability.
            int round = ++room.round;
            Instant drawnAt = clock.instant();
            for (int i = 0; i < count; i++) {
                int selected = i + random.nextInt(eligible.size() - i);
                Collections.swap(eligible, i, selected);
                Participant winner = eligible.get(i);
                winner.won = true;
                room.winners.add(new RoomView.WinnerView(winner.id, winner.nickname, round, drawnAt));
            }
            room.version++;
            if (requestId != null) room.drawRequests.put(requestId, count);
            broadcast(room);
            return snapshot(room);
        }
    }

    public RoomView close(String code, SecurityUser principal) {
        return access.asUser(principal, actor -> closeRoom(code, actor));
    }

    private RoomView closeRoom(String code, SecurityUser principal) {
        Room room = find(code);
        synchronized (room) {
            SecurityUser actor = authorize(room, principal, true);
            terminate(room, actor.id().equals(room.ownerId) ? "房主已结束房间" : "管理员已中断房间");
            return snapshot(room);
        }
    }

    public void userDisabled(Long id) {
        for (Room room : rooms.values()) {
            synchronized (room) {
                if (room.ownerId.equals(id)) {
                    terminate(room, "房主账户已被禁用");
                } else {
                    var removed = room.connections.values().stream().filter(c -> id.equals(c.userId)).toList();
                    removed.forEach(c -> {
                        room.connections.remove(c.session.getId());
                        closeSocket(c.session, CloseStatus.POLICY_VIOLATION);
                    });
                    if (!removed.isEmpty()) { room.version++; broadcast(room); }
                }
            }
        }
    }

    public void connect(String code, String guestId, String nickname, boolean watch,
                        SecurityUser principal, WebSocketSession socket) {
        access.read(() -> {
            connectRoom(code, guestId, nickname, watch, principal, socket);
            return null;
        });
    }

    private void connectRoom(String code, String guestId, String nickname, boolean watch,
                             SecurityUser principal, WebSocketSession socket) {
        Object binding = socket.getAttributes().get(SessionBinding.ATTRIBUTE);
        if (binding instanceof SessionBinding session) {
            if (!session.isCurrent()) fail(HttpStatus.UNAUTHORIZED, "会话已改变，请重新连接");
            principal = session.principal();
        }
        Room room = find(code);
        synchronized (room) {
            open(room);
            // Hosts and administrators always observe, even if mode=watch was omitted.
            SecurityUser actor = principal == null ? null : active(principal);
            boolean observer = actor != null && (actor.id().equals(room.ownerId) || actor.isAdmin());
            if (watch && !observer) fail(HttpStatus.FORBIDDEN, "仅房主或管理员可旁观");
            var previous = room.connections.values().stream().filter(c -> guestId.equals(c.guestId)).toList();
            if (room.connections.size() - previous.size() >= 1200) fail(HttpStatus.TOO_MANY_REQUESTS, "房间连接数已达上限");
            if (!observer) {
                if (nickname == null || nickname.isBlank()) nickname = "游客 " + guestId.substring(0, 4);
                nickname = nickname.strip();
                if (nickname.length() > 30 || nickname.codePoints().anyMatch(Character::isISOControl)) {
                    fail(HttpStatus.BAD_REQUEST, "昵称需为 1–30 个字符且不能包含控制字符");
                }
                if (!room.participants.containsKey(guestId)) {
                    if (room.participants.size() >= MAX_PARTICIPANTS) fail(HttpStatus.TOO_MANY_REQUESTS, "房间累计参与人数已达上限");
                    room.participants.put(guestId, new Participant(guestId, nickname));
                }
                // One active connection per guest in each room; reconnect keeps identity and winner status.
            }
            previous.forEach(c -> {
                room.connections.remove(c.session.getId());
                closeSocket(c.session, new CloseStatus(4001, "Connected in another tab"));
            });
            room.connections.put(socket.getId(), new Connection(socket, guestId, observer ? null : guestId, actor == null ? null : actor.id(), clock.instant()));
            send(socket, Map.of("type", "joined", "participantId", observer ? "" : guestId, "observer", observer));
            room.version++;
            broadcast(room);
        }
    }

    public void disconnect(String code, String sessionId) {
        Room room = rooms.get(code);
        if (room == null) return;
        synchronized (room) {
            if (room.connections.remove(sessionId) != null && !room.closed) {
                room.version++;
                broadcast(room);
            }
        }
    }

    public void sessionEnded(String guestId) {
        if (guestId == null) return;
        for (Room room : rooms.values()) {
            synchronized (room) {
                var removed = room.connections.values().stream().filter(c -> guestId.equals(c.guestId)).toList();
                removed.forEach(c -> {
                    room.connections.remove(c.session.getId());
                    closeSocket(c.session, new CloseStatus(4002, "Session changed"));
                });
                if (!removed.isEmpty() && !room.closed) { room.version++; broadcast(room); }
            }
        }
    }

    @Scheduled(fixedDelay = 25_000)
    public void maintain() {
        pruneExpired();
        Instant now = clock.instant();
        for (Room room : rooms.values()) {
            synchronized (room) {
                boolean changed = false;
                for (Connection connection : List.copyOf(room.connections.values())) {
                    try {
                        if (!connection.session.isOpen() || !connection.lastPong.plusSeconds(75).isAfter(now)) {
                            room.connections.remove(connection.session.getId());
                            closeSocket(connection.session, CloseStatus.SESSION_NOT_RELIABLE);
                            changed = true;
                            continue;
                        }
                        connection.session.sendMessage(new PingMessage());
                    } catch (Exception error) {
                        room.connections.remove(connection.session.getId());
                        closeSocket(connection.session, CloseStatus.SERVER_ERROR);
                        changed = true;
                    }
                }
                if (changed && !room.closed) { room.version++; broadcast(room); }
            }
        }
    }

    public void pong(String code, String sessionId) {
        Room room = rooms.get(code);
        if (room == null) return;
        synchronized (room) {
            Connection connection = room.connections.get(sessionId);
            if (connection != null) connection.lastPong = clock.instant();
        }
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        for (Room room : rooms.values()) {
            synchronized (room) {
                if (!room.closed && !room.createdAt.plus(Duration.ofHours(24)).isAfter(now)) terminate(room, "房间已到期");
                if (room.closed && !room.closedAt.plus(Duration.ofHours(1)).isAfter(now)) rooms.remove(room.code, room);
            }
        }
    }

    private Room find(String code) {
        if (code == null || !code.matches("[2-9A-HJ-NP-Za-hj-np-z]{8}")) fail(HttpStatus.NOT_FOUND, "房间不存在或已过期");
        Room room = rooms.get(code.toUpperCase(Locale.ROOT));
        if (room == null) fail(HttpStatus.NOT_FOUND, "房间不存在或已过期");
        synchronized (room) {
            if (!room.closed && !room.createdAt.plus(Duration.ofHours(24)).isAfter(clock.instant())) terminate(room, "房间已到期");
            if (room.closed && !room.closedAt.plus(Duration.ofHours(1)).isAfter(clock.instant())) {
                rooms.remove(room.code, room);
                fail(HttpStatus.NOT_FOUND, "房间不存在或已过期");
            }
        }
        return room;
    }

    private SecurityUser active(SecurityUser principal) {
        return access.current(principal);
    }

    private SecurityUser authorize(Room room, SecurityUser principal, boolean allowAdmin) {
        SecurityUser actor = principal;
        if (!room.ownerId.equals(actor.id()) && !(allowAdmin && actor.role() == Role.ADMIN)) {
            fail(HttpStatus.FORBIDDEN, "无权操作此房间");
        }
        return actor;
    }

    private void open(Room room) {
        if (!room.closed && !room.createdAt.plus(Duration.ofHours(24)).isAfter(clock.instant())) terminate(room, "房间已到期");
        if (room.closed) fail(HttpStatus.GONE, "房间已结束");
    }

    private Set<String> onlineIds(Room room) {
        Set<String> online = new HashSet<>();
        if (!room.closed) room.connections.values().stream().filter(c -> c.session.isOpen() && c.participantId != null
                        && c.lastPong.plusSeconds(75).isAfter(clock.instant()))
                .forEach(c -> online.add(c.participantId));
        return online;
    }

    private RoomView snapshot(Room room) {
        Set<String> online = onlineIds(room);
        return new RoomView(room.code, room.title, room.ownerId, room.ownerName,
                room.closed ? "CLOSED" : "OPEN", room.closeReason, room.createdAt, room.version,
                room.participants.values().stream().map(p -> new RoomView.ParticipantView(p.id, p.nickname, online.contains(p.id), p.won)).toList(),
                List.copyOf(room.winners));
    }

    private void broadcast(Room room) {
        boolean removed;
        do {
            TextMessage message = new TextMessage(json.writeValueAsString(Map.of("type", "room", "room", snapshot(room))));
            removed = false;
            for (Connection connection : List.copyOf(room.connections.values())) {
                if (!send(connection.session, message)) {
                    room.connections.remove(connection.session.getId());
                    removed = true;
                }
            }
            // Correct the roster immediately for peers after a failed send.
            if (removed) room.version++;
        } while (removed && !room.closed && !room.connections.isEmpty());
    }

    public void sendError(WebSocketSession socket, String detail) {
        send(socket, Map.of("type", "error", "detail", detail));
    }

    private boolean send(WebSocketSession socket, Object payload) {
        return send(socket, new TextMessage(json.writeValueAsString(payload)));
    }

    private boolean send(WebSocketSession socket, TextMessage message) {
        try {
            if (!socket.isOpen()) return false;
            socket.sendMessage(message);
            return true;
        } catch (Exception error) {
            closeSocket(socket, CloseStatus.SERVER_ERROR);
            return false;
        }
    }

    private void terminate(Room room, String reason) {
        if (room.closed) return;
        room.closed = true;
        room.closedAt = clock.instant();
        room.closeReason = reason;
        room.version++;
        broadcast(room);
        var connections = List.copyOf(room.connections.values());
        room.connections.clear();
        connections.forEach(c -> closeSocket(c.session, CloseStatus.NORMAL));
    }

    private void closeSocket(WebSocketSession socket, CloseStatus status) {
        try { socket.close(status); } catch (IOException ignored) { /* Already disconnected. */ }
    }

    private static void fail(HttpStatus status, String message) { throw new ResponseStatusException(status, message); }

    private static final class Room {
        final String code;
        final String title;
        final Long ownerId;
        final String ownerName;
        final Instant createdAt;
        final Map<String, Participant> participants = new LinkedHashMap<>();
        final Map<String, Connection> connections = new LinkedHashMap<>();
        final List<RoomView.WinnerView> winners = new ArrayList<>();
        final Map<UUID, Integer> drawRequests = new HashMap<>();
        volatile boolean closed;
        Instant closedAt;
        String closeReason;
        long version;
        int round;
        Room(String code, String title, Long ownerId, String ownerName, Instant createdAt) {
            this.code = code; this.title = title; this.ownerId = ownerId; this.ownerName = ownerName; this.createdAt = createdAt;
        }
    }

    private static final class Participant {
        final String id;
        final String nickname;
        boolean won;
        Participant(String id, String nickname) { this.id = id; this.nickname = nickname; }
    }

    private static final class Connection {
        final WebSocketSession session;
        final String guestId;
        final String participantId;
        final Long userId;
        Instant lastPong;
        Connection(WebSocketSession session, String guestId, String participantId, Long userId, Instant lastPong) {
            this.session = session; this.guestId = guestId; this.participantId = participantId;
            this.userId = userId; this.lastPong = lastPong;
        }
    }
}
