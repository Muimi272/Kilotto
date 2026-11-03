package club.muimi.kilotto.admin;

import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.dto.CreateUserRequest;
import club.muimi.kilotto.auth.dto.UserView;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import club.muimi.kilotto.auth.service.UserService;
import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.room.RoomService;
import club.muimi.kilotto.room.RoomSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final UserDao users;
    private final UserService userService;
    private final RoomService rooms;
    private final AccountAccess access;

    public record EnableUser(@NotNull Boolean enabled) {}

    @GetMapping("/users")
    List<UserView> users(@RequestParam(defaultValue = "0") int page, @AuthenticationPrincipal SecurityUser actor) {
        if (page < 0 || page > 100_000) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "页码超出范围");
        return access.asAdmin(actor, current -> users.findAll(PageRequest.of(page, 100, Sort.by("id"))).map(UserView::from).getContent());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@Valid @RequestBody CreateUserRequest input, @AuthenticationPrincipal SecurityUser actor) {
        return userService.create(input, actor);
    }

    @PatchMapping("/users/{id}/enabled")
    UserView enabled(@PathVariable Long id, @Valid @RequestBody EnableUser input, @AuthenticationPrincipal SecurityUser actor) {
        return userService.setEnabled(actor, id, input.enabled());
    }

    @GetMapping("/rooms")
    List<RoomSummary> rooms(@AuthenticationPrincipal SecurityUser actor) { return rooms.list(actor, true); }
}
