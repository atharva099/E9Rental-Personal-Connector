package com.e9rental.config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class LocalApiGuard extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = LocalApiGuard.class.getName()+".principal";
    private final String token;
    public LocalApiGuard(@Value("${LOCAL_API_TOKEN:}") String token) { this.token = token; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Content-Type-Options", "nosniff");
        String path = req.getRequestURI();
        if (path.equals("/health") ||
                (path.equals("/") && req.getMethod().equals("GET")) ||
                (path.equals("/api/drive/oauth/callback") && req.getMethod().equals("GET"))) {
            chain.doFilter(req,res); return;
        }
        String provided = req.getHeader("Authorization");
        if (token.length() < 32 || provided == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401); res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Local bearer token required\"}"); return;
        }
        req.setAttribute(PRINCIPAL_ATTRIBUTE,principal(token));
        chain.doFilter(req,res);
    }
    private static String principal(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest is unavailable"); }
    }
}
