package club.muimi.kilotto.auth.config;

import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {
    private final UserDao users;
    private final UserService userService;
    @Value("${kilotto.bootstrap.username:}")
    private String username;
    @Value("${kilotto.bootstrap.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() != 0) return;
        if (username.isBlank() || password.isBlank()) {
            log.warn("No users exist. Set KILOTTO_ADMIN_USERNAME and KILOTTO_ADMIN_PASSWORD to initialize the first administrator.");
            return;
        }
        if (userService.initializeAdmin(username, password)) log.info("Initial administrator created: {}", username);
    }
}
