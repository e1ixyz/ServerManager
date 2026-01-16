package com.example.soj.moderation;

import com.example.soj.Config;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoIpBanService {
  public enum EventType { CONNECTION, PING, BAD_USERNAME }

  public record Decision(boolean banned, ModerationService.Entry entry) {}

  private static final long SWEEP_INTERVAL_MS = 60_000L;
  private static final long DEFAULT_BAN_MINUTES = 60L;

  private final Config.AutoIpBan cfg;
  private final ModerationService moderation;
  private final Logger log;
  private final Map<String, IpCounters> counters = new ConcurrentHashMap<>();
  private final List<Cidr> trustedCidrs = new ArrayList<>();
  private final long maxWindowMs;
  private volatile long lastSweepMs = 0L;

  public AutoIpBanService(Config.AutoIpBan cfg, ModerationService moderation, Logger log) {
    this.cfg = cfg;
    this.moderation = moderation;
    this.log = log;
    this.maxWindowMs = Math.max(60_000L, calcMaxWindowMs(cfg));
    if (cfg != null && cfg.trustedCidrs != null) {
      for (String raw : cfg.trustedCidrs) {
        Cidr parsed = Cidr.parse(raw);
        if (parsed != null) trustedCidrs.add(parsed);
      }
    }
  }

  public boolean enabled() {
    return cfg != null && cfg.enabled;
  }

  public boolean isBadUsername(String username) {
    if (username == null) return true;
    return !username.matches("^[A-Za-z0-9_]{3,16}$");
  }

  public Decision record(String ip, EventType type, String username) {
    if (!enabled()) return new Decision(false, null);
    String normalized = normalizeIp(ip);
    if (normalized == null || isTrusted(normalized)) return new Decision(false, null);
    if (moderation != null && moderation.enabled()) {
      ModerationService.Entry existing = moderation.findBan(null, normalized);
      if (existing != null) {
        return new Decision(true, existing);
      }
    }

    sweepIfNeeded();

    long now = System.currentTimeMillis();
    IpCounters bucket = counters.computeIfAbsent(normalized, k -> new IpCounters());
    bucket.lastSeenMs = now;

    Config.Threshold threshold = thresholdFor(type);
    if (threshold == null || threshold.limit <= 0 || threshold.windowSeconds <= 0) {
      return new Decision(false, null);
    }

    boolean triggered = bucket.record(type, now, threshold.windowSeconds * 1000L, threshold.limit);
    if (!triggered) return new Decision(false, null);

    String reason = buildReason(type, username);
    if (cfg.dryRun) {
      log.warn("AutoIpBan(dry-run) would ban {} for {}", normalized, reason);
      return new Decision(false, null);
    }
    if (moderation == null || !moderation.enabled()) {
      log.warn("AutoIpBan triggered for {} but moderation is disabled. Reason: {}", normalized, reason);
      return new Decision(false, null);
    }

    long expiresAt = banExpiry(now);
    ModerationService.Entry entry = moderation.banIpAsync(normalized, reason, "auto", expiresAt);
    if (entry == null) {
      return new Decision(false, null);
    }
    log.warn("Auto-banned {} for {}", normalized, reason);
    return new Decision(true, entry);
  }

  private long banExpiry(long now) {
    long minutes = cfg == null ? DEFAULT_BAN_MINUTES : cfg.banMinutes;
    if (minutes <= 0) minutes = DEFAULT_BAN_MINUTES;
    return now + (minutes * 60_000L);
  }

  private Config.Threshold thresholdFor(EventType type) {
    if (cfg == null || cfg.thresholds == null) return null;
    return switch (type) {
      case CONNECTION -> cfg.thresholds.connections;
      case PING -> cfg.thresholds.pings;
      case BAD_USERNAME -> cfg.thresholds.badUsernames;
    };
  }

  private String buildReason(EventType type, String username) {
    String prefix = cfg == null ? "Auto-ban" : Objects.toString(cfg.reasonPrefix, "Auto-ban").trim();
    if (prefix.isEmpty()) prefix = "Auto-ban";
    String detail = switch (type) {
      case CONNECTION -> "connection flood";
      case PING -> "ping flood";
      case BAD_USERNAME -> "invalid username spam";
    };
    if (type == EventType.BAD_USERNAME && username != null && !username.isBlank()) {
      detail = detail + " (" + username + ")";
    }
    return prefix + ": " + detail;
  }

  private void sweepIfNeeded() {
    long now = System.currentTimeMillis();
    if (now - lastSweepMs < SWEEP_INTERVAL_MS) return;
    lastSweepMs = now;
    long cutoff = now - (maxWindowMs * 2);
    counters.entrySet().removeIf(e -> e.getValue().lastSeenMs < cutoff);
  }

  private boolean isTrusted(String ip) {
    if (cfg == null) return false;
    if (cfg.trustedIps != null) {
      for (String raw : cfg.trustedIps) {
        String normalized = normalizeIp(raw);
        if (normalized != null && normalized.equalsIgnoreCase(ip)) return true;
      }
    }
    if (!trustedCidrs.isEmpty()) {
      for (Cidr cidr : trustedCidrs) {
        if (cidr.matches(ip)) return true;
      }
    }
    return false;
  }

  private long calcMaxWindowMs(Config.AutoIpBan cfg) {
    if (cfg == null || cfg.thresholds == null) return 60_000L;
    long max = 0L;
    max = Math.max(max, windowSeconds(cfg.thresholds.connections));
    max = Math.max(max, windowSeconds(cfg.thresholds.pings));
    max = Math.max(max, windowSeconds(cfg.thresholds.badUsernames));
    return Math.max(1L, max) * 1000L;
  }

  private long windowSeconds(Config.Threshold threshold) {
    return threshold == null ? 0L : threshold.windowSeconds;
  }

  private String normalizeIp(String ip) {
    if (ip == null) return null;
    String s = ip.trim();
    if (s.startsWith("/")) s = s.substring(1);
    if (s.isEmpty()) return null;
    if (s.contains(":") && s.indexOf(':') == s.lastIndexOf(':') && s.contains(".")) {
      s = s.substring(0, s.indexOf(':'));
    }
    try {
      return InetAddress.getByName(s).getHostAddress();
    } catch (Exception ignored) {
      return s;
    }
  }

  private static final class IpCounters {
    private final SlidingWindow connections = new SlidingWindow();
    private final SlidingWindow pings = new SlidingWindow();
    private final SlidingWindow badUsernames = new SlidingWindow();
    private volatile long lastSeenMs = System.currentTimeMillis();

    boolean record(EventType type, long now, long windowMs, int limit) {
      return switch (type) {
        case CONNECTION -> connections.record(now, windowMs, limit);
        case PING -> pings.record(now, windowMs, limit);
        case BAD_USERNAME -> badUsernames.record(now, windowMs, limit);
      };
    }
  }

  private static final class SlidingWindow {
    private final ArrayDeque<Long> events = new ArrayDeque<>();

    synchronized boolean record(long now, long windowMs, int limit) {
      while (!events.isEmpty() && now - events.peekFirst() > windowMs) {
        events.removeFirst();
      }
      events.addLast(now);
      return events.size() >= limit;
    }
  }

  private static final class Cidr {
    private final int network;
    private final int mask;

    private Cidr(int network, int mask) {
      this.network = network;
      this.mask = mask;
    }

    static Cidr parse(String raw) {
      if (raw == null) return null;
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) return null;
      String[] parts = trimmed.split("/", 2);
      if (parts.length != 2) return null;
      Integer ip = toIpv4(parts[0].trim());
      if (ip == null) return null;
      int bits;
      try {
        bits = Integer.parseInt(parts[1].trim());
      } catch (NumberFormatException ex) {
        return null;
      }
      if (bits < 0 || bits > 32) return null;
      int mask = bits == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - bits));
      return new Cidr(ip & mask, mask);
    }

    boolean matches(String ip) {
      Integer target = toIpv4(ip);
      if (target == null) return false;
      return (target & mask) == network;
    }

    private static Integer toIpv4(String ip) {
      if (ip == null) return null;
      String[] parts = ip.trim().split("\\.");
      if (parts.length != 4) return null;
      int value = 0;
      for (String part : parts) {
        int octet;
        try {
          octet = Integer.parseInt(part);
        } catch (NumberFormatException ex) {
          return null;
        }
        if (octet < 0 || octet > 255) return null;
        value = (value << 8) | octet;
      }
      return value;
    }
  }
}
