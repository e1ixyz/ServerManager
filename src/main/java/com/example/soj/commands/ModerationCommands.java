package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.moderation.ModerationService;
import com.example.soj.whitelist.WhitelistService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone moderation commands: ban, ipban, unban, mute, unmute, warn and list views.
 */
public final class ModerationCommands implements SimpleCommand {
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final Pattern DURATION_TOKEN = Pattern.compile("(?i)(\\d+)([dhms]?)");
  private static final List<String> FOREVER = List.of("forever", "permanent", "perm", "infinite", "indefinite");
  private final ProxyServer proxy;
  private volatile Config cfg;
  private volatile ModerationService moderation;
  private volatile WhitelistService whitelist;
  private final Logger log;

  public ModerationCommands(ProxyServer proxy, Config cfg, ModerationService moderation, WhitelistService whitelist, Logger log) {
    this.proxy = proxy;
    this.cfg = cfg;
    this.moderation = moderation;
    this.whitelist = whitelist;
    this.log = log;
  }

  public void updateState(Config cfg, ModerationService moderation, WhitelistService whitelist) {
    this.cfg = cfg;
    this.moderation = moderation;
    this.whitelist = whitelist;
  }

  @Override
  public void execute(Invocation inv) {
    CommandSource src = inv.source();
    String alias = inv.alias().toLowerCase(Locale.ROOT);
    String[] args = inv.arguments();

    if (moderation == null || !moderation.enabled()) {
      src.sendMessage(Component.text("Moderation is disabled in config."));
      return;
    }

    switch (alias) {
      case "ban" -> handleBan(src, args);
      case "ipban" -> handleIpBan(src, args);
      case "unban" -> handleUnban(src, args);
      case "mute" -> handleMute(src, args);
      case "unmute" -> handleUnmute(src, args);
      case "warn" -> handleWarn(src, args);
      case "banlist" -> handleList(src, moderation.bans(), "Bans");
      case "mutelist" -> handleList(src, moderation.mutes(), "Mutes");
      default -> src.sendMessage(Component.text("Unknown moderation command."));
    }
  }

  @Override
  public List<String> suggest(Invocation inv) {
    String alias = inv.alias().toLowerCase(Locale.ROOT);
    String[] args = inv.arguments();
    if (!moderation.enabled()) return List.of();

    return switch (alias) {
      case "ban", "ipban", "mute", "unmute", "warn", "unban" -> suggestPlayers(args);
      case "banlist", "mutelist" -> List.of();
      default -> List.of();
    };
  }

  private void handleBan(CommandSource src, String[] args) {
    if (!has(src, "servermanager.moderation.ban")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(Component.text("Usage: /ban <player> [duration] [reason]"));
      return;
    }
    Target target = resolveTarget(args[0]);
    if (target.uuid == null) {
      src.sendMessage(Component.text("Player must be online or a valid UUID."));
      return;
    }
    ParsedAction parsed = parseDurationAndReason(args, 1, "Banned by " + nameOf(src));
    if (parsed.invalid) {
      src.sendMessage(Component.text("Invalid duration. Use values like 30m, 2h, 1d, or forever."));
      return;
    }
    long expiresAt = parsed.durationSeconds <= 0 ? 0 : System.currentTimeMillis() + parsed.durationSeconds * 1000L;
    try {
      moderation.banUuid(target.uuid, target.name(), parsed.reason, nameOf(src), expiresAt);
    } catch (IOException ex) {
      log.error("Failed to ban player", ex);
      src.sendMessage(Component.text("Failed to update ban list: " + ex.getMessage()));
      return;
    }
    proxy.getPlayer(target.uuid).ifPresent(p -> p.disconnect(
        moderationMessage(cfg.messages.bannedMessage, p.getUsername(), parsed.reason, expiresAt)));
    src.sendMessage(Component.text("Banned " + target.display() + suffix(expiresAt) + reasonSuffix(parsed.reason)));
  }

