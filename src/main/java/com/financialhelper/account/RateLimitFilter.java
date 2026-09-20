package com.financialhelper.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final AccountSessionService sessionService;
    private final AccountProperties properties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(AccountSessionService sessionService, AccountProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > properties.limits().maxRequestBytes()) {
            writeError(response, 413, "REQUEST_TOO_LARGE", "요청 크기가 허용 범위를 초과했습니다.");
            return;
        }
        if (!properties.limits().rateLimitEnabled() || !isLimitedPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = sessionService.currentAccountId().map(id -> "account:" + id)
                .orElseGet(() -> "ip:" + clientIp(request));
        Duration window = properties.limits().rateLimitWindow();
        long now = System.currentTimeMillis();
        Window candidate = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAt >= window.toMillis()) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt, existing.count + 1);
        });
        if (candidate.count > properties.limits().rateLimitRequests()) {
            writeError(response, 429, "RATE_LIMITED", "잠시 후 다시 시도해 주세요.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isLimitedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) return false;
        if (path.startsWith("/api/v1/emergency")) return false;
        return !path.startsWith("/api/v1/auth/") && !"GET".equalsIgnoreCase(request.getMethod());
    }

    private static String clientIp(HttpServletRequest request) {
        // 신뢰할 수 있는 proxy를 설정하지 않았다면 forwarded header를 신뢰하지 않는다.
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}}");
    }

    private record Window(long startedAt, int count) { }
}
