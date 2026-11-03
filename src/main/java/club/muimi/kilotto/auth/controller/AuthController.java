package club.muimi.kilotto.auth.controller;

import club.muimi.kilotto.auth.dao.UserDao;
import club.muimi.kilotto.auth.dto.LoginRequest;
import club.muimi.kilotto.auth.dto.UserView;
import club.muimi.kilotto.auth.security.entity.SecurityUser;
import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.room.RoomService;
import club.muimi.kilotto.room.websocket.RoomHandshakeInterceptor;
import club.muimi.kilotto.room.websocket.SessionBinding;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository contextRepository;
    private final UserDao users;
    private final AccountAccess access;
    private final RoomService rooms;

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    UserView login(@Valid @RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(input.username(), input.password()));
        var principal = (SecurityUser) authentication.getPrincipal();
        return access.exclusive(() -> access.asUser(principal, current -> {
            var session = request.getSession(true);
            session.setAttribute(SessionBinding.REVISION, SessionBinding.revision(session) + 1);
            rooms.sessionEnded((String) session.getAttribute(RoomHandshakeInterceptor.GUEST_ID));
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current, null, current.getAuthorities()));
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            return UserView.from(users.findById(current.id()).orElseThrow());
        }));
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal SecurityUser principal) {
        return access.asUser(principal, current -> UserView.from(users.findById(current.id()).orElseThrow()));
    }
}
