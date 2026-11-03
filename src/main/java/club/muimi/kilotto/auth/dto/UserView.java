package club.muimi.kilotto.auth.dto;

import club.muimi.kilotto.auth.entity.User;
import club.muimi.kilotto.auth.enums.Role;
import java.time.Instant;

public record UserView(Long id, String username, Role role, boolean enabled, Instant createdAt) {
    public static UserView from(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(), user.getCreatedAt());
    }
}