  private void handleIpBan(CommandSource src, String[] args) {
    if (!has(src, "servermanager.moderation.ipban")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(Component.text("Usage: /ipban <player|ip> [duration] [reason]"));
      return;
    }
    Target target = resolveTarget(args[0]);
    String ip = target.ip;
    if (ip == null && looksLikeIp(args[0])) ip = args[0];
    if (ip == null) {
      src.sendMessage(Component.text("Provide an IP or an online player."));
      return;
    }
    ParsedAction parsed = parseDurationAndReason(args, 1, "IP banned by " + nameOf(src));
    if (parsed.invalid) {
      src.sendMessage(Component.text("Invalid duration. Use values like 30m, 2h, 1d, or forever."));
      return;
    }
    long expiresAt = parsed.durationSeconds <= 0 ? 0 : System.currentTimeMillis() + parsed.durationSeconds * 1000L;
    try {
      moderation.banIp(ip, parsed.reason, nameOf(src), expiresAt);
    } catch (IOException ex) {
      log.error("Failed to ip-ban", ex);
      src.sendMessage(Component.text("Failed to update ban list: " + ex.getMessage()));
      return;
    }
    if (target.uuid != null) {
      proxy.getPlayer(target.uuid).ifPresent(p -> p.disconnect(
          moderationMessage(cfg.messages.bannedMessage, p.getUsername(), parsed.reason, expiresAt)));
    }
    src.sendMessage(Component.text("IP banned " + ip + suffix(expiresAt) + reasonSuffix(parsed.reason)));
  }

  private void handleUnban(CommandSource src, String[] args) {
    if (!has(src, "servermanager.moderation.unban")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(Component.text("Usage: /unban <player|ip>"));
      return;
    }
    Target target = resolveTarget(args[0]);
    String ip = target.ip;
    if (ip == null && looksLikeIp(args[0])) ip = args[0];
    UUID uuid = target.uuid != null ? target.uuid : parseUuid(args[0]);
    if (uuid == null && ip == null) {
      src.sendMessage(Component.text("Provide a player UUID/name or an IP to unban."));
      return;
    }
    try {
      moderation.unban(uuid, ip);
    } catch (IOException ex) {
      log.error("Failed to unban", ex);
      src.sendMessage(Component.text("Failed to update ban list: " + ex.getMessage()));
      return;
    }
    src.sendMessage(Component.text("Unbanned " + (ip != null ? ip : target.display())));
  }

  private void handleMute(CommandSource src, String[] args) {
    if (!has(src, "servermanager.moderation.mute")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(Component.text("Usage: /mute <player> [duration] [reason]"));
      return;
    }
    Target target = resolveTarget(args[0]);
    if (target.uuid == null) {
      src.sendMessage(Component.text("Player must be online or a valid UUID."));
      return;
    }
    ParsedAction parsed = parseDurationAndReason(args, 1, "Muted by " + nameOf(src));
    if (parsed.invalid) {
      src.sendMessage(Component.text("Invalid duration. Use values like 30m, 2h, 1d, or forever."));
      return;
    }
    long expiresAt = parsed.durationSeconds <= 0 ? 0 : System.currentTimeMillis() + parsed.durationSeconds * 1000L;
    try {
      moderation.mute(target.uuid, target.name(), parsed.reason, nameOf(src), expiresAt);
    } catch (IOException ex) {
      log.error("Failed to mute", ex);
      src.sendMessage(Component.text("Failed to update mute list: " + ex.getMessage()));
      return;
    }
    proxy.getPlayer(target.uuid).ifPresent(p -> p.sendMessage(
        moderationMessage(cfg.messages.mutedMessage, p.getUsername(), parsed.reason, expiresAt)));
    src.sendMessage(Component.text("Muted " + target.display() + suffix(expiresAt) + reasonSuffix(parsed.reason)));
  }

  private void handleUnmute(CommandSource src, String[] args) {
    if (!has(src, "servermanager.moderation.unmute")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(Component.text("Usage: /unmute <player>"));
      return;
    }
    Target target = resolveTarget(args[0]);
    if (target.uuid == null) {
      src.sendMessage(Component.text("Player must be online or a valid UUID."));
      return;
    }
    try {
      moderation.unmute(target.uuid);
    } catch (IOException ex) {
      log.error("Failed to unmute", ex);
      src.sendMessage(Component.text("Failed to update mute list: " + ex.getMessage()));
      return;
    }
    src.sendMessage(Component.text("Unmuted " + target.display()));
  }

  private void handleWarn(CommandSource src, String[] args) {
    if (!has(src, "servermanager.moderation.warn")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(Component.text("Usage: /warn <player> [reason]"));
      return;
    }
    Target target = resolveTarget(args[0]);
    if (target.uuid == null) {
      src.sendMessage(Component.text("Player must be online or a valid UUID."));
      return;
    }
    String reason = args.length >= 2 ? joinArgs(args, 1) : "Warned by " + nameOf(src);
    proxy.getPlayer(target.uuid).ifPresent(p -> p.sendMessage(
        moderationMessage(cfg.messages.warnMessage, p.getUsername(), reason, 0)));
    src.sendMessage(Component.text("Warned " + target.display() + reasonSuffix(reason)));
  }

