package club.muimi.kilotto.auth.security;

import club.muimi.kilotto.auth.security.entity.SecurityUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

// Deliberately registered only inside the Security filter chain (not as a servlet filter bean).
@RequiredArgsConstructor
public class ActiveUserFilter extends OncePerRequestFilter {
    private final AccountAccess access;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser principal) {
            try {
                SecurityUser current = access.asUser(principal, actor -> actor);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current, null, current.getAuthorities()));
                SecurityContextHolder.setContext(context);
            } catch (ResponseStatusException revoked) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
