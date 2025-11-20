package com.example.soj.whitelist;

import com.example.soj.Config;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory network whitelist backed by a simple YAML file plus
 * short-lived verification codes to allow self-service onboarding.
 */
public final class WhitelistService {

  public record PendingCode(String code, UUID uuid, String username, long expiresAt) {
    boolean expired() { return System.currentTimeMillis() > expiresAt; }
  }

  public record Entry(UUID uuid, String lastKnownName, long addedAt) {}

  private final Config.Whitelist cfg;
  private final Logger log;
  private final Path dataFile;
  private final SecureRandom rng = new SecureRandom();
  private final Yaml yaml = new Yaml();

  private final Map<UUID, Entry> entriesByUuid = new ConcurrentHashMap<>();
  private final Map<String, Entry> entriesByName = new ConcurrentHashMap<>();

  private final Map<String, PendingCode> codesByCode = new ConcurrentHashMap<>();
  private final Map<UUID, PendingCode> codesByPlayer = new ConcurrentHashMap<>();

  public WhitelistService(Config.Whitelist cfg, Logger log, Path dataDir) throws IOException {
    this.cfg = cfg;
    this.log = log;
    this.dataFile = dataDir.resolve(cfg.dataFile == null || cfg.dataFile.isBlank()
        ? "network-whitelist.yml"
        : cfg.dataFile).normalize();
    loadExisting();
  }

  public boolean enabled() {
    return Boolean.TRUE.equals(cfg.enabled);
  }

  public Config.Whitelist config() {
    return cfg;
  }

  public Optional<Entry> lookup(UUID uuid, String username) {
    UUID id = uuid;
    String normalizedName = normalize(username);
    if (id != null) {
      Entry byUuid = entriesByUuid.get(id);
      if (byUuid != null) return Optional.of(byUuid);
    }
    if (normalizedName != null) {
      Entry byName = entriesByName.get(normalizedName);
      if (byName != null) return Optional.of(byName);
    }
    return Optional.empty();
  }

  public boolean isWhitelisted(UUID uuid, String username) {
    return lookup(uuid, username).isPresent();
  }

  public synchronized Entry add(UUID uuid, String username) throws IOException {
    long now = System.currentTimeMillis();
    Entry entry = new Entry(uuid, username, now);
    Entry previous = entriesByUuid.put(uuid, entry);
    if (previous != null && previous.lastKnownName() != null && !previous.lastKnownName().isBlank()) {
      entriesByName.remove(previous.lastKnownName().toLowerCase(Locale.ROOT));
    }
    if (username != null && !username.isBlank()) {
      entriesByName.put(username.toLowerCase(Locale.ROOT), entry);
    }
    persist();
    log.info("Whitelist entry added for {} ({})", username, uuid);
    return entry;
  }

  public synchronized boolean remove(UUID uuid, String username) throws IOException {
    boolean changed = false;
    if (uuid != null) {
      Entry removed = entriesByUuid.remove(uuid);
      if (removed != null) {
        changed = true;
        if (removed.lastKnownName() != null && !removed.lastKnownName().isBlank()) {
          entriesByName.remove(removed.lastKnownName().toLowerCase(Locale.ROOT));
        }
      }
    }
    String normalizedName = normalize(username);
    if (!changed && normalizedName != null) {
      Entry removed = entriesByName.remove(normalizedName);
      if (removed != null) {
        entriesByUuid.remove(removed.uuid());
        changed = true;
      }
    }
    if (changed) {
      persist();
      log.info("Whitelist entry removed for {}", username != null ? username : uuid);
    }
    return changed;
  }

