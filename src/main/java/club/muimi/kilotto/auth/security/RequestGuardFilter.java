package club.muimi.kilotto.auth.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@RequiredArgsConstructor
public class RequestGuardFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 16 * 1024;
    private final RequestRateLimiter limiter;
    private final RequestLimits limits;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        String key = null;
        int rate = 0;
        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(path)) {
            key = "login:" + request.getRemoteAddr(); rate = limits.loginsPerMinute();
        } else if (path.startsWith("/ws/rooms/")) {
            key = "upgrade:" + request.getRemoteAddr(); rate = limits.upgradesPerMinute();
        }
        // Forwarded headers are deliberately not used as unauthenticated identity.
        if (key != null && !limiter.allow(key, rate)) {
            response.setHeader("Retry-After", "60");
            error(response, 429, "请求过于频繁，请稍后重试");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            error(response, 413, "请求内容超过 16 KiB 限制");
            return;
        }
        if (path.startsWith("/api/") && ("POST".equals(request.getMethod()) || "PATCH".equals(request.getMethod()))) {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                private ServletInputStream guarded;
                @Override
                public ServletInputStream getInputStream() throws IOException {
                    if (guarded == null) guarded = new LimitedInputStream(super.getInputStream());
                    return guarded;
                }
            }, response);
        } else chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    public static class PayloadTooLargeException extends IOException {}

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private long bytes;
        LimitedInputStream(ServletInputStream delegate) { this.delegate = delegate; }

        private void count(int amount) throws IOException {
            if (amount > 0 && (bytes += amount) > MAX_BODY_BYTES) throw new PayloadTooLargeException();
        }

        @Override public int read() throws IOException { int value = delegate.read(); count(value < 0 ? 0 : 1); return value; }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            int read = delegate.read(data, offset, Math.min(length, MAX_BODY_BYTES + 1)); count(read); return read;
        }
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
