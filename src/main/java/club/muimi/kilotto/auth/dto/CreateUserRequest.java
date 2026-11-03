package club.muimi.kilotto.auth.dto;

import club.muimi.kilotto.auth.enums.Role;
import jakarta.validation.constraints.*;

public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,50}") String username,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotNull Role role) {}
