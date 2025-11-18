package com.example.soj.bans;

import com.example.soj.Config;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple persistent network-ban registry so the proxy can prevent banned players
 * from waking backend servers.
 */
public final class NetworkBanService {

  public record Entry(UUID uuid, String lastKnownName, String reason, String bannedBy, long bannedAt) {}

  private final Config.Banlist cfg;
  private final Logger log;
  private final Path dataFile;
  private final Yaml yaml = new Yaml();
  private final Map<UUID, Entry> bansByUuid = new ConcurrentHashMap<>();
  private final Map<String, Entry> bansByName = new ConcurrentHashMap<>();

  public NetworkBanService(Config.Banlist cfg, Logger log, Path dataDir) throws IOException {
    this.cfg = cfg == null ? new Config.Banlist() : cfg;
    this.log = log;
    String fileName = (this.cfg.dataFile == null || this.cfg.dataFile.isBlank())
        ? "network-bans.yml"
        : this.cfg.dataFile;
    this.dataFile = dataDir.resolve(fileName).normalize();
    loadExisting();
  }

  public boolean enabled() {
    return Boolean.TRUE.equals(cfg.enabled);
  }

  public Optional<Entry> lookup(UUID uuid, String username) {
    if (uuid != null) {
      Entry entry = bansByUuid.get(uuid);
      if (entry != null) return Optional.of(entry);
    }
    if (username != null && !username.isBlank()) {
      Entry entry = bansByName.get(username.toLowerCase(Locale.ROOT));
      if (entry != null) return Optional.of(entry);
    }
    return Optional.empty();
  }

  public List<Entry> entries() {
    return List.copyOf(bansByUuid.values());
  }

  public synchronized Entry ban(UUID uuid, String username, String reason, String bannedBy) throws IOException {
    if (uuid == null) throw new IllegalArgumentException("uuid required");
    String name = sanitize(username);
    String finalReason = sanitize(reason);
    Entry entry = new Entry(uuid, name, finalReason, sanitize(bannedBy), System.currentTimeMillis());

    Entry previous = bansByUuid.put(uuid, entry);
    if (previous != null && previous.lastKnownName() != null) {
      bansByName.remove(previous.lastKnownName().toLowerCase(Locale.ROOT));
    }
    if (name != null) {
      bansByName.put(name.toLowerCase(Locale.ROOT), entry);
    }

    persist();
    log.info("Network ban added for {} ({}) reason: {}", name, uuid, finalReason);
    return entry;
  }

  public synchronized boolean unban(UUID uuid, String username) throws IOException {
    boolean changed = false;
    if (uuid != null) {
      Entry removed = bansByUuid.remove(uuid);
      if (removed != null) {
        changed = true;
        if (removed.lastKnownName() != null) {
          bansByName.remove(removed.lastKnownName().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (!changed && username != null && !username.isBlank()) {
      Entry removed = bansByName.remove(username.toLowerCase(Locale.ROOT));
      if (removed != null) {
        bansByUuid.remove(removed.uuid());
        changed = true;
      }
    }
    if (changed) {
      persist();
      log.info("Network ban removed for {}", username != null ? username : uuid);
    }
    return changed;
  }

  private void loadExisting() throws IOException {
    if (!Files.exists(dataFile)) return;
    String raw = Files.readString(dataFile, StandardCharsets.UTF_8);
    Object parsed = yaml.load(raw);
    if (!(parsed instanceof Iterable<?> iterable)) return;
    int count = 0;
    for (Object row : iterable) {
      if (!(row instanceof Map<?, ?> map)) continue;
      UUID uuid = parseUuid(map.get("uuid"));
      if (uuid == null) continue;
      String name = sanitize(map.get("name"));
      String reason = sanitize(map.get("reason"));
      String bannedBy = sanitize(map.get("bannedBy"));
      long bannedAt = parseLong(map.get("bannedAt"), System.currentTimeMillis());
      Entry entry = new Entry(uuid, name, reason, bannedBy, bannedAt);
      bansByUuid.put(uuid, entry);
      if (name != null) {
        bansByName.put(name.toLowerCase(Locale.ROOT), entry);
      }
      count++;
    }
    log.info("Loaded {} network bans from {}", count, dataFile.toString().replace('\\','/'));
  }

  private void persist() throws IOException {
    Files.createDirectories(dataFile.getParent());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Entry entry : bansByUuid.values()) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("uuid", entry.uuid().toString());
      map.put("name", entry.lastKnownName());
      map.put("reason", entry.reason());
      map.put("bannedBy", entry.bannedBy());
      map.put("bannedAt", entry.bannedAt());
      map.put("bannedAtIso", Instant.ofEpochMilli(entry.bannedAt()).toString());
      rows.add(map);
    }
    StringWriter sw = new StringWriter();
    yaml.dump(rows, sw);
    byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
    Path tmp = dataFile.resolveSibling(dataFile.getFileName().toString() + ".tmp");
    Files.write(tmp, bytes);
    Files.move(tmp, dataFile,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  private UUID parseUuid(Object raw) {
    if (raw == null) return null;
    try {
      return UUID.fromString(raw.toString());
    } catch (Exception ignored) {
      return null;
    }
  }

  private long parseLong(Object raw, long fallback) {
    if (raw == null) return fallback;
    if (raw instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(raw.toString());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private String sanitize(Object raw) {
    if (raw == null) return null;
    String s = raw.toString().trim();
    return s.isEmpty() ? null : s;
  }
}
