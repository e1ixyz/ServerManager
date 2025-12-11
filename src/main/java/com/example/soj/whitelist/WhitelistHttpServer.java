package com.example.soj.whitelist;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.example.soj.admin.AdminAuthService;
import com.example.soj.admin.AdminRoutes;
import com.example.soj.admin.AdminSessionManager;
import com.example.soj.bans.NetworkBanService;
import com.example.soj.commands.ServerManagerCmd;
import com.example.soj.ServerManagerPlugin;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal web UI to redeem join codes and add players to the network whitelist.
 * Also hosts the optional admin panel when enabled.
 */
public final class WhitelistHttpServer {

  private final Config fullCfg;
  private final Config.Whitelist cfg;
  private final Logger log;
  private final WhitelistService whitelist;
  private final AdminRoutes adminRoutes;
  private HttpServer http;

  public WhitelistHttpServer(Config fullCfg,
                             Config.Whitelist cfg,
                             Config.Admin adminCfg,
                             Logger log,
                             WhitelistService whitelist,
                             ServerProcessManager mgr,
                             NetworkBanService networkBans,
                             VanillaWhitelistChecker vanillaWhitelist,
                             ServerManagerCmd cmd,
                             java.nio.file.Path dataDir,
                             AdminAuthService adminAuth,
                             AdminSessionManager adminSessions,
                             ServerManagerPlugin plugin) throws IOException {
    this.fullCfg = fullCfg;
    this.cfg = cfg;
    this.log = log;
    this.whitelist = whitelist;
    if (adminCfg != null && adminCfg.enabled && adminAuth != null && adminSessions != null) {
      this.adminRoutes = new AdminRoutes(
          fullCfg, log, adminAuth, adminSessions, mgr, whitelist, vanillaWhitelist, networkBans, cmd, plugin);
    } else {
      this.adminRoutes = null;
    }
  }

  public void start() throws IOException {
    if (!Boolean.TRUE.equals(cfg.enabled)) return;
    if (http != null) return;

    InetSocketAddress addr = new InetSocketAddress(cfg.bind, cfg.port);
    http = HttpServer.create(addr, 0);
    http.createContext("/", this::handleRoot);
    if (adminRoutes != null) {
      http.createContext("/admin", this::handleAdmin);
      http.createContext("/admin/", this::handleAdmin);
      http.createContext("/admin/login", this::handleAdmin);
      http.createContext("/admin/api/action", this::handleAdmin);
    }
    http.setExecutor(null);
    http.start();
    log.info("Whitelist web server listening on {}:{} (url: {})", cfg.bind, cfg.port, cfg.baseUrl);
  }

  public void stop() {
    if (http != null) {
      http.stop(0);
      http = null;
      log.info("Whitelist web server stopped.");
    }
  }

  private void handleAdmin(HttpExchange ex) throws IOException {
    if (adminRoutes == null) {
      sendPlain(ex, 404, "Not Found");
      return;
    }
    if (!adminRoutes.handle(ex)) {
      sendPlain(ex, 404, "Not Found");
    }
  }

  private void handleRoot(HttpExchange ex) throws IOException {
    try {
      String method = ex.getRequestMethod();
      if ("GET".equalsIgnoreCase(method)) {
        String rawQuery = ex.getRequestURI().getRawQuery();
        Map<String, String> params = parseKv(rawQuery);
        renderForm(ex, null, params.get("code"), params.get("name"));
        return;
      }
      if ("POST".equalsIgnoreCase(method)) {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = parseKv(body);
        String name = sanitize(form.getOrDefault("name", ""));
        String code = sanitize(form.getOrDefault("code", ""));
        if (code.isBlank()) {
          renderForm(ex, cfg.failureMessage, code, name);
          return;
        }
        boolean ok = whitelist.redeem(code, name);
        if (ok) {
          renderSuccess(ex, name.isBlank() ? null : name);
        } else {
          renderForm(ex, cfg.failureMessage, "", name);
        }
        return;
      }
      sendPlain(ex, 405, "Method Not Allowed");
    } catch (Exception err) {
      log.warn("Whitelist HTTP error: {}", err.getMessage());
      sendPlain(ex, 500, "Internal Server Error");
    }
  }

