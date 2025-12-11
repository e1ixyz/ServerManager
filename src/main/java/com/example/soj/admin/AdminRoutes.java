package com.example.soj.admin;

import com.example.soj.Config;
import com.example.soj.ServerManagerPlugin;
import com.example.soj.ServerProcessManager;
import com.example.soj.bans.NetworkBanService;
import com.example.soj.commands.ServerManagerCmd;
import com.example.soj.whitelist.VanillaWhitelistChecker;
import com.example.soj.whitelist.WhitelistService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles /admin and related endpoints on the whitelist HTTP server.
 */
public final class AdminRoutes {
  private final Config cfg;
  private final Logger log;
  private final AdminAuthService auth;
  private final AdminSessionManager sessions;
  private final ServerProcessManager mgr;
  private final WhitelistService whitelist;
  private final VanillaWhitelistChecker vanillaWhitelist;
  private final NetworkBanService networkBans;
  private final ServerManagerCmd cmd;
  private final ServerManagerPlugin plugin;

  private static final String COOKIE_NAME = "sm_admin";

  public AdminRoutes(Config cfg,
                     Logger log,
                     AdminAuthService auth,
                     AdminSessionManager sessions,
                     ServerProcessManager mgr,
                     WhitelistService whitelist,
                     VanillaWhitelistChecker vanillaWhitelist,
                     NetworkBanService networkBans,
                     ServerManagerCmd cmd,
                     ServerManagerPlugin plugin) {
    this.cfg = cfg;
    this.log = log;
    this.auth = auth;
    this.sessions = sessions;
    this.mgr = mgr;
    this.whitelist = whitelist;
    this.vanillaWhitelist = vanillaWhitelist;
    this.networkBans = networkBans;
    this.cmd = cmd;
    this.plugin = plugin;
  }

