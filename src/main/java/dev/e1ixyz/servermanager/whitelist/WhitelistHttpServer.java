package dev.e1ixyz.servermanager.whitelist;

import dev.e1ixyz.servermanager.Config;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal web UI to redeem join codes and add players to the network whitelist.
 * The code is bound to the player's authenticated UUID when it is issued, so the
 * page only asks for the code — entering it whitelists the account it was issued to.
 */
public final class WhitelistHttpServer {

  private static final int MAX_BODY_BYTES = 8192;
  private static final String CSP =
      "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

  private final Config.Whitelist cfg;
  private final Logger log;
  private final WhitelistService whitelist;
  private HttpServer http;

  /** Per-IP sliding-window attempt timestamps (brute-force throttle on redemption). */
  private final Map<String, Deque<Long>> attempts = new HashMap<>();

  public WhitelistHttpServer(Config.Whitelist cfg, Logger log, WhitelistService whitelist) {
    this.cfg = cfg;
    this.log = log;
    this.whitelist = whitelist;
  }

  public void start() throws IOException {
    if (!Boolean.TRUE.equals(cfg.enabled)) return;
    if (http != null) return;

    InetSocketAddress addr = new InetSocketAddress(cfg.bind, cfg.port);
    http = HttpServer.create(addr, 0);
    http.createContext("/", this::handleRoot);
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

  private void handleRoot(HttpExchange ex) throws IOException {
    try {
      String method = ex.getRequestMethod();
      if ("GET".equalsIgnoreCase(method)) {
        Map<String, String> params = parseKv(ex.getRequestURI().getRawQuery());
        renderForm(ex, 200, null, params.get("code"));
        return;
      }
      if ("POST".equalsIgnoreCase(method)) {
        if (rateLimited(clientIp(ex))) {
          log.warn("Whitelist redeem rate-limited for {}", clientIp(ex));
          renderForm(ex, 429, rateLimitMessage(), "");
          return;
        }
        // Bounded read: reject oversized bodies rather than buffering unbounded input.
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
          sendPlain(ex, 413, "Payload Too Large");
          return;
        }
        Map<String, String> form = parseKv(new String(raw, StandardCharsets.UTF_8));
        String code = sanitize(form.getOrDefault("code", ""));
        if (code.isBlank()) {
          renderForm(ex, 200, cfg.failureMessage, code);
          return;
        }
        // Blank username -> WhitelistService whitelists the code's bound UUID under its original name.
        boolean ok = whitelist.redeem(code, "");
        if (ok) {
          renderSuccess(ex);
        } else {
          renderForm(ex, 200, cfg.failureMessage, "");
        }
        return;
      }
      sendPlain(ex, 405, "Method Not Allowed");
    } catch (Exception err) {
      log.warn("Whitelist HTTP error: {}", err.getMessage());
      sendPlain(ex, 500, "Internal Server Error");
    }
  }

  private void renderForm(HttpExchange ex, int status, String error, String code) throws IOException {
    String title = Objects.toString(cfg.pageTitle, "Network Access");
    String subtitle = Objects.toString(cfg.pageSubtitle, "Enter the code shown in-game to whitelist your account.");
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
              <label for="code">Whitelist Code</label>
              <input id="code" name="code" placeholder="000000" value="%s" inputmode="numeric" autocomplete="one-time-code" autofocus>
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
        escapeAttr(nullToEmpty(code)),
        escape(button)
    );

    sendHtml(ex, status, html);
  }

  private void renderSuccess(HttpExchange ex) throws IOException {
    String title = Objects.toString(cfg.pageTitle, "Network Access");
    String msg = Objects.toString(cfg.successMessage, "You are now whitelisted. You may rejoin the server.");
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
            <p>%s</p>
            <p>You can close this page and rejoin the server now.</p>
          </div>
        </body>
        </html>
        """,
        escape(title),
        escape(title),
        escape(msg)
    );
    sendHtml(ex, 200, html);
  }

  // ---- rate limiting -------------------------------------------------------

  /** True if this IP has exhausted its attempt budget in the sliding window. */
  private synchronized boolean rateLimited(String ip) {
    long now = System.currentTimeMillis();
    long windowMs = Math.max(1, cfg.windowSeconds) * 1000L;
    int max = Math.max(1, cfg.maxAttempts);
    Deque<Long> dq = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
    while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) dq.pollFirst();
    if (dq.size() >= max) return true;
    dq.addLast(now);
    if (attempts.size() > 10_000) attempts.values().removeIf(Deque::isEmpty); // ponytail: crude cap, fine for this traffic
    return false;
  }

  /** Real client IP behind cloudflared (socket is always 127.0.0.1 there). */
  private String clientIp(HttpExchange ex) {
    Headers h = ex.getRequestHeaders();
    String cf = h.getFirst("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) return cf.trim();
    String xff = h.getFirst("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
    return ex.getRemoteAddress() == null ? "unknown" : ex.getRemoteAddress().getAddress().getHostAddress();
  }

  private String rateLimitMessage() {
    return Objects.toString(cfg.rateLimitedMessage, "Too many attempts. Please wait a few minutes and try again.");
  }

  // ---- helpers -------------------------------------------------------------

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
    addSecurityHeaders(ex);
    Headers headers = ex.getResponseHeaders();
    headers.add("Content-Type", "text/html; charset=utf-8");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void sendPlain(HttpExchange ex, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    addSecurityHeaders(ex);
    Headers headers = ex.getResponseHeaders();
    headers.add("Content-Type", "text/plain; charset=utf-8");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void addSecurityHeaders(HttpExchange ex) {
    Headers h = ex.getResponseHeaders();
    h.add("Cache-Control", "no-store");
    h.add("X-Content-Type-Options", "nosniff");
    h.add("X-Frame-Options", "DENY");
    h.add("Referrer-Policy", "no-referrer");
    h.add("Content-Security-Policy", CSP);
  }

  private String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** For values placed inside double-quoted HTML attributes (also escapes quotes). */
  private String escapeAttr(String s) {
    return escape(s).replace("\"", "&quot;").replace("'", "&#39;");
  }

  private String sanitize(String s) {
    return s == null ? "" : s.strip();
  }

  private String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