  private void handleList(CommandSource src, List<ModerationService.Entry> entries, String title) {
    if (!has(src, "servermanager.moderation.view", "servermanager.moderation.ban")) {
      src.sendMessage(Component.text("You don't have permission."));
      return;
    }
    src.sendMessage(Component.text(title + " (" + entries.size() + "):"));
    int limit = Math.min(entries.size(), 20);
    for (int i = 0; i < limit; i++) {
      ModerationService.Entry e = entries.get(i);
      String who = displayName(e);
      String reason = e.reason() == null ? "" : " [" + e.reason() + "]";
      String expiry = e.expiresAt() > 0 ? " (expires " + formatDuration(secondsUntil(e.expiresAt())) + ")" : "";
      src.sendMessage(Component.text(" - " + e.type().name().toLowerCase(Locale.ROOT) + ": " + who + reason + expiry));
    }
    if (entries.size() > limit) {
      src.sendMessage(Component.text(" …and " + (entries.size() - limit) + " more."));
    }
  }

  private Component moderationMessage(String template, String player, String reason, long expiresAt) {
    String expiry = expiresAt > 0 ? formatDuration(secondsUntil(expiresAt)) : "never";
    String t = normalize(template);
    return MINI.deserialize(t,
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("reason", reason == null ? "" : reason),
        Placeholder.unparsed("expiry", expiry));
  }

  private ParsedAction parseDurationAndReason(String[] args, int offset, String defaultReason) {
    if (args.length <= offset) {
      return new ParsedAction(0, defaultReason, false);
    }
    long duration = parseDurationSeconds(args[offset]);
    boolean invalid = false;
    int reasonStart = offset;
    if (duration > 0 || isForever(args[offset])) {
      if (isForever(args[offset])) duration = 0;
      reasonStart = offset + 1;
    } else {
      if (duration < 0) invalid = true;
      duration = 0;
    }
    String reason = (args.length > reasonStart) ? joinArgs(args, reasonStart) : defaultReason;
    return new ParsedAction(duration, reason, invalid);
  }

  private record ParsedAction(long durationSeconds, String reason, boolean invalid) {}

  private Target resolveTarget(String raw) {
    UUID uuid = parseUuid(raw);
    String ip = null;
    String name = null;
    var onlineByName = proxy.getPlayer(raw);
    if (onlineByName.isPresent()) {
      var p = onlineByName.get();
      uuid = p.getUniqueId();
      name = p.getUsername();
      ip = remoteIp(p);
    } else if (whitelist != null) {
      var entry = whitelist.lookup(null, raw);
      if (entry.isPresent()) {
        uuid = entry.get().uuid();
        name = entry.get().lastKnownName();
      }
    }
    return new Target(uuid, name, ip);
  }

  private UUID parseUuid(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    try {
      return UUID.fromString(trimmed);
    } catch (IllegalArgumentException ignored) {
      String digits = trimmed.replace("-", "");
      if (digits.length() != 32) return null;
      try {
        return UUID.fromString(digits.replaceFirst(
            "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
            "$1-$2-$3-$4-$5"));
      } catch (IllegalArgumentException ignored2) {
        return null;
      }
    }
  }

  private boolean isForever(String raw) {
    if (raw == null) return false;
    for (String kw : FOREVER) {
      if (kw.equalsIgnoreCase(raw)) return true;
    }
    return false;
  }

  private long secondsUntil(long millisEpoch) {
    long now = System.currentTimeMillis();
    if (millisEpoch <= now) return 0L;
    return (millisEpoch - now) / 1000L;
  }

  private static String nameOf(CommandSource src) {
    return (src instanceof Player p) ? p.getUsername() : "CONSOLE";
  }

  private boolean has(CommandSource src, String... perms) {
    if (!(src instanceof Player)) return true;
    for (String perm : perms) {
      if (perm != null && src.hasPermission(perm)) return true;
    }
    return false;
  }

