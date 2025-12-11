package com.example.soj.admin;

import com.example.soj.Config;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory session tokens for the admin panel. */
public final class AdminSessionManager {
  private final long ttlMs;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final SecureRandom rng = new SecureRandom();

  public AdminSessionManager(Config.Admin cfg) {
    long minutes = Math.max(1, cfg.sessionMinutes);
    long max = Long.MAX_VALUE / 60000L;
    this.ttlMs = Math.min(minutes, max) * 60000L;
  }

  public String issue(String mcUser) {
    purgeExpired();
    String token = generateToken();
    long now = System.currentTimeMillis();
    sessions.put(token, new Session(mcUser, now + ttlMs));
    return token;
  }

  public Session verify(String token) {
    purgeExpired();
    Session s = sessions.get(token);
    if (s == null) return null;
    if (s.expiresAt() <= System.currentTimeMillis()) {
      sessions.remove(token);
      return null;
    }
    return s;
  }

  public record Session(String mcUser, long expiresAt) {}

  private void purgeExpired() {
    long now = System.currentTimeMillis();
    sessions.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    rng.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
