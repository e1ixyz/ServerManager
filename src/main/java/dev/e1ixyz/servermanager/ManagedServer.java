package dev.e1ixyz.servermanager;

import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class ManagedServer {
  private final String name;
  private volatile ServerConfig cfg;
  private final Logger log;

  private volatile Process process;
  private volatile boolean starting;
  private volatile long lastStartMs = 0L;
  private volatile Long externalConsoleTabId;

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
      File logOutputFile = null;

      if (Boolean.TRUE.equals(snapshot.logToFile)) {
        File out = (snapshot.logFile != null && !snapshot.logFile.isBlank())
            ? new File(snapshot.logFile)
            : new File("proxy-managed.log");
        if (!out.isAbsolute()) out = new File(snapshot.workingDir, out.getPath());
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        pb.redirectOutput(out); // Send all output to file
        logOutputFile = out;
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

      if (Boolean.TRUE.equals(snapshot.openConsoleWindow)) {
        openExternalConsoleWindow(snapshot, started.pid(), logOutputFile);
      }
      Thread watcher = new Thread(() -> {
        try {
          started.waitFor();
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        } finally {
          closeExternalConsoleWindow();
        }
      }, "ms-exit-" + name);
      watcher.setDaemon(true);
      watcher.start();

      log.info("[{}] started (pid? {})", name, started.pid());
    } finally {
      starting = false;
    }
  }

  synchronized void stopGracefully() {
    if (!isRunning()) {
      closeExternalConsoleWindow();
      return;
    }
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
      closeExternalConsoleWindow();
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

  private synchronized void openExternalConsoleWindow(ServerConfig snapshot, long serverPid, File logOutputFile) {
    closeExternalConsoleWindow();

    if (!isMacOs()) {
      log.warn("[{}] openConsoleWindow is enabled but only supported on macOS.", name);
      return;
    }
    if (!Boolean.TRUE.equals(snapshot.logToFile)) {
      log.warn("[{}] openConsoleWindow requires logToFile=true.", name);
      return;
    }
    if (logOutputFile == null) {
      log.warn("[{}] openConsoleWindow could not resolve log file.", name);
      return;
    }

    try {
      String logPath = logOutputFile.getAbsolutePath();
      String title = "SM-" + name.replace("'", "");
      String monitorScript =
          "printf '\\033]0;" + title + "\\007'; " +
              "LOG=" + shQuote(logPath) + "; " +
              "PID=" + serverPid + "; " +
              "touch \"$LOG\"; " +
              "tail -n 200 -f \"$LOG\" & TAIL_PID=$!; " +
              "while kill -0 \"$PID\" 2>/dev/null; do sleep 1; done; " +
              "kill \"$TAIL_PID\" >/dev/null 2>&1; " +
              "wait \"$TAIL_PID\" 2>/dev/null; " +
              "exit";
      String terminalCommand = "bash -lc " + shQuote(monitorScript);
      String appleScript =
          "tell application \"Terminal\"\n" +
              "activate\n" +
              "set t to do script \"" + escapeAppleScriptString(terminalCommand) + "\"\n" +
              "delay 0.1\n" +
              "return id of t\n" +
              "end tell";

      Process p = new ProcessBuilder("osascript", "-e", appleScript).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      int exit = p.waitFor();
      if (exit != 0) {
        log.warn("[{}] failed to open Terminal console window: {}", name, err.isBlank() ? "osascript failed" : err);
        return;
      }
      if (!out.isBlank()) {
        try {
          externalConsoleTabId = Long.parseLong(out);
        } catch (NumberFormatException ignored) {
          externalConsoleTabId = null;
        }
      }
    } catch (Exception ex) {
      log.warn("[{}] failed to open Terminal console window", name, ex);
    }
  }

  private synchronized void closeExternalConsoleWindow() {
    Long tabId = externalConsoleTabId;
    externalConsoleTabId = null;
    if (tabId == null) return;
    if (!isMacOs()) return;

    try {
      String appleScript =
          "tell application \"Terminal\"\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "if id of t is " + tabId + " then\n" +
              "close t\n" +
              "return\n" +
              "end if\n" +
              "end repeat\n" +
              "end repeat\n" +
              "end tell";
      Process p = new ProcessBuilder("osascript", "-e", appleScript).start();
      p.waitFor(2, TimeUnit.SECONDS);
    } catch (Exception ex) {
      log.debug("[{}] failed to close Terminal console window", name, ex);
    }
  }

  private static boolean isMacOs() {
    return System.getProperty("os.name").toLowerCase().contains("mac");
  }

  private static String shQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static String escapeAppleScriptString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
