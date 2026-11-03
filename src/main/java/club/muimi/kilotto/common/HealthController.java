package club.muimi.kilotto.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/health")
    Map<?, ?> health() { return Map.of("status", "ok"); }
}
