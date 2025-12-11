package com.example.soj.admin;

import com.example.soj.Config;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple TOTP-based admin authentication for the web panel. Stores shared secrets per MC username.
 */
public final class AdminAuthService {

  private final Config.Admin cfg;
  private final Path dataFile;
  private final Logger log;
  private final SecureRandom rng = new SecureRandom();
  private final Yaml yaml = new Yaml();
  private final Map<String, String> secrets = new ConcurrentHashMap<>();

  public AdminAuthService(Config.Admin cfg, Path dataDir, Logger log) throws IOException {
    this.cfg = cfg;
    this.log = log;
    this.dataFile = dataDir.resolve(cfg.authFile == null || cfg.authFile.isBlank()
        ? "admin-auth.yml"
        : cfg.authFile).normalize();
    load();
  }

  /** Generates or resets an admin token for the given MC username. */
  public synchronized Provisioning provision(String mcUser) throws IOException {
    String normalized = normalize(mcUser);
    if (normalized == null) throw new IllegalArgumentException("Username required");
    String secret = generateSecret();
    secrets.put(normalized, secret);
    persist();
    return new Provisioning(secret);
  }

  public boolean verify(String mcUser, String code) {
    String normalized = normalize(mcUser);
    if (normalized == null) return false;
    String secret = secrets.get(normalized);
    if (secret == null || code == null) return false;
    String trimmed = code.trim();
    return !trimmed.isEmpty() && secret.equals(trimmed);
  }

  private String generateSecret() {
    byte[] bytes = new byte[20];
    rng.nextBytes(bytes);
    return base32Encode(bytes);
  }

  private void load() throws IOException {
    secrets.clear();
    if (!Files.exists(dataFile)) return;
    Object parsed = yaml.load(Files.readString(dataFile, StandardCharsets.UTF_8));
    if (!(parsed instanceof Map<?,?> map)) return;
    for (var e : map.entrySet()) {
      if (e.getKey() == null || e.getValue() == null) continue;
      secrets.put(normalize(e.getKey().toString()), e.getValue().toString());
    }
    log.info("Loaded {} admin auth entries from {}", secrets.size(), dataFile.toString().replace('\\','/'));
  }

  private void persist() throws IOException {
    if (dataFile.getParent() != null) Files.createDirectories(dataFile.getParent());
    Map<String, String> out = new HashMap<>(secrets);
    String yamlStr = yaml.dump(out);
    Files.writeString(dataFile, yamlStr, StandardCharsets.UTF_8);
  }

  private String normalize(String user) {
    if (user == null) return null;
    String s = user.trim();
    return s.isEmpty() ? null : s;
  }

  private String urlEncode(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  public record Provisioning(String secret) {}

  // ---- minimal Base32 (RFC 4648) helpers ----
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private String base32Encode(byte[] data) {
    StringBuilder sb = new StringBuilder();
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        int index = (buffer >> (bitsLeft - 5)) & 0x1F;
        bitsLeft -= 5;
        sb.append(ALPHABET.charAt(index));
      }
    }
    if (bitsLeft > 0) {
      int index = (buffer << (5 - bitsLeft)) & 0x1F;
      sb.append(ALPHABET.charAt(index));
    }
    return sb.toString();
  }

}