  private String joinArgs(String[] args, int from) {
    if (args == null || from >= args.length) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < args.length; i++) {
      if (i > from) sb.append(' ');
      sb.append(args[i]);
    }
    return sb.toString();
  }

  private List<String> suggestPlayers(String[] args) {
    if (args.length == 0) return List.of();
    String partial = args[args.length - 1];
    List<String> matches = new ArrayList<>();
    for (Player p : proxy.getAllPlayers()) {
      if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))) {
        matches.add(p.getUsername());
      }
    }
    matches.sort(String.CASE_INSENSITIVE_ORDER);
    return matches;
  }

  private long parseDurationSeconds(String raw) {
    if (raw == null) return -1;
    String trimmed = raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) return -1;
    if (isForever(trimmed)) return 0L;

    Matcher matcher = DURATION_TOKEN.matcher(trimmed);
    int index = 0;
    long total = 0L;
    while (matcher.find()) {
      if (matcher.start() != index) return -1;
      index = matcher.end();

      long value = Long.parseLong(matcher.group(1));
      String unitGroup = matcher.group(2);
      char unit = unitGroup == null || unitGroup.isEmpty() ? 'm' : unitGroup.charAt(0);
      long multiplier = switch (Character.toLowerCase(unit)) {
        case 'd' -> 86400L;
        case 'h' -> 3600L;
        case 'm' -> 60L;
        case 's' -> 1L;
        default -> -1L;
      };
      if (multiplier <= 0L) return -1;

      if (value > Long.MAX_VALUE / multiplier) {
        total = Long.MAX_VALUE;
        continue;
      }
      long add = value * multiplier;
      if (Long.MAX_VALUE - total < add) {
        total = Long.MAX_VALUE;
      } else {
        total += add;
      }
    }

    if (index != trimmed.length()) return -1;
    if (total < 0L) return -1;
    return total;
  }

  private String formatDuration(long seconds) {
    if (seconds <= 0L) return "never";
    long remaining = seconds;
    long days = remaining / 86400L;
    remaining %= 86400L;
    long hours = remaining / 3600L;
    remaining %= 3600L;
    long minutes = remaining / 60L;
    long secs = remaining % 60L;

    StringBuilder sb = new StringBuilder();
    appendUnit(sb, days, 'd');
    appendUnit(sb, hours, 'h');
    appendUnit(sb, minutes, 'm');
    if (sb.length() == 0 || days == 0 && hours == 0 && minutes == 0) {
      appendUnit(sb, secs, 's');
    } else if (secs > 0) {
      appendUnit(sb, secs, 's');
    }
    return sb.toString().trim();
  }

  private void appendUnit(StringBuilder sb, long value, char suffix) {
    if (value <= 0) return;
    if (sb.length() > 0) sb.append(' ');
    sb.append(value).append(suffix);
  }

  private boolean looksLikeIp(String raw) {
    if (raw == null) return false;
    return raw.contains(".") || raw.contains(":");
  }

  private String remoteIp(Player player) {
    try {
      var addr = player.getRemoteAddress();
      if (addr instanceof InetSocketAddress) {
        InetSocketAddress inet = (InetSocketAddress) addr;
        if (inet.getAddress() != null) {
          return inet.getAddress().getHostAddress();
        }
      }
    } catch (Exception ignored) {}
    return null;
  }

  private String displayName(ModerationService.Entry e) {
    if (e.name() != null && !e.name().isBlank()) return e.name();
    if (e.uuid() != null) {
      var online = proxy.getPlayer(e.uuid());
      if (online.isPresent()) return online.get().getUsername();
      if (whitelist != null) {
        var wl = whitelist.lookup(e.uuid(), null);
        if (wl.isPresent() && wl.get().lastKnownName() != null) return wl.get().lastKnownName();
      }
      return e.uuid().toString();
    }
    if (e.ip() != null) return e.ip();
    return "unknown";
  }

  private String suffix(long expiresAt) {
    if (expiresAt <= 0) return " (permanent)";
    return " (expires in " + formatDuration(secondsUntil(expiresAt)) + ")";
  }

  private String reasonSuffix(String reason) {
    if (reason == null || reason.isBlank()) return "";
    return " for: " + reason;
  }

  private static String normalize(String s) {
    if (s == null) return "";
    return s
        .replace("{player}", "<player>").replace("(player)", "<player>")
        .replace("{reason}", "<reason>").replace("(reason)", "<reason>")
        .replace("{expiry}", "<expiry>").replace("(expiry)", "<expiry>");
  }

  private record Target(UUID uuid, String name, String ip) {
    String display() {
      if (name != null && !name.isBlank()) return name;
      if (uuid != null) return uuid.toString();
      if (ip != null) return ip;
      return "unknown";
    }
  }
}