  public PendingCode issueCode(UUID uuid, String username) {
    purgeExpiredCodes();
    PendingCode existing = codesByPlayer.get(uuid);
    if (existing != null && !existing.expired()) {
      return existing;
    }

    String code;
    int attempts = 0;
    do {
      code = generateCode(Math.max(4, cfg.codeLength));
      attempts++;
      if (attempts > 1000) {
        throw new IllegalStateException("Could not generate unique whitelist code");
      }
    } while (codesByCode.containsKey(code));
    long ttlMillis = Math.max(60_000L, cfg.codeTtlSeconds * 1000L);
    PendingCode pc = new PendingCode(code, uuid, username, System.currentTimeMillis() + ttlMillis);
    codesByCode.put(code, pc);
    codesByPlayer.put(uuid, pc);
    log.info("Issued whitelist code {} for {}", code, username);
    return pc;
  }

  public synchronized boolean redeem(String code, String providedUsername) throws IOException {
    if (code == null || code.isBlank()) return false;
    purgeExpiredCodes();

    PendingCode pc = codesByCode.remove(code.trim());
    if (pc == null || pc.expired()) {
      if (pc != null) codesByPlayer.remove(pc.uuid());
      return false;
    }
    codesByPlayer.remove(pc.uuid());

    String provided = providedUsername == null ? "" : providedUsername.trim();
    String original = pc.username() == null ? "" : pc.username().trim();

    if (!provided.isBlank() && !original.isBlank()
        && !provided.equalsIgnoreCase(original)) {
      return false;
    }

    String finalName = !provided.isBlank() ? provided : original;
    add(pc.uuid(), finalName);
    return true;
  }

  public String kickMessage(String url, String code) {
    String raw = cfg.kickMessage == null ? "" : cfg.kickMessage;
    if ("You are not whitelisted. Visit <url> and enter code <code>.".equals(raw)) {
      raw = "You are not whitelisted. Visit <url> and enter your username and code <code>.";
    }
    return raw.replace("<url>", Objects.toString(url, ""))
        .replace("<code>", Objects.toString(code, ""));
  }

  public List<Entry> snapshot() {
    return List.copyOf(entriesByUuid.values());
  }

  public void purgeExpiredCodes() {
    long now = System.currentTimeMillis();
    for (var e : new ArrayList<>(codesByCode.entrySet())) {
      if (e.getValue().expiresAt() <= now) {
        codesByCode.remove(e.getKey());
        codesByPlayer.remove(e.getValue().uuid());
      }
    }
  }

  private void loadExisting() throws IOException {
    if (!Files.exists(dataFile)) return;
    String raw = Files.readString(dataFile, StandardCharsets.UTF_8);
    Object parsed = yaml.load(raw);
    if (!(parsed instanceof Iterable<?> iterable)) return;
    int count = 0;
    for (Object obj : iterable) {
      if (!(obj instanceof Map<?, ?> map)) continue;
      UUID uuid = parseUuid(map.get("uuid"));
      if (uuid == null) continue;
      String name = Objects.toString(map.get("name"), null);
      long added = parseLong(map.get("addedAt"), System.currentTimeMillis());
      Entry entry = new Entry(uuid, name, added);
      entriesByUuid.put(uuid, entry);
      if (name != null && !name.isBlank()) {
        entriesByName.put(name.toLowerCase(Locale.ROOT), entry);
      }
      count++;
    }
    log.info("Loaded {} whitelist entries from {}", count, dataFile.toString().replace('\\','/'));
  }

  private void persist() throws IOException {
    Files.createDirectories(dataFile.getParent());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Entry entry : entriesByUuid.values()) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("uuid", entry.uuid().toString());
      map.put("name", entry.lastKnownName());
      map.put("addedAt", entry.addedAt());
      map.put("addedAtIso", Instant.ofEpochMilli(entry.addedAt()).toString());
      rows.add(map);
    }
    StringWriter sw = new StringWriter();
    yaml.dump(rows, sw);
    byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
    Path tmp = dataFile.resolveSibling(dataFile.getFileName().toString() + ".tmp");
    Files.write(tmp, bytes);
    Files.move(tmp, dataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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

  private String generateCode(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(rng.nextInt(10));
    }
    return sb.toString();
  }

  private String normalize(String name) {
    if (name == null) return null;
    String trimmed = name.trim();
    if (trimmed.isEmpty()) return null;
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
