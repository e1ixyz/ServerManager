package dev.e1ixyz.servermanager.maps;

import dev.e1ixyz.servermanager.Config;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves a single tabbed page at {@code /} and reverse-proxies each configured tab's
 * path (e.g. {@code /smp/*}) to that server's local Dynmap webserver, so both maps live
 * under one hostname. Dynmap uses only relative URLs, so a trailing-slash redirect +
 * prefix strip + verbatim query passthrough is all the URL handling needed — no body
 * rewriting. Mirrors {@link dev.e1ixyz.servermanager.whitelist.WhitelistHttpServer}.
 */
public final class MapsHttpServer {

  /** Never forwarded in either direction. */
  private static final Set<String> HOP_BY_HOP = Set.of(
      "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
      "te", "trailer", "transfer-encoding", "upgrade");
  /** Additionally skipped on the request side: HttpClient sets/forbids these (throws otherwise). */
  private static final Set<String> SKIP_REQ = Set.of(
      "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
      "te", "trailer", "transfer-encoding", "upgrade",
      "host", "content-length", "expect");

  private final Config.Maps cfg;
  private final Logger log;
  private HttpServer http;
  private ExecutorService pool;
  private HttpClient client;

  public MapsHttpServer(Config.Maps cfg, Logger log) {
    this.cfg = cfg;
    this.log = log;
  }

  public void start() throws IOException {
    if (!Boolean.TRUE.equals(cfg.enabled)) return;
    if (http != null) return;

    // Bounded pool for the HttpServer: each proxied request blocks a thread on the backend
    // round-trip, so a single-threaded executor would stall everyone. NOT shared with the
    // HttpClient — sync send() joins on the client's executor, which would deadlock if it were
    // this same pool once all threads are blocked in send().
    AtomicInteger n = new AtomicInteger();
    this.pool = new ThreadPoolExecutor(8, 48, 60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        r -> { Thread t = new Thread(r, "maps-http-" + n.incrementAndGet()); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.CallerRunsPolicy());
    this.client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)   // a proxy forwards 3xx, never chases them
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    InetSocketAddress addr = new InetSocketAddress(cfg.bind, cfg.port);
    http = HttpServer.create(addr, 0);
    http.createContext("/", this::handleRoot);
    if (cfg.tabs != null) {
      for (Config.MapTab t : cfg.tabs) {
        if (t == null || t.path == null || t.backend == null) continue;
        String ctx = "/" + t.path.replaceAll("^/+|/+$", "");   // normalize to "/smp" (no trailing slash)
        String backend = t.backend.replaceAll("/+$", "");        // "http://127.0.0.1:8124"
        if (ctx.equals("/")) continue;                           // don't clobber the wrapper
        http.createContext(ctx, proxyHandler(ctx, backend));
      }
    }
    http.setExecutor(pool);
    http.start();
    log.info("Maps web server listening on {}:{}", cfg.bind, cfg.port);
  }

  public void stop() {
    if (http != null) {
      http.stop(0);
      http = null;
      log.info("Maps web server stopped.");
    }
    if (pool != null) {
      pool.shutdownNow();
      pool = null;
    }
    client = null;
  }

  // ---- wrapper page --------------------------------------------------------

  private void handleRoot(HttpExchange ex) throws IOException {
    try {
      // "/" is a catch-all context; only the exact root serves the page, everything else 404s.
      if (!"/".equals(ex.getRequestURI().getRawPath())) {
        sendPlain(ex, 404, "Not Found");
        return;
      }
      if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
        renderPage(ex);
        return;
      }
      sendPlain(ex, 405, "Method Not Allowed");
    } catch (Exception err) {
      log.warn("Maps HTTP error: {}", err.getMessage());
      sendPlain(ex, 500, "Internal Server Error");
    }
  }

  private void renderPage(HttpExchange ex) throws IOException {
    String title = Objects.toString(cfg.pageTitle, "Network Maps");
    List<Config.MapTab> tabs = cfg.tabs;

    if (tabs == null || tabs.isEmpty()) {
      sendHtml(ex, 200, "<!doctype html><meta charset=\"utf-8\"><title>" + escape(title)
          + "</title><body style=\"font-family:system-ui;background:#0f1115;color:#f0f3f8;padding:24px\">"
          + "<h1>" + escape(title) + "</h1><p>No maps configured.</p></body>");
      return;
    }

    StringBuilder buttons = new StringBuilder();
    StringBuilder frames = new StringBuilder();
    for (int i = 0; i < tabs.size(); i++) {
      Config.MapTab t = tabs.get(i);
      String label = escape(Objects.toString(t.label, "Map " + (i + 1)));
      String seg = t.path == null ? "" : t.path.replaceAll("^/+|/+$", "");
      String src = escapeAttr(seg + "/");   // same-origin relative path, trailing slash avoids a redirect hop
      boolean first = (i == 0);
      buttons.append(String.format(Locale.ROOT,
          "<button class=\"tab%s\" onclick=\"show(%d)\">%s</button>",
          first ? " active" : "", i, label));
      frames.append(String.format(Locale.ROOT,
          "<iframe class=\"frame%s\" data-src=\"%s\"%s></iframe>",
          first ? " active" : "", src, first ? " src=\"" + src + "\"" : ""));
    }

    String html = String.format(Locale.ROOT, """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <title>%s</title>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            html,body{margin:0;height:100%%;}
            body{display:flex;flex-direction:column;background:#0f1115;color:#f0f3f8;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Ubuntu,Arial,sans-serif;}
            .bar{flex:0 0 auto;display:flex;gap:8px;align-items:center;padding:10px 14px;background:#141821;border-bottom:1px solid #22283a;}
            .bar .title{font-weight:600;margin-right:8px;}
            .tab{padding:8px 16px;border-radius:10px;border:1px solid #28324a;background:#0b0f18;color:#d5dcf3;font-size:15px;font-weight:600;cursor:pointer;}
            .tab.active{background:#2563eb;border-color:#2563eb;color:#fff;}
            .frames{flex:1 1 auto;position:relative;}
            .frame{position:absolute;inset:0;width:100%%;height:100%%;border:0;display:none;}
            .frame.active{display:block;}
          </style>
        </head>
        <body>
          <div class="bar"><span class="title">%s</span>%s</div>
          <div class="frames">%s</div>
          <script>
            function show(i){
              var fs=document.querySelectorAll('.frame'), bs=document.querySelectorAll('.tab');
              for(var k=0;k<fs.length;k++){
                var on=(k===i);
                fs[k].classList.toggle('active',on);
                bs[k].classList.toggle('active',on);
                if(on && !fs[k].getAttribute('src')) fs[k].setAttribute('src', fs[k].getAttribute('data-src'));
              }
            }
          </script>
        </body>
        </html>
        """, escape(title), escape(title), buttons.toString(), frames.toString());

    sendHtml(ex, 200, html);
  }

  // ---- reverse proxy -------------------------------------------------------

  private HttpHandler proxyHandler(String contextPath, String backendBase) {
    return ex -> {
      boolean headersSent = false;
      try {
        String rawPath = ex.getRequestURI().getRawPath();
        String rawQuery = ex.getRequestURI().getRawQuery();

        // Trailing-slash redirect so Dynmap's relative assets resolve under contextPath + "/".
        if (rawPath.equals(contextPath)) {
          ex.getResponseHeaders().add("Location", contextPath + "/" + (rawQuery != null ? "?" + rawQuery : ""));
          ex.sendResponseHeaders(308, -1);   // 308 preserves method; -1 = no body
          headersSent = true;
          return;
        }

        // Strip the prefix. Context matching isn't segment-aware, so "/smpfoo" lands here too — reject it.
        String rest = rawPath.substring(contextPath.length());   // "/smp/js/x" -> "/js/x", "/smp/" -> "/"
        if (!rest.startsWith("/")) {
          sendPlain(ex, 404, "Not Found");
          return;
        }

        URI target = URI.create(backendBase + rest + (rawQuery != null ? "?" + rawQuery : ""));
        String method = ex.getRequestMethod();
        HttpRequest.Builder b = HttpRequest.newBuilder(target)
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(30));

        for (var e : ex.getRequestHeaders().entrySet()) {
          String name = e.getKey();
          if (name == null || SKIP_REQ.contains(name.toLowerCase(Locale.ROOT))) continue;
          for (String v : e.getValue()) {
            try { b.header(name, v); } catch (IllegalArgumentException ignore) { /* restricted header */ }
          }
        }

        boolean reqBody = !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
        b.method(method, reqBody
            ? HttpRequest.BodyPublishers.ofByteArray(ex.getRequestBody().readAllBytes())
            : HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();

        Headers out = ex.getResponseHeaders();
        long declaredLen = -1;
        for (var e : resp.headers().map().entrySet()) {
          String name = e.getKey();
          if (name == null || name.startsWith(":")) continue;    // skip any pseudo-headers
          String lc = name.toLowerCase(Locale.ROOT);
          if (lc.equals("content-length")) {
            try { declaredLen = Long.parseLong(e.getValue().get(0)); } catch (Exception ignore) {}
            continue;   // framing is set by sendResponseHeaders below
          }
          if (HOP_BY_HOP.contains(lc)) continue;
          if (lc.equals("location")) {
            for (String v : e.getValue()) out.add("Location", rewriteLocation(v, backendBase, contextPath));
            continue;
          }
          for (String v : e.getValue()) out.add(name, v);
        }

        boolean bodyless = "HEAD".equalsIgnoreCase(method)
            || code == 204 || code == 304 || (code >= 100 && code < 200);

        try (InputStream in = resp.body()) {
          if (bodyless || declaredLen == 0) {
            ex.sendResponseHeaders(code, -1);          // no body (Content-Length: 0 must be -1, not 0)
            headersSent = true;
          } else if (declaredLen > 0) {
            ex.sendResponseHeaders(code, declaredLen); // fixed length
            headersSent = true;
            try (OutputStream os = ex.getResponseBody()) { in.transferTo(os); }
          } else {
            ex.sendResponseHeaders(code, 0);           // 0 = chunked/unknown length
            headersSent = true;
            try (OutputStream os = ex.getResponseBody()) { in.transferTo(os); }
          }
        }
      } catch (HttpConnectTimeoutException t) {
        if (!headersSent) trySend(ex, 504, "Gateway Timeout");
      } catch (Exception err) {
        log.warn("Maps proxy error ({}): {}", contextPath, err.toString());
        if (!headersSent) trySend(ex, 502, "Bad Gateway");
      } finally {
        ex.close();
      }
    };
  }

  private String rewriteLocation(String loc, String backendBase, String contextPath) {
    if (loc == null) return "";
    if (loc.startsWith(backendBase)) {
      String rest = loc.substring(backendBase.length());
      return contextPath + (rest.startsWith("/") ? rest : "/" + rest);
    }
    if (loc.startsWith("/")) return contextPath + loc;
    return loc;   // relative — resolves correctly under contextPath/
  }

  // ---- helpers (mirror WhitelistHttpServer) --------------------------------

  private void trySend(HttpExchange ex, int status, String text) {
    try { sendPlain(ex, status, text); } catch (IOException ignore) { /* connection gone */ }
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

  private String escapeAttr(String s) {
    return escape(s).replace("\"", "&quot;");
  }
}
