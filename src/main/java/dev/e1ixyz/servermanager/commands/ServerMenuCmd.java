package dev.e1ixyz.servermanager.commands;

import dev.e1ixyz.servermanager.Config;
import dev.e1ixyz.servermanager.ServerManagerPlugin;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reskinnable replacement for Velocity's built-in {@code /server}.
 * <p>No args: a config-driven clickable chat menu of every server, each line hovering to show the
 * players currently connected there. With an arg: connect the player to that server (routing through
 * the plugin's normal join machinery via {@link Player#createConnectionRequest}, so start-on-join,
 * whitelist and queue handling all still fire).
 */
public final class ServerMenuCmd implements SimpleCommand {
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final ServerManagerPlugin plugin;
  private final ProxyServer proxy;
  private final Logger log;
  private volatile Config cfg;

  public ServerMenuCmd(ServerManagerPlugin plugin, ProxyServer proxy, Logger log) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.log = log;
  }

  public void updateState(Config cfg) {
    this.cfg = cfg;
  }

  @Override
  public void execute(Invocation inv) {
    CommandSource src = inv.source();
    String[] args = inv.arguments();
    Config config = this.cfg;
    if (config == null || config.serverMenu == null) {
      src.sendMessage(Component.text("Server menu is not ready."));
      return;
    }
    Config.ServerMenu menu = config.serverMenu;

    if (args.length >= 1 && !args[0].isBlank()) {
      connect(src, args[0], config);
      return;
    }

    List<String> order = orderedServers(config);
    if (order.isEmpty()) {
      src.sendMessage(Component.text("No servers are configured."));
      return;
    }

    boolean isPlayer = src instanceof Player;
    if (menu.header != null && !menu.header.isBlank()) src.sendMessage(mm(menu.header));
    for (String name : order) {
      RegisteredServer rs = proxy.getServer(name).orElse(null);
      if (rs == null) continue;
      boolean managed = config.servers != null && config.servers.containsKey(name);
      // ponytail: non-managed Velocity servers can't be liveness-checked here, so treat as online.
      boolean up = !managed || plugin.isServerUp(name);
      if (!up && !menu.showOffline) continue;

      int count = rs.getPlayersConnected().size();
      String state = up ? nz(menu.stateOnline) : nz(menu.stateOffline);
      Component version = versionComponent(name, up, menu);
      Component line = MINI.deserialize(normalize(menu.entry),
          Placeholder.unparsed("server", name),
          Placeholder.unparsed("count", Integer.toString(count)),
          Placeholder.parsed("state", state),
          Placeholder.component("version", version));
      if (isPlayer) {
        line = line
            .hoverEvent(HoverEvent.showText(tooltip(name, rs, menu)))
            .clickEvent(ClickEvent.runCommand("/server " + name));
      }
      src.sendMessage(line);
    }
    if (menu.footer != null && !menu.footer.isBlank()) src.sendMessage(mm(menu.footer));
  }

  /** Build the hover tooltip: a header line plus one line per online player (capped). */
  private Component tooltip(String name, RegisteredServer rs, Config.ServerMenu menu) {
    List<Player> players = new ArrayList<>(rs.getPlayersConnected());
    Component tip = MINI.deserialize(normalize(menu.tooltipHeader),
        Placeholder.unparsed("server", name),
        Placeholder.unparsed("count", Integer.toString(players.size())));

    if (players.isEmpty()) {
      if (menu.tooltipEmpty != null && !menu.tooltipEmpty.isBlank()) {
        tip = tip.append(Component.newline()).append(mm(menu.tooltipEmpty));
      }
      return tip;
    }

    players.sort(Comparator.comparing(p -> p.getUsername().toLowerCase(Locale.ROOT)));
    int cap = menu.tooltipMaxPlayers > 0 ? menu.tooltipMaxPlayers : Integer.MAX_VALUE;
    int shown = Math.min(players.size(), cap);
    for (int i = 0; i < shown; i++) {
      tip = tip.append(Component.newline()).append(MINI.deserialize(normalize(menu.tooltipPlayer),
          Placeholder.unparsed("player", players.get(i).getUsername())));
    }
    int overflow = players.size() - shown;
    if (overflow > 0 && menu.tooltipMore != null && !menu.tooltipMore.isBlank()) {
      tip = tip.append(Component.newline()).append(MINI.deserialize(normalize(menu.tooltipMore),
          Placeholder.unparsed("count", Integer.toString(overflow))));
    }
    return tip;
  }

  /** Formatted version tag for a server (from its last successful ping), or empty when offline/unknown. */
  private Component versionComponent(String name, boolean up, Config.ServerMenu menu) {
    if (!up || menu.version == null || menu.version.isBlank()) return Component.empty();
    String ver = plugin.serverVersion(name);
    if (ver == null || ver.isBlank()) return Component.empty();
    return MINI.deserialize(normalize(menu.version), Placeholder.unparsed("version", ver));
  }

  private void connect(CommandSource src, String token, Config config) {
    if (!(src instanceof Player p)) {
      src.sendMessage(Component.text("Only players can connect to a server."));
      return;
    }
    String resolved = resolveServer(token);
    if (resolved == null) {
      src.sendMessage(MINI.deserialize(normalize(config.messages.unknownServer),
          Placeholder.unparsed("server", token),
          Placeholder.unparsed("player", p.getUsername())));
      return;
    }
    RegisteredServer target = proxy.getServer(resolved).orElse(null);
    if (target == null) {
      src.sendMessage(Component.text("Velocity does not know server " + resolved + "."));
      return;
    }
    String current = p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);
    if (resolved.equalsIgnoreCase(current)) {
      src.sendMessage(Component.text("You are already connected to " + resolved + "."));
      return;
    }
    // connect() fires ServerPreConnectEvent, so PlayerEvents' start-on-join / whitelist / queue logic runs.
    p.createConnectionRequest(target).connect().whenComplete((result, error) -> {
      if (error != null) {
        log.warn("Failed /server connection for {} -> {}", p.getUsername(), resolved, error);
        p.sendMessage(Component.text("Failed to connect to " + resolved + "."));
        return;
      }
      if (result == null || result.isSuccessful()) return;
      result.getReasonComponent().ifPresent(p::sendMessage);
    });
  }

  private String resolveServer(String token) {
    var direct = proxy.getServer(token);
    if (direct.isPresent()) return direct.get().getServerInfo().getName();
    for (RegisteredServer rs : proxy.getAllServers()) {
      if (rs.getServerInfo().getName().equalsIgnoreCase(token)) return rs.getServerInfo().getName();
    }
    return null;
  }

  /** Managed servers in config order first, then any other Velocity server so nothing is hidden. */
  private List<String> orderedServers(Config config) {
    List<String> order = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    if (config != null && config.servers != null) {
      for (String name : config.servers.keySet()) {
        if (proxy.getServer(name).isPresent() && seen.add(name.toLowerCase(Locale.ROOT))) {
          order.add(name);
        }
      }
    }
    for (RegisteredServer rs : proxy.getAllServers()) {
      String n = rs.getServerInfo().getName();
      if (seen.add(n.toLowerCase(Locale.ROOT))) order.add(n);
    }
    return order;
  }

  @Override
  public List<String> suggest(Invocation inv) {
    String[] args = inv.arguments();
    if (args.length > 1) return List.of();
    String partial = (args.length == 1 ? args[0] : "").toLowerCase(Locale.ROOT);
    List<String> out = new ArrayList<>();
    for (String name : orderedServers(this.cfg)) {
      if (partial.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(partial)) out.add(name);
    }
    return out;
  }

  private static Component mm(String s) {
    return MINI.deserialize(normalize(s));
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private static String normalize(String s) {
    if (s == null) return "";
    return s
        .replace("{server}", "<server>").replace("(server)", "<server>")
        .replace("{count}", "<count>").replace("(count)", "<count>")
        .replace("{player}", "<player>").replace("(player)", "<player>")
        .replace("{state}", "<state>").replace("(state)", "<state>")
        .replace("{version}", "<version>").replace("(version)", "<version>");
  }
}
