package club.muimi.kilotto.auth.service;

import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.dto.CreateUserRequest;
import club.muimi.kilotto.auth.dto.UserView;
import club.muimi.kilotto.auth.entity.User;
import club.muimi.kilotto.auth.enums.Role;
import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import club.muimi.kilotto.room.RoomService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserDao users;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final AccountAccess access;
    private final TransactionTemplate transactions;
    private final RoomService rooms;

    public UserView create(CreateUserRequest request, SecurityUser principal) {
        return access.asAdmin(principal, actor -> createUser(request));
    }

    public boolean initializeAdmin(String username, String password) {
        return access.exclusive(() -> {
            if (users.count() != 0) return false;
            createUser(new CreateUserRequest(username, password, Role.ADMIN));
            return true;
        });
    }

    private UserView createUser(CreateUserRequest request) {
        if (!validator.validate(request).isEmpty()
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名需为 3–50 位字母、数字或 _.-，密码需为 12–72 位且不超过 72 字节");
        }
        // Expensive hashing runs before taking SQLite's single write connection.
        String hash = passwords.encode(request.password());
        return transactions.execute(status -> {
            if (users.existsByUsername(request.username())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
            }
            User user = new User();
            user.setUsername(request.username());
            user.setPassword(hash);
            user.setRole(request.role());
            return UserView.from(users.saveAndFlush(user));
        });
    }

    public UserView setEnabled(SecurityUser principal, Long targetId, boolean enabled) {
        return access.exclusive(() -> access.asAdmin(principal, actor -> {
            if (actor.id().equals(targetId) && !enabled) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能禁用自身账户");
            }
            UserView result = transactions.execute(status -> {
                User user = users.findById(targetId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
                if (!enabled) user.setAuthVersion(user.getAuthVersion() + 1);
                user.setEnabled(enabled);
                return UserView.from(users.saveAndFlush(user));
            });
            // The database commit and room cleanup finish before any new room operation is admitted.
            if (!enabled) rooms.userDisabled(targetId);
            return result;
        }));
    }
}
