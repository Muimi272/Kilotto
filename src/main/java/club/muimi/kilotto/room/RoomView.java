package club.muimi.kilotto.room;

import java.time.Instant;
import java.util.List;

public record RoomView(String code, String title, Long ownerId, String ownerName, String status,
                       String closeReason, Instant createdAt, long version,
                       List<ParticipantView> participants, List<WinnerView> winners) {
    public record ParticipantView(String id, String nickname, boolean online, boolean won) {}
    public record WinnerView(String participantId, String nickname, int round, Instant drawnAt) {}
}