  public boolean handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    if (path == null || (!path.startsWith("/admin"))) return false;
    if (path.startsWith("/admin/api/")) {
      handleAction(ex);
      return true;
    }
    if ("POST".equalsIgnoreCase(ex.getRequestMethod()) && ("/admin".equals(path) || "/admin/".equals(path) || "/admin/login".equals(path))) {
      handleLogin(ex);
      return true;
    }
    handlePanel(ex);
    return true;
  }

  private void handleLogin(HttpExchange ex) throws IOException {
    Map<String, String> form = parseKv(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String user = sanitize(form.get("user"));
    String code = sanitize(form.get("code"));
    if (auth.verify(user, code)) {
      String token = sessions.issue(user);
      setCookie(ex, COOKIE_NAME, token, cfg.admin.sessionMinutes);
      redirect(ex, "/admin");
      return;
    }
    renderLogin(ex, "Invalid code or user.");
  }

  private void handlePanel(HttpExchange ex) throws IOException {
    AdminSessionManager.Session session = session(ex);
    if (session == null) {
      renderLogin(ex, null);
      return;
    }
    renderDashboard(ex, session.mcUser());
  }

  /** Allows other routes to authenticate admin token + username and issue a session cookie. */
  public boolean tryTokenLogin(String user, String token, HttpExchange ex) throws IOException {
    if (user == null || token == null) return false;
    if (!auth.verify(user, token)) return false;
    String sessionToken = sessions.issue(user);
    setCookie(ex, COOKIE_NAME, sessionToken, cfg.admin.sessionMinutes);
    redirect(ex, "/admin");
    return true;
  }

  private void handleAction(HttpExchange ex) throws IOException {
    AdminSessionManager.Session session = session(ex);
    if (session == null) {
      sendJson(ex, 401, "{\"error\":\"unauthorized\"}");
      return;
    }
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, "{\"error\":\"method\"}");
      return;
    }

    Map<String, String> form = parseKv(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String action = form.get("action");
    String server = form.get("server");
    String arg = form.get("arg");
    String name = form.get("name");
    String reason = form.get("reason");

    try {
      switch (action) {
        case "start" -> mgr.start(server);
        case "stop" -> mgr.stop(server);
        case "hold" -> mgr.hold(server, Long.parseLong(arg));
        case "holdClear" -> mgr.clearHold(server);
        case "reload" -> { if (plugin != null) plugin.reload(); }
        case "whitelistNetworkAdd" -> {
          UUID uuid = parseUuid(arg);
          whitelist.add(uuid, name);
          if (plugin != null) plugin.mirrorNetworkWhitelistEntry(uuid, name);
        }
        case "whitelistNetworkRemove" -> {
          UUID uuid = parseUuid(arg);
          whitelist.remove(uuid, name);
          if (plugin != null) plugin.removeNetworkWhitelistEntry(uuid, name);
        }
        case "whitelistVanillaAdd" -> {
          if (vanillaWhitelist != null) {
            UUID uuid = parseUuid(arg);
            vanillaWhitelist.addEntry(server, uuid, name);
            if (name != null && mgr.isRunning(server)) mgr.sendCommand(server, "whitelist add " + name);
          }
        }
        case "whitelistVanillaRemove" -> {
          if (vanillaWhitelist != null) {
            UUID uuid = parseUuid(arg);
            vanillaWhitelist.removeEntry(server, uuid, name);
            if (name != null && mgr.isRunning(server)) mgr.sendCommand(server, "whitelist remove " + name);
          }
        }
        case "netbanAdd" -> {
          if (networkBans != null) {
            UUID uuid = parseUuid(arg);
            String finalReason = (reason == null || reason.isBlank()) ? "Web admin ban" : reason;
            networkBans.ban(uuid, name, finalReason, session.mcUser());
            whitelist.remove(uuid, name);
            if (plugin != null) plugin.removeNetworkWhitelistEntry(uuid, name);
          }
        }
        case "netbanRemove" -> {
          if (networkBans != null) {
            UUID uuid = parseUuid(arg);
            networkBans.unban(uuid, name);
          }
        }
        default -> {
          sendJson(ex, 400, "{\"error\":\"unknown action\"}");
          return;
        }
      }
      sendJson(ex, 200, "{\"ok\":true}");
    } catch (Exception err) {
      log.warn("Admin action failed", err);
      sendJson(ex, 500, "{\"error\":\"" + escape(err.getMessage()) + "\"}");
    }
  }

  private AdminSessionManager.Session session(HttpExchange ex) {
    List<String> cookies = ex.getRequestHeaders().get("Cookie");
    if (cookies == null) return null;
    for (String header : cookies) {
      List<HttpCookie> parsed = HttpCookie.parse(header);
      for (HttpCookie c : parsed) {
        if (COOKIE_NAME.equals(c.getName())) {
          return sessions.verify(c.getValue());
        }
      }
    }
    return null;
  }

  private void renderLogin(HttpExchange ex, String error) throws IOException {
    String err = (error == null) ? "" : "<div class='err'>" + escape(error) + "</div>";
    String html = """
        <!doctype html><html><head><meta charset='utf-8'><title>Admin Login</title>
        <meta name='viewport' content='width=device-width, initial-scale=1'>
        <style>
        body{margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center;background:#0f1115;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Ubuntu,Arial,sans-serif;color:#f6f7fb;padding:24px;}
        .card{background:#141821;border:1px solid #22283a;border-radius:14px;padding:28px;max-width:420px;width:100%;box-shadow:0 10px 30px rgba(0,0,0,0.45);}
        h1{margin:0 0 12px;font-size:24px;}
        label{display:block;margin:12px 0 6px;font-weight:600;color:#d5dcf3;}
        input{width:100%;padding:12px;border-radius:10px;border:1px solid #28324a;background:#0b0f18;color:#fff;font-size:16px;}
        button{margin-top:20px;width:100%;padding:12px;border-radius:10px;background:#2563eb;border:0;color:#fff;font-size:16px;font-weight:600;cursor:pointer;}
        .err{background:rgba(220,70,70,0.12);border:1px solid rgba(220,70,70,0.5);padding:12px;border-radius:10px;margin-bottom:16px;color:#ffb3b3;}
        </style></head><body>
        <div class='card'><h1>Admin Login</h1>%s<form method='post' action='/admin'>
        <label for='user'>Minecraft Username</label><input id='user' name='user' autocomplete='username'>
        <label for='code'>Admin Token</label><input id='code' name='code' autocomplete='one-time-code'>
        <button type='submit'>Sign In</button></form></div></body></html>
        """.formatted(err);
    sendHtml(ex, 200, html);
  }

  private void renderDashboard(HttpExchange ex, String mcUser) throws IOException {
    StringBuilder servers = new StringBuilder();
    for (String name : cfg.servers.keySet()) {
      boolean running = mgr.isRunning(name);
      long hold = mgr.holdRemainingSeconds(name);
      servers.append("<div class='srv'><div class='top'><div class='name'>")
          .append(escape(name))
          .append("</div><div class='pill ").append(running ? "on" : "off").append("'>")
          .append(running ? "Online" : "Offline").append("</div></div>")
          .append("<div class='meta'>Hold: ").append(hold == Long.MAX_VALUE ? "forever" : hold + "s").append("</div>")
          .append("<div class='actions'>")
          .append(btn("Start", "start", name))
          .append(btn("Stop", "stop", name))
          .append(btn("Hold 10m", "hold", name, "600"))
          .append(btn("Hold forever", "hold", name, String.valueOf(Long.MAX_VALUE)))
          .append(btn("Clear hold", "holdClear", name))
          .append("</div></div>");
    }

    StringBuilder nwList = new StringBuilder();
    if (whitelist != null) {
      var entries = new ArrayList<>(whitelist.snapshot());
      entries.sort(Comparator.comparing(WhitelistService.Entry::addedAt).reversed());
      int limit = Math.min(entries.size(), 20);
      for (int i = 0; i < limit; i++) {
        var e = entries.get(i);
        nwList.append("<li>")
            .append(escape(display(e.uuid(), e.lastKnownName())))
            .append(" <button onclick=\"act('whitelistNetworkRemove','','").append(escapeJs(e.uuid().toString())).append("')\">Remove</button></li>");
      }
    }

    StringBuilder banList = new StringBuilder();
    if (networkBans != null) {
      var entries = new ArrayList<>(networkBans.entries());
      entries.sort(Comparator.comparing(NetworkBanService.Entry::bannedAt).reversed());
      int limit = Math.min(entries.size(), 20);
      for (int i = 0; i < limit; i++) {
        var e = entries.get(i);
        banList.append("<li>")
            .append(escape(display(e.uuid(), e.lastKnownName())))
            .append(" [").append(escape(nullToEmpty(e.reason()))).append("] ")
            .append("<button onclick=\"act('netbanRemove','','").append(escapeJs(e.uuid().toString())).append("','").append(escapeJs(nullToEmpty(e.lastKnownName()))).append("')\">Unban</button></li>");
      }
    }

    String html = """
        <!doctype html><html><head><meta charset='utf-8'><title>ServerManager Admin</title>
        <meta name='viewport' content='width=device-width, initial-scale=1'>
        <style>
        body{margin:0;background:#0f1115;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Ubuntu,Arial,sans-serif;color:#f6f7fb;padding:20px;}
        header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;}
        .pill{padding:6px 12px;border-radius:999px;font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:0.5px;}
        .pill.on{background:rgba(52,211,153,0.15);color:#34d399;border:1px solid rgba(52,211,153,0.4);}
        .pill.off{background:rgba(248,113,113,0.12);color:#f87171;border:1px solid rgba(248,113,113,0.35);}
        .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px;}
        .srv{background:#141821;border:1px solid #22283a;border-radius:12px;padding:16px;box-shadow:0 10px 30px rgba(0,0,0,0.35);}
        .top{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;}
        .name{font-size:18px;font-weight:700;}
        .meta{color:#9ca3af;font-size:13px;margin-bottom:10px;}
        .actions{display:flex;flex-wrap:wrap;gap:8px;}
        button{padding:10px 14px;border-radius:10px;background:#2563eb;border:0;color:#fff;font-weight:600;cursor:pointer;}
        button.red{background:#ef4444;} button.gray{background:#374151;}
        section{background:#11151e;border:1px solid #1f2736;border-radius:12px;padding:16px;margin-top:16px;}
        h2{margin:0 0 12px;font-size:18px;}
        ul{margin:0;padding-left:18px;color:#cbd5e1;}
        form input{margin-right:6px;padding:8px 10px;border-radius:8px;border:1px solid #28324a;background:#0b0f18;color:#fff;}
        </style></head><body>
        <header><div><strong>ServerManager Admin</strong><div style='color:#9ca3af;font-size:13px;'>Signed in as %s</div></div>
        <div style='display:flex;gap:8px;align-items:center;'><button class='gray' onclick=\"act('reload','','')\">Reload</button>
        <form method='post' action='/admin'><input type='hidden' name='logout' value='1'><button class='gray'>Sign out</button></form></div></header>
        <div class='grid'>%s</div>
        <section><h2>Network Whitelist</h2>
        <form onsubmit="submitWl(event)"><input name='name' placeholder='Username'> <input name='uuid' placeholder='UUID (optional)'><button>Add</button></form>
        <ul>%s</ul></section>
        <section><h2>Network Bans</h2>
        <form onsubmit="submitBan(event)"><input name='name' placeholder='Username'> <input name='uuid' placeholder='UUID (optional)'> <input name='reason' placeholder='Reason'> <button class='red'>Ban</button></form>
        <ul>%s</ul></section>
        <script>
        async function act(action, server, arg, name, reason){const body=new URLSearchParams({action,server,arg:arg||"",name:name||"",reason:reason||""});const res=await fetch('/admin/api/action',{method:'POST',body});if(!res.ok){alert('Action failed');return;}location.reload();}
        function submitWl(e){e.preventDefault();const f=e.target;act('whitelistNetworkAdd','',f.uuid.value,f.name.value);}
        function submitBan(e){e.preventDefault();const f=e.target;act('netbanAdd','',f.uuid.value,f.name.value,f.reason.value);}
        </script>
        </body></html>
        """.formatted(escape(mcUser), servers, nwList, banList);
    sendHtml(ex, 200, html);
  }

  private String btn(String label, String action, String server) {
    return btn(label, action, server, "");
  }

  private String btn(String label, String action, String server, String arg) {
    return "<button onclick=\"act('" + escapeJs(action) + "','" + escapeJs(server) + "','" + escapeJs(arg) + "')\">" + escape(label) + "</button>";
  }

  private void setCookie(HttpExchange ex, String name, String value, int minutes) {
    HttpCookie cookie = new HttpCookie(name, value);
    cookie.setHttpOnly(true);
    cookie.setPath("/admin");
    cookie.setMaxAge(minutes * 60L);
    ex.getResponseHeaders().add("Set-Cookie", cookie.toString());
  }

  private void redirect(HttpExchange ex, String location) throws IOException {
    Headers h = ex.getResponseHeaders();
    h.add("Location", location);
    ex.sendResponseHeaders(302, -1);
    ex.close();
  }

  private Map<String, String> parseKv(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isBlank()) return out;
    for (String token : raw.split("&")) {
      if (token.isEmpty()) continue;
      int idx = token.indexOf('=');
      String key = idx < 0 ? token : token.substring(0, idx);
      String val = idx < 0 ? "" : token.substring(idx + 1);
      out.put(urlDecode(key), urlDecode(val));
    }
    return out;
  }

  private String sanitize(String s) {
    return s == null ? "" : s.trim();
  }

  private String escape(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private String escapeJs(String s) {
    return s == null ? "" : s.replace("'", "\\'");
  }

  private String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private String display(UUID uuid, String name) {
    if (name != null && !name.isBlank()) return name + " (" + uuid + ")";
    return uuid == null ? "unknown" : uuid.toString();
  }

  private void sendHtml(HttpExchange ex, int status, String html) throws IOException {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    Headers headers = ex.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    headers.add("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
  }

  private void sendJson(HttpExchange ex, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    Headers headers = ex.getResponseHeaders();
    headers.add("Content-Type", "application/json; charset=utf-8");
    headers.add("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
  }

  private String urlDecode(String raw) {
    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
  }

  private UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try { return UUID.fromString(raw.trim()); } catch (Exception ignored) { return null; }
  }
}
