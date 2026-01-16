package com.example.soj.moderation;

import com.example.soj.Config;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public final class ModerationService {
  public enum Type { BAN, IPBAN, MUTE }
  public record Entry(Type type, UUID uuid, String ip, String name, String reason, String actor, long createdAt, long expiresAt) {}

  private final Config.Moderation cfg;
  private final Path dataFile;
  private final Logger log;
  private final Yaml yaml = new Yaml();
  private final Map<UUID, Entry> bansByUuid = new ConcurrentHashMap<>();
  private final Map<String, Entry> bansByIp = new ConcurrentHashMap<>();
  private final Map<UUID, Entry> mutesByUuid = new ConcurrentHashMap<>();

  public ModerationService(Config.Moderation cfg, Logger log, Path dataDir) throws IOException {
    this.cfg = cfg;
    this.log = log;
    this.dataFile = dataDir.resolve(cfg.dataFile == null || cfg.dataFile.isBlank()
        ? "moderation.yml"
        : cfg.dataFile).normalize();
    load();
  }

  public boolean enabled() { return cfg != null && cfg.enabled; }

  public synchronized void banUuid(UUID uuid, String name, String reason, String actor, long expiresAt) throws IOException {
    Entry e = new Entry(Type.BAN, uuid, null, name, reason, actor, now(), expiresAt);
    bansByUuid.put(uuid, e);
    persist();
  }

  public synchronized void banIp(String ip, String reason, String actor, long expiresAt) throws IOException {
    String normalized = normalizeIp(ip);
    if (normalized == null) return;
    Entry e = new Entry(Type.IPBAN, null, normalized, null, reason, actor, now(), expiresAt);
    bansByIp.put(normalized, e);
    persist();
  }

  public Entry banIpAsync(String ip, String reason, String actor, long expiresAt) {
    String normalized = normalizeIp(ip);
    if (normalized == null) return null;
    Entry e = new Entry(Type.IPBAN, null, normalized, null, reason, actor, now(), expiresAt);
    synchronized (this) {
      bansByIp.put(normalized, e);
    }
    CompletableFuture.runAsync(() -> {
      synchronized (this) {
        try {
          persist();
        } catch (IOException ex) {
          log.error("Failed to persist auto-ban {}", normalized, ex);
        }
      }
    });
    return e;
  }

  public synchronized boolean unban(UUID uuid, String ip) throws IOException {
    boolean changed = false;
    if (uuid != null) changed |= (bansByUuid.remove(uuid) != null);
    if (ip != null) changed |= (bansByIp.remove(normalizeIp(ip)) != null);
    if (changed) persist();
    return changed;
  }

  public synchronized void mute(UUID uuid, String name, String reason, String actor, long expiresAt) throws IOException {
    if (uuid == null) return;
    Entry e = new Entry(Type.MUTE, uuid, null, name, reason, actor, now(), expiresAt);
    mutesByUuid.put(uuid, e);
    persist();
  }

  public synchronized boolean unmute(UUID uuid) throws IOException {
    if (uuid == null) return false;
    boolean changed = mutesByUuid.remove(uuid) != null;
    if (changed) persist();
    return changed;
  }

  public Entry findBan(UUID uuid, String ip) {
    purgeExpired();
    Entry e = uuid == null ? null : bansByUuid.get(uuid);
    if (e != null) return e;
    String normalized = normalizeIp(ip);
    if (normalized != null) {
      e = bansByIp.get(normalized);
      if (e != null) return e;
    }
    return null;
  }

  public Entry findMute(UUID uuid) {
    purgeExpired();
    return uuid == null ? null : mutesByUuid.get(uuid);
  }

  public Entry findBanByName(String name) {
    if (name == null || name.isBlank()) return null;
    purgeExpired();
    String lower = name.toLowerCase(Locale.ROOT);
    for (Entry e : bans()) {
      if (e.name() != null && e.name().toLowerCase(Locale.ROOT).equals(lower)) return e;
    }
    return null;
  }

  public Entry findMuteByName(String name) {
    if (name == null || name.isBlank()) return null;
    purgeExpired();
    String lower = name.toLowerCase(Locale.ROOT);
    for (Entry e : mutes()) {
      if (e.name() != null && e.name().toLowerCase(Locale.ROOT).equals(lower)) return e;
    }
    return null;
  }

  public List<Entry> bans() {
    purgeExpired();
    List<Entry> list = new ArrayList<>();
    list.addAll(bansByUuid.values());
    list.addAll(bansByIp.values());
    list.sort(Comparator.comparing(Entry::createdAt).reversed());
    return list;
  }

  public List<Entry> mutes() {
    purgeExpired();
    List<Entry> list = new ArrayList<>(mutesByUuid.values());
    list.sort(Comparator.comparing(Entry::createdAt).reversed());
    return list;
  }

  private void purgeExpired() {
    long now = now();
    bansByUuid.values().removeIf(e -> expired(e, now));
    bansByIp.values().removeIf(e -> expired(e, now));
    mutesByUuid.values().removeIf(e -> expired(e, now));
  }

  private boolean expired(Entry e, long now) {
    return e.expiresAt() > 0 && e.expiresAt() <= now;
  }

  private void load() throws IOException {
    bansByUuid.clear();
    bansByIp.clear();
    mutesByUuid.clear();
    if (!Files.exists(dataFile)) return;
    Object parsed;
    try {
      parsed = yaml.load(Files.readString(dataFile, StandardCharsets.UTF_8));
    } catch (RuntimeException ex) {
      log.warn("Failed to parse moderation data {}", dataFile.toString().replace('\\','/'), ex);
      return;
    }
    if (!(parsed instanceof Iterable<?> iterable)) return;
    for (Object obj : iterable) {
      if (!(obj instanceof Map<?,?> map)) continue;
      String typeRaw = Objects.toString(map.get("type"), "");
      Type type;
      try { type = Type.valueOf(typeRaw); } catch (Exception ex) { continue; }
      UUID uuid = parseUuid(map.get("uuid"));
      String ip = normalizeIp(Objects.toString(map.get("ip"), null));
      String name = Objects.toString(map.get("name"), null);
      String reason = Objects.toString(map.get("reason"), null);
      String actor = Objects.toString(map.get("actor"), null);
      long created = parseLong(map.get("createdAt"), now());
      long expires = parseLong(map.get("expiresAt"), 0);
      Entry e = new Entry(type, uuid, ip, name, reason, actor, created, expires);
      switch (type) {
        case BAN -> { if (uuid != null) bansByUuid.put(uuid, e); }
        case IPBAN -> { if (ip != null) bansByIp.put(ip, e); }
        case MUTE -> { if (uuid != null) mutesByUuid.put(uuid, e); }
      }
    }
    log.info("Loaded moderation entries: {} bans, {} ipbans, {} mutes",
        bansByUuid.size(), bansByIp.size(), mutesByUuid.size());
  }

  private void persist() throws IOException {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Entry e : bans()) rows.add(toMap(e));
    for (Entry e : mutes()) rows.add(toMap(e));
    String yamlStr = yaml.dump(rows);
    if (dataFile.getParent() != null) Files.createDirectories(dataFile.getParent());
    Files.writeString(dataFile, yamlStr, StandardCharsets.UTF_8);
  }

  private Map<String, Object> toMap(Entry e) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", e.type().name());
    if (e.uuid() != null) map.put("uuid", e.uuid().toString());
    if (e.ip() != null) map.put("ip", e.ip());
    if (e.name() != null) map.put("name", e.name());
    map.put("reason", e.reason());
    map.put("actor", e.actor());
    map.put("createdAt", e.createdAt());
    map.put("expiresAt", e.expiresAt());
    map.put("createdAtIso", Instant.ofEpochMilli(e.createdAt()).toString());
    if (e.expiresAt() > 0) map.put("expiresAtIso", Instant.ofEpochMilli(e.expiresAt()).toString());
    return map;
  }

  private UUID parseUuid(Object raw) {
    if (raw == null) return null;
    try { return UUID.fromString(raw.toString()); } catch (Exception ignored) { return null; }
  }

  private long parseLong(Object raw, long fallback) {
    if (raw == null) return fallback;
    if (raw instanceof Number n) return n.longValue();
    try { return Long.parseLong(raw.toString()); } catch (Exception ex) { return fallback; }
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

  private long now() { return System.currentTimeMillis(); }
}
