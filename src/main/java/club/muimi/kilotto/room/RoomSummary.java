package club.muimi.kilotto.room;

import java.time.Instant;

public record RoomSummary(String code, String title, Long ownerId, String ownerName,
                          String status, Instant createdAt, int onlineCount, int participantCount, int winnerCount) {}
