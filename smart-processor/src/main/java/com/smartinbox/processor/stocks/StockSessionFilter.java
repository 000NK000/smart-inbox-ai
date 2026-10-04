package com.smartinbox.processor.stocks;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local UI capability; blocks cross-origin sites and non-loopback callers, including forwarded clients. */
@Component @Order(-100)
public class StockSessionFilter extends OncePerRequestFilter {
    private final Map<String,Long> sessions = new ConcurrentHashMap<>();
    public String issue() {
        long now = System.currentTimeMillis(); sessions.entrySet().removeIf(e -> e.getValue() < now);
        if (sessions.size() >= 100) throw new IllegalStateException("Too many stock sessions");
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        sessions.put(token,now+30*60_000L); return token;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        String path=r.getRequestURI();
        try {
            // Match Spring's decoded path and matrix-parameter routing before deciding whether to guard it.
            for(int i=0;i<2 && path.contains("%");i++) path=java.net.URLDecoder.decode(path,java.nio.charset.StandardCharsets.UTF_8);
            path=path.replaceAll(";[^/]*","").replaceAll("/{2,}","/");
            path=URI.create(path).normalize().getPath();
            return !(path.equals("/api/stocks") || path.startsWith("/api/stocks/"));
        } catch (Exception ignored) { return false; }
    }
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws ServletException,IOException {
        s.setHeader("Cache-Control","no-store"); s.setHeader("Referrer-Policy","no-referrer"); s.setHeader("X-Content-Type-Options","nosniff");
        if(r.getRequestURI().contains("%") || r.getRequestURI().contains(";") || r.getRequestURI().contains("//") || r.getRequestURI().contains("/../")) { deny(s); return; }
        boolean callback = r.getMethod().equals("GET") && Set.of("/api/stocks/auth/ibkr/callback","/api/stocks/auth/gpt/callback").contains(r.getRequestURI());
        if (!loopback(r.getRemoteAddr()) || !localHost(r.getServerName())) { deny(s); return; }
        String forwarded = r.getHeader("X-Forwarded-For");
        if (forwarded != null && Arrays.stream(forwarded.split(",")).anyMatch(a -> !loopback(a.trim()))) { deny(s); return; }
        // OAuth navigation comes from the provider, but is protected by single-use PKCE and state in its service.
        if (callback) { chain.doFilter(r,s); return; }
        String origin = r.getHeader("Origin");
        if ((origin != null && !allowedOrigin(origin)) || "cross-site".equalsIgnoreCase(r.getHeader("Sec-Fetch-Site"))) { deny(s); return; }
        if (r.getMethod().equals("GET") && r.getRequestURI().equals("/api/stocks/session")) { chain.doFilter(r,s); return; }
        String token = r.getHeader("X-Stock-Session");
        Long expiry = token == null ? null : sessions.get(token);
        if (expiry == null || expiry < System.currentTimeMillis()) { deny(s); return; }
        chain.doFilter(r,s);
    }
    static boolean allowedOrigin(String text) {
        try { URI u = URI.create(text); return "http".equals(u.getScheme()) && localHost(u.getHost()) && Set.of(5173,8080,8083).contains(u.getPort()) && u.getUserInfo()==null; }
        catch (Exception ignored) { return false; }
    }
    private static boolean localHost(String value) { return Set.of("localhost","127.0.0.1","::1","[::1]").contains(String.valueOf(value)); }
    private static boolean loopback(String value) { return Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(String.valueOf(value)); }
    private static void deny(HttpServletResponse s) throws IOException { s.setStatus(403); s.setContentType("application/json;charset=UTF-8"); s.getWriter().write("{\"message\":\"股票页面连接已过期，请重新打开此页面\"}"); }
}
