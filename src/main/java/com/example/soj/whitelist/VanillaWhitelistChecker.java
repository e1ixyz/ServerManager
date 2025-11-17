package com.example.soj.whitelist;

import com.example.soj.Config;
import com.example.soj.ServerConfig;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads vanilla whitelist.json files from managed backends. The primary server
 * can be mirrored into the network whitelist, while auxiliary servers are
 * queried to enforce their own access lists.
 */
public final class VanillaWhitelistChecker {

  private static final String VANILLA_FILE = "whitelist.json";

  private final Logger log;
  private final Yaml yaml = new Yaml();
  private final Map<String, Path> serverFiles = new HashMap<>();
  private final Map<Path, Cache> cache = new ConcurrentHashMap<>();
  private final String primaryName;

  private record Cache(long lastModified, Set<UUID> uuids, Set<String> namesWithoutUuid) {}
  private record Entry(UUID uuid, String name) {}

  public VanillaWhitelistChecker(Config cfg, Logger log) {
    this.log = log;
    this.primaryName = cfg.primaryServerName();

    for (var entry : cfg.servers.entrySet()) {
      ServerConfig sc = entry.getValue();
      if (sc.workingDir == null || sc.workingDir.isBlank()) continue;
      Path file = Paths.get(sc.workingDir)
          .resolve(VANILLA_FILE)
          .toAbsolutePath()
          .normalize();
      serverFiles.put(entry.getKey(), file);
    }

    Path primaryPath = primaryPath();
    if (primaryPath != null) {
      log.info("Primary whitelist file: {}", primaryPath.toString().replace('\\','/'));
    }
  }

  public boolean hasTargets() {
    return !serverFiles.isEmpty();
  }

  public boolean tracksServer(String server) {
    return serverFiles.containsKey(server);
  }

  /** Networks bypass uses the primary backend only. */
  public boolean isWhitelisted(UUID uuid, String username) {
    Path path = primaryPath();
    if (path == null) return false;
    return isWhitelisted(path, uuid, username);
  }

  /** Checks an arbitrary backend's whitelist; treats missing files as open. */
  public boolean isWhitelisted(String server, UUID uuid, String username) {
    Path path = serverFiles.get(server);
    if (path == null) return true; // no whitelist file tracked -> allow
    return isWhitelisted(path, uuid, username);
  }

  /** Mirrors the network whitelist into the primary backend. */
  public void ensureWhitelisted(UUID uuid, String username) {
    ensureWhitelisted(this.primaryName, uuid, username);
  }

  /** Mirrors the network whitelist into a specific backend. */
  public void ensureWhitelisted(String server, UUID uuid, String username) {
    if (uuid == null || server == null) return;
    Path path = serverFiles.get(server);
    if (path == null) return;
    String name = username == null ? "" : username.trim();
    try {
      if (updateFile(path, uuid, name)) {
        cache.remove(path);
      }
    } catch (IOException ex) {
      log.warn("Failed to update vanilla whitelist {}", path.toString().replace('\\','/'), ex);
    }
  }

  private Path primaryPath() {
    if (primaryName == null) return null;
    return serverFiles.get(primaryName);
  }

  private boolean isWhitelisted(Path file, UUID uuid, String username) {
    Cache c = cache.compute(file, (p, existing) -> loadIfNeeded(p, existing));
    if (c == null) return false;
    if (uuid != null && c.uuids.contains(uuid)) return true;
    if (username != null && !username.isBlank()) {
      if (c.namesWithoutUuid.contains(username.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private Cache loadIfNeeded(Path file, Cache existing) {
    try {
      if (!Files.exists(file)) return existing;
      FileTime ft = Files.getLastModifiedTime(file);
      long lastModified = ft.toMillis();
      if (existing != null && existing.lastModified == lastModified) {
        return existing;
      }
      String raw = Files.readString(file, StandardCharsets.UTF_8);
      Object parsed = yaml.load(raw);
      Set<UUID> uuids = new HashSet<>();
      Set<String> namesWithoutUuid = new HashSet<>();
      for (Entry entry : readEntries(parsed)) {
        if (entry.uuid() != null) uuids.add(entry.uuid());
        else if (entry.name() != null) namesWithoutUuid.add(entry.name().toLowerCase(Locale.ROOT));
      }
      return new Cache(lastModified, uuids, namesWithoutUuid);
    } catch (IOException ex) {
      log.warn("Failed to read vanilla whitelist {}", file.toString().replace('\\','/'), ex);
      return existing;
    }
  }

  private boolean updateFile(Path file, UUID uuid, String username) throws IOException {
    List<Entry> entries = new ArrayList<>(readEntries(file));
    boolean changed = false;
    boolean exists = entries.stream().anyMatch(e -> uuid.equals(e.uuid()));
    if (!exists) {
      entries.add(new Entry(uuid, username.isBlank() ? null : username));
      changed = true;
    } else if (username != null && !username.isBlank()) {
      boolean updated = false;
      for (int i = 0; i < entries.size(); i++) {
        Entry e = entries.get(i);
        if (uuid.equals(e.uuid()) && !Objects.equals(e.name(), username)) {
          entries.set(i, new Entry(uuid, username));
          updated = true;
        }
      }
      changed = updated;
    }
    if (changed) {
      writeEntries(file, entries);
    }
    return changed;
  }

  private List<Entry> readEntries(Path file) throws IOException {
    List<Entry> entries = new ArrayList<>();
    if (!Files.exists(file)) {
      return entries;
    }
    String raw = Files.readString(file, StandardCharsets.UTF_8);
    Object parsed = yaml.load(raw);
    entries.addAll(readEntries(parsed));
    return entries;
  }

  private List<Entry> readEntries(Object parsed) {
    List<Entry> entries = new ArrayList<>();
    if (parsed instanceof Iterable<?> iterable) {
      for (Object obj : iterable) {
        if (!(obj instanceof Map<?, ?> map)) continue;
        UUID uuid = parseUuid(map.get("uuid"));
        String name = stringOrNull(map.get("name"));
        entries.add(new Entry(uuid, name));
      }
    }
    return entries;
  }

  private void writeEntries(Path file, List<Entry> entries) throws IOException {
    Files.createDirectories(file.getParent());
    StringBuilder sb = new StringBuilder();
    sb.append("[\n");
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      sb.append("  {");
      if (e.uuid() != null) {
        sb.append("\"uuid\": \"").append(e.uuid()).append('\"');
      } else {
        sb.append("\"uuid\": null");
      }
      sb.append(", \"name\": ");
      if (e.name() != null) {
        sb.append('\"').append(escapeJson(e.name())).append('\"');
      } else {
        sb.append("null");
      }
      sb.append('}');
      if (i + 1 < entries.size()) sb.append(',');
      sb.append('\n');
    }
    sb.append("]\n");
    Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
  }

  private UUID parseUuid(Object raw) {
    if (raw == null) return null;
    try {
      return UUID.fromString(raw.toString());
    } catch (Exception ignored) {
      return null;
    }
  }

  private String stringOrNull(Object raw) {
    if (raw == null) return null;
    String s = raw.toString();
    return s.isBlank() ? null : s;
  }

  private String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