  private void renderForm(HttpExchange ex, String error, String code, String name) throws IOException {
    String title = Objects.toString(cfg.pageTitle, "Network Access");
    String subtitle = Objects.toString(cfg.pageSubtitle, "Enter your code to whitelist your account.");
    String button = Objects.toString(cfg.buttonText, "Verify");
    String errBlock = (error == null || error.isBlank())
        ? ""
        : "<div class=\"error\">" + escape(error) + "</div>";

    String html = String.format(Locale.ROOT, """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <title>%s</title>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Ubuntu,Arial,sans-serif;background:#0f1115;color:#f0f3f8;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px;}
            .card{background:#141821;border:1px solid #22283a;border-radius:14px;padding:28px;max-width:420px;width:100%%;box-shadow:0 10px 30px rgba(0,0,0,0.45);}
            h1{font-size:26px;margin:0 0 8px;}
            p{margin:0 0 16px;color:#b5bfd8;}
            label{display:block;margin:14px 0 6px;font-weight:600;color:#d5dcf3;}
            input{width:100%%;padding:12px;border-radius:10px;border:1px solid #28324a;background:#0b0f18;color:#fff;font-size:16px;}
            button{margin-top:20px;width:100%%;padding:12px;border-radius:10px;background:#2563eb;border:0;color:#fff;font-size:16px;font-weight:600;cursor:pointer;}
            .error{background:rgba(220,70,70,0.12);border:1px solid rgba(220,70,70,0.5);padding:12px;border-radius:10px;margin-bottom:16px;color:#ffb3b3;}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>%s</h1>
            <p>%s</p>
            %s
            <form method="post">
              <label for="name">Minecraft Username</label>
              <input id="name" name="name" placeholder="Steve" value="%s" autocomplete="username">
              <label for="code">Whitelist Code</label>
              <input id="code" name="code" placeholder="000000" value="%s" autocomplete="one-time-code" autofocus>
              <button type="submit">%s</button>
            </form>
          </div>
        </body>
        </html>
        """,
        escape(title),
        escape(title),
        escape(subtitle),
        errBlock,
        escape(nullToEmpty(name)),
        escape(nullToEmpty(code)),
        escape(button)
    );

    sendHtml(ex, 200, html);
  }

  private void renderSuccess(HttpExchange ex, String name) throws IOException {
    String title = Objects.toString(cfg.pageTitle, "Network Access");
    String msg = Objects.toString(cfg.successMessage, "You are now whitelisted. You may rejoin the server.");
    String display = (name == null || name.isBlank()) ? "" : "<p><strong>" + escape(name) + "</strong></p>";
    String html = String.format(Locale.ROOT, """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <title>%s</title>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Ubuntu,Arial,sans-serif;background:#0f1115;color:#f0f3f8;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px;}
            .card{background:#141821;border:1px solid #22283a;border-radius:14px;padding:28px;max-width:420px;width:100%%;box-shadow:0 10px 30px rgba(0,0,0,0.45);text-align:center;}
            h1{font-size:26px;margin:0 0 12px;}
            p{margin:0 0 16px;color:#b5bfd8;}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>%s</h1>
            %s
            <p>%s</p>
            <p>You can close this page and rejoin the server now.</p>
          </div>
        </body>
        </html>
        """,
        escape(title),
        escape(title),
        display,
        escape(msg)
    );
    sendHtml(ex, 200, html);
  }

  private Map<String, String> parseKv(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isBlank()) return out;
    for (String token : raw.split("&")) {
      if (token.isEmpty()) continue;
      int idx = token.indexOf('=');
      if (idx < 0) {
        out.put(urlDecode(token), "");
        continue;
      }
      String key = urlDecode(token.substring(0, idx));
      String val = urlDecode(token.substring(idx + 1));
      out.put(key, val);
    }
    return out;
  }

  private String urlDecode(String raw) {
    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
  }

  private void sendHtml(HttpExchange ex, int status, String html) throws IOException {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    Headers headers = ex.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    headers.add("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void sendPlain(HttpExchange ex, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    Headers headers = ex.getResponseHeaders();
    headers.add("Content-Type", "text/plain; charset=utf-8");
    headers.add("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private String sanitize(String s) {
    return s == null ? "" : s.strip();
  }

  private String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
