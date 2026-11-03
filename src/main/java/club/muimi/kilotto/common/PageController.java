package club.muimi.kilotto.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Explicit document routes: unknown paths and API paths never fall through to the app shell. */
@Controller
public class PageController {
    @GetMapping({"/join", "/login", "/rooms", "/rooms/new", "/admin/users", "/admin/users/new",
            "/room/{code}", "/room/{code}/history"})
    String page() { return "forward:/index.html"; }
}
