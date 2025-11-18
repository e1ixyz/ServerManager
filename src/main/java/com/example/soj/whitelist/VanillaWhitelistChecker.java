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
  public record VanillaEntry(UUID uuid, String name) {}

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

  public Set<String> trackedServers() {
    return Set.copyOf(serverFiles.keySet());
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
      if (ensureEntry(path, uuid, name)) {
        cache.remove(path);
      }
    } catch (IOException ex) {
      log.warn("Failed to update vanilla whitelist {}", path.toString().replace('\\','/'), ex);
    }
  }

  public synchronized boolean addEntry(String server, UUID uuid, String username) throws IOException {
    Path path = serverFiles.get(server);
    if (path == null) throw new IllegalArgumentException("Unknown server: " + server);
    boolean changed = ensureEntry(path, uuid, username == null ? "" : username);
    if (changed) cache.remove(path);
    return changed;
  }

  public synchronized boolean removeEntry(String server, UUID uuid, String username) throws IOException {
    Path path = serverFiles.get(server);
    if (path == null) throw new IllegalArgumentException("Unknown server: " + server);
    boolean changed = removeEntry(path, uuid, username);
    if (changed) cache.remove(path);
    return changed;
  }

  public List<VanillaEntry> listEntries(String server) throws IOException {
    Path path = serverFiles.get(server);
    if (path == null) throw new IllegalArgumentException("Unknown server: " + server);
    return List.copyOf(readEntries(path));
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
      for (VanillaEntry entry : readEntries(parsed)) {
        if (entry.uuid() != null) uuids.add(entry.uuid());
        else if (entry.name() != null) namesWithoutUuid.add(entry.name().toLowerCase(Locale.ROOT));
      }
      return new Cache(lastModified, uuids, namesWithoutUuid);
    } catch (IOException ex) {
      log.warn("Failed to read vanilla whitelist {}", file.toString().replace('\\','/'), ex);
      return existing;
    }
  }

  private boolean ensureEntry(Path file, UUID uuid, String username) throws IOException {
    List<VanillaEntry> entries = new ArrayList<>(readEntries(file));
    boolean changed = false;
    String normalizedName = username == null ? "" : username;
    boolean exists;
    if (uuid != null) {
      exists = entries.stream().anyMatch(e -> uuid.equals(e.uuid()));
    } else {
      exists = !normalizedName.isBlank() && entries.stream().anyMatch(e -> e.name() != null && e.name().equalsIgnoreCase(normalizedName));
    }
    if (!exists) {
      String storedName = normalizedName.isBlank() ? null : normalizedName;
      entries.add(new VanillaEntry(uuid, storedName));
      changed = true;
    } else if (uuid != null && !normalizedName.isBlank()) {
      boolean updated = false;
      for (int i = 0; i < entries.size(); i++) {
        VanillaEntry e = entries.get(i);
        if (uuid.equals(e.uuid()) && !Objects.equals(e.name(), username)) {
          entries.set(i, new VanillaEntry(uuid, normalizedName));
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

  private boolean removeEntry(Path file, UUID uuid, String username) throws IOException {
    List<VanillaEntry> entries = new ArrayList<>(readEntries(file));
    boolean changed = entries.removeIf(e -> {
      if (uuid != null && uuid.equals(e.uuid())) return true;
      if (username != null && !username.isBlank() && e.name() != null) {
        return e.name().equalsIgnoreCase(username);
      }
      return false;
    });
    if (changed) {
      writeEntries(file, entries);
    }
    return changed;
  }

  private List<VanillaEntry> readEntries(Path file) throws IOException {
    List<VanillaEntry> entries = new ArrayList<>();
    if (!Files.exists(file)) {
      return entries;
    }
    String raw = Files.readString(file, StandardCharsets.UTF_8);
    Object parsed = yaml.load(raw);
    entries.addAll(readEntries(parsed));
    return entries;
  }

  private List<VanillaEntry> readEntries(Object parsed) {
    List<VanillaEntry> entries = new ArrayList<>();
    if (parsed instanceof Iterable<?> iterable) {
      for (Object obj : iterable) {
        if (!(obj instanceof Map<?, ?> map)) continue;
        UUID uuid = parseUuid(map.get("uuid"));
        String name = stringOrNull(map.get("name"));
        entries.add(new VanillaEntry(uuid, name));
      }
    }
    return entries;
  }

  private void writeEntries(Path file, List<VanillaEntry> entries) throws IOException {
    Files.createDirectories(file.getParent());
    StringBuilder sb = new StringBuilder();
    sb.append("[\n");
    for (int i = 0; i < entries.size(); i++) {
      VanillaEntry e = entries.get(i);
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
