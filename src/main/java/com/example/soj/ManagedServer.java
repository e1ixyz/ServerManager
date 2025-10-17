package com.example.soj;

import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class ManagedServer {
  private final String name;
  private volatile ServerConfig cfg;
  private final Logger log;

  private volatile Process process;
  private volatile boolean starting;
  private volatile long lastStartMs = 0L;

  ManagedServer(String name, ServerConfig cfg, Logger log) {
    this.name = name;
    this.cfg = cfg;
    this.log = log;
  }

  boolean isRunning() {
    return process != null && process.isAlive();
  }

  boolean recentlyStarted(long windowMs) {
    return isRunning() && lastStartMs > 0 && (System.currentTimeMillis() - lastStartMs) < windowMs;
  }

  synchronized void start() throws IOException {
    if (isRunning() || starting) {
      log.info("[{}] already running/starting", name);
      return;
    }
    ServerConfig snapshot = this.cfg;

    if (snapshot.workingDir == null || snapshot.startCommand == null)
      throw new IllegalStateException("workingDir and startCommand required for " + name);

    starting = true;
    try {
      ProcessBuilder pb = shellCommand(snapshot.startCommand);
      pb.directory(new File(snapshot.workingDir));
      pb.redirectErrorStream(true);

      if (Boolean.TRUE.equals(snapshot.logToFile)) {
        File out = (snapshot.logFile != null && !snapshot.logFile.isBlank())
            ? new File(snapshot.logFile)
            : new File("proxy-managed.log");
        if (!out.isAbsolute()) out = new File(snapshot.workingDir, out.getPath());
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        pb.redirectOutput(out); // Send all output to file
        log.info("[{}] logging to {}", name, out.getPath().replace('\\','/'));
      }

      Process started = pb.start();
      process = started;
      lastStartMs = System.currentTimeMillis();

      // If not logging to file, stream to Velocity logger as before
      if (!Boolean.TRUE.equals(snapshot.logToFile)) {
        Thread t = new Thread(() -> {
          try (BufferedReader br = new BufferedReader(
              new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
              log.info("[{}] {}", name, line);
            }
          } catch (IOException ignored) {}
        }, "ms-log-" + name);
        t.setDaemon(true);
        t.start();
      }

      log.info("[{}] started (pid? {})", name, started.pid());
    } finally {
      starting = false;
    }
  }

  synchronized void stopGracefully() {
    if (!isRunning()) return;
    try {
      OutputStream os = process.getOutputStream();
      ServerConfig snapshot = this.cfg;
      os.write((snapshot.stopCommand + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      os.flush();
      log.info("[{}] sent stop command", name);

      if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
        log.warn("[{}] did not exit in time; destroying forcibly", name);
        process.destroyForcibly();
      }
    } catch (Exception ex) {
      log.error("[{}] error during stop", name, ex);
      process.destroyForcibly();
    } finally {
      process = null;
      starting = false;
    }
  }

  synchronized boolean sendCommand(String command) {
    if (!isRunning()) return false;
    try {
      OutputStream os = process.getOutputStream();
      os.write((command + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      os.flush();
      return true;
    } catch (IOException ex) {
      log.warn("[{}] failed to dispatch command '{}'", name, command, ex);
      return false;
    }
  }

  synchronized void updateConfig(ServerConfig newCfg) {
    this.cfg = newCfg;
  }

  private static ProcessBuilder shellCommand(String cmd) {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      return new ProcessBuilder("cmd.exe", "/c", cmd);
    } else {
      return new ProcessBuilder("sh", "-lc", cmd);
    }
  }
}
