package dev.e1ixyz.servermanager.maps;

import dev.e1ixyz.servermanager.Config;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Minimal web UI that embeds each server's Dynmap in an iframe and lets you
 * switch between them with tabs. Mirrors {@link dev.e1ixyz.servermanager.whitelist.WhitelistHttpServer}.
 */
public final class MapsHttpServer {

  private final Config.Maps cfg;
  private final Logger log;
  private HttpServer http;

  public MapsHttpServer(Config.Maps cfg, Logger log) {
    this.cfg = cfg;
    this.log = log;
  }

  public void start() throws IOException {
    if (!Boolean.TRUE.equals(cfg.enabled)) return;
    if (http != null) return;

    InetSocketAddress addr = new InetSocketAddress(cfg.bind, cfg.port);
    http = HttpServer.create(addr, 0);
    http.createContext("/", this::handleRoot);
    http.setExecutor(null);
    http.start();
    log.info("Maps web server listening on {}:{}", cfg.bind, cfg.port);
  }

  public void stop() {
    if (http != null) {
      http.stop(0);
      http = null;
      log.info("Maps web server stopped.");
    }
  }

  private void handleRoot(HttpExchange ex) throws IOException {
    try {
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
      String url = escapeAttr(Objects.toString(t.url, ""));
      boolean first = (i == 0);
      buttons.append(String.format(Locale.ROOT,
          "<button class=\"tab%s\" onclick=\"show(%d)\">%s</button>",
          first ? " active" : "", i, label));
      // First frame loads immediately (src set); the rest lazy-load from data-src on first activation.
      frames.append(String.format(Locale.ROOT,
          "<iframe class=\"frame%s\" data-src=\"%s\"%s></iframe>",
          first ? " active" : "", url, first ? " src=\"" + url + "\"" : ""));
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
