package club.muimi.kilotto.room;

import club.muimi.kilotto.auth.security.entity.SecurityUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService rooms;

    public record CreateRoom(@NotBlank @Size(max = 80) String title) {}
    public record Draw(@Min(1) @Max(100) int count, java.util.UUID requestId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RoomView create(@Valid @RequestBody CreateRoom input, @AuthenticationPrincipal SecurityUser actor) {
        return rooms.create(input.title(), actor);
    }

    @GetMapping
    List<RoomSummary> list(@AuthenticationPrincipal SecurityUser actor) { return rooms.list(actor, false); }

    @GetMapping("/{code}")
    RoomView inspect(@PathVariable String code, @AuthenticationPrincipal SecurityUser actor) { return rooms.inspect(code, actor); }

    @PostMapping("/{code}/draw")
    RoomView draw(@PathVariable String code, @Valid @RequestBody Draw input, @AuthenticationPrincipal SecurityUser actor) {
        return rooms.draw(code, input.count(), input.requestId(), actor);
    }

    @PostMapping("/{code}/close")
    RoomView close(@PathVariable String code, @AuthenticationPrincipal SecurityUser actor) { return rooms.close(code, actor); }
}
