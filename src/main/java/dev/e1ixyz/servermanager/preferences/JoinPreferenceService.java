package dev.e1ixyz.servermanager.preferences;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores each player's preferred managed backend for initial join routing.
 */
public final class JoinPreferenceService {
  public record Entry(UUID uuid, String server, long updatedAt) {}

  private final Logger log;
  private final Path dataFile;
  private final Yaml yaml = new Yaml();
  private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

  public JoinPreferenceService(Logger log, Path dataDir) throws IOException {
    this.log = log;
    this.dataFile = dataDir.resolve("join-preferences.yml").normalize();
    loadExisting();
  }

  public String preferredServer(UUID uuid) {
    if (uuid == null) return null;
    Entry entry = entries.get(uuid);
    if (entry == null) return null;
    return sanitizeServer(entry.server());
  }

  public synchronized String setPreferredServer(UUID uuid, String server) throws IOException {
    String normalizedServer = sanitizeServer(server);
    if (uuid == null || normalizedServer == null) return null;
    Entry entry = new Entry(uuid, normalizedServer, System.currentTimeMillis());
    entries.put(uuid, entry);
    persist();
    return normalizedServer;
  }

  public synchronized boolean clearPreferredServer(UUID uuid) throws IOException {
    if (uuid == null) return false;
    boolean removed = entries.remove(uuid) != null;
    if (removed) {
      persist();
    }
    return removed;
  }

  private void loadExisting() throws IOException {
    if (!Files.exists(dataFile)) return;
    String raw = Files.readString(dataFile, StandardCharsets.UTF_8);
    Object parsed;
    try {
      parsed = yaml.load(raw);
    } catch (RuntimeException ex) {
      log.warn("Failed to parse join preferences {}", dataFile.toString().replace('\\', '/'), ex);
      return;
    }
    if (!(parsed instanceof Iterable<?> iterable)) return;

    int loaded = 0;
    for (Object obj : iterable) {
      if (!(obj instanceof Map<?, ?> map)) continue;
      UUID uuid = parseUuid(map.get("uuid"));
      String server = sanitizeServer(value(map.get("server")));
      if (uuid == null || server == null) continue;
      long updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());
      entries.put(uuid, new Entry(uuid, server, updatedAt));
      loaded++;
    }
    log.info("Loaded {} join preference entries from {}", loaded, dataFile.toString().replace('\\', '/'));
  }

  private void persist() throws IOException {
    if (dataFile.getParent() != null) {
      Files.createDirectories(dataFile.getParent());
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    entries.values().stream()
        .sorted(Comparator.comparing(entry -> entry.uuid().toString()))
        .forEach(entry -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("uuid", entry.uuid().toString());
          row.put("server", entry.server());
          row.put("updatedAt", entry.updatedAt());
          row.put("updatedAtIso", Instant.ofEpochMilli(entry.updatedAt()).toString());
          rows.add(row);
        });

    StringWriter sw = new StringWriter();
    yaml.dump(rows, sw);
    byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);

    Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
    Files.write(tmp, bytes);
    Files.move(tmp, dataFile,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  private static String sanitizeServer(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String value(Object raw) {
    return raw == null ? null : raw.toString();
  }

  private static UUID parseUuid(Object raw) {
    if (raw == null) return null;
    try {
      return UUID.fromString(raw.toString());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static long parseLong(Object raw, long fallback) {
    if (raw == null) return fallback;
    if (raw instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(raw.toString());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
