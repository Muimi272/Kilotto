package club.muimi.kilotto.auth.security;

import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/** Lock order: account lifecycle, then a room. Database transactions commit before releasing this lock. */
@Component
@RequiredArgsConstructor
public class AccountAccess {
    private final UserDao users;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);

    public SecurityUser current(SecurityUser principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return users.findById(principal.id())
                .filter(user -> user.isEnabled() && user.getAuthVersion() == principal.authVersion())
                .map(SecurityUser::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录"));
    }

    public <T> T asUser(SecurityUser principal, Function<SecurityUser, T> operation) {
        return read(() -> operation.apply(current(principal)));
    }

    public <T> T asAdmin(SecurityUser principal, Function<SecurityUser, T> operation) {
        return asUser(principal, actor -> {
            if (!actor.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
            return operation.apply(actor);
        });
    }

    public <T> T read(Supplier<T> operation) {
        var lock = lifecycle.readLock();
        lock.lock();
        try { return operation.get(); } finally { lock.unlock(); }
    }

    public <T> T exclusive(Supplier<T> operation) {
        var lock = lifecycle.writeLock();
        lock.lock();
        try { return operation.get(); } finally { lock.unlock(); }
    }
}
