package dev.e1ixyz.servermanager;

import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

final class ManagedServer {
  private final String name;
  private volatile ServerConfig cfg;
  private final Logger log;

  private volatile Process process;
  private volatile boolean starting;
  private volatile long lastStartMs = 0L;
  private volatile Long externalConsoleWindowId;
  private volatile String externalConsoleTty;
  private volatile String externalConsoleTitle;
  private volatile File externalConsoleCommandFile;

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
      File commandSpoolFile = null;

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
        commandSpoolFile = resolveConsoleCommandFile(snapshot, logOutputFile);
        startExternalConsoleCommandPump(started, commandSpoolFile);
        openExternalConsoleWindow(snapshot, started.pid(), logOutputFile, commandSpoolFile);
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

  private synchronized File resolveConsoleCommandFile(ServerConfig snapshot, File logOutputFile) {
    if (!Boolean.TRUE.equals(snapshot.openConsoleWindow)) return null;
    if (snapshot == null || snapshot.workingDir == null) return null;
    if (logOutputFile == null) return null;
    try {
      File parent = logOutputFile.getParentFile();
      if (parent == null) parent = new File(snapshot.workingDir);
      if (!parent.exists()) parent.mkdirs();
      String token = sanitizeFileToken(name);
      File commandFile = new File(parent, "proxy-managed-" + token + "-stdin.txt");
      Files.writeString(
          commandFile.toPath(),
          "",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
      );
      return commandFile;
    } catch (Exception ex) {
      log.warn("[{}] failed to initialize console command spool", name, ex);
      return null;
    }
  }

  private void startExternalConsoleCommandPump(Process serverProcess, File commandSpoolFile) {
    if (commandSpoolFile == null) return;
    this.externalConsoleCommandFile = commandSpoolFile;
    Thread t = new Thread(() -> {
      long pointer = 0L;
      while (serverProcess.isAlive()) {
        try (RandomAccessFile raf = new RandomAccessFile(commandSpoolFile, "r")) {
          long length = raf.length();
          if (pointer > length) pointer = 0L;
          raf.seek(pointer);

          String raw;
          while ((raw = raf.readLine()) != null) {
            String command = decodeRandomAccessLine(raw).trim();
            if (!command.isEmpty()) {
              sendCommand(command);
            }
          }
          pointer = raf.getFilePointer();
        } catch (FileNotFoundException ignored) {
          pointer = 0L;
        } catch (Exception ex) {
          log.debug("[{}] console command bridge read failed", name, ex);
        }
        try {
          Thread.sleep(200L);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }, "ms-console-cmd-" + name);
    t.setDaemon(true);
    t.start();
  }

  private synchronized void openExternalConsoleWindow(
      ServerConfig snapshot, long serverPid, File logOutputFile, File commandSpoolFile) {
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
      String title = "SM-" + sanitizeFileToken(name);
      this.externalConsoleTitle = title;
      this.externalConsoleCommandFile = commandSpoolFile;
      String commandPath = commandSpoolFile == null ? null : commandSpoolFile.getAbsolutePath();
      String commandInit = (commandPath == null)
          ? ""
          : ("CMD=" + shQuote(commandPath) + "; touch \"$CMD\"; ");
      String inputLoop = (commandPath == null)
          ? "while kill -0 \"$PID\" 2>/dev/null; do sleep 1; done; "
          : "echo \"[ServerManager] Type backend commands and press Enter.\"; " +
            "while kill -0 \"$PID\" 2>/dev/null; do " +
            "if IFS= read -r -t 1 LINE; then " +
            "printf '%s\\n' \"$LINE\" >> \"$CMD\"; " +
            "fi; " +
            "done; ";
      String monitorScript =
          "printf '\\033]0;" + title + "\\007'; " +
              "TITLE=" + shQuote(title) + "; " +
              "LOG=" + shQuote(logPath) + "; " +
              "PID=" + serverPid + "; " +
              "SELF_TTY=$(tty 2>/dev/null || true); " +
              "SELF_TTY_SHORT=${SELF_TTY##*/}; " +
              "touch \"$LOG\"; " +
              commandInit +
              "tail -n 200 -f \"$LOG\" & TAIL_PID=$!; " +
              inputLoop +
              "kill \"$TAIL_PID\" >/dev/null 2>&1; " +
              "wait \"$TAIL_PID\" 2>/dev/null; " +
              "if [ -n \"$SELF_TTY\" ]; then " +
              "osascript -e \"tell application \\\"Terminal\\\" to close (every tab of every window whose tty is \\\"$SELF_TTY\\\") saving no\" >/dev/null 2>&1 || true; " +
              "osascript -e \"tell application \\\"Terminal\\\" to close (every tab of every window whose tty is \\\"$SELF_TTY_SHORT\\\") saving no\" >/dev/null 2>&1 || true; " +
              "fi; " +
              "osascript -e 'tell application \"Terminal\" to close (every tab of every window whose custom title is \"'\"$TITLE\"'\") saving no' >/dev/null 2>&1 || true; " +
              "exit";
      String terminalCommand = "bash -lc " + shQuote(monitorScript);
      String appleScript =
          "tell application \"Terminal\"\n" +
              "activate\n" +
              "do script \"" + escapeAppleScriptString(terminalCommand) + "\"\n" +
              "delay 0.2\n" +
              "set w to front window\n" +
              "try\n" +
              "set custom title of selected tab of w to \"" + escapeAppleScriptString(title) + "\"\n" +
              "end try\n" +
              "return id of w as string\n" +
              "end tell";

      Process p = new ProcessBuilder("osascript", "-e", appleScript).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      int exit = p.waitFor();
      if (exit != 0) {
        log.warn("[{}] failed to open Terminal console window: {}", name, err.isBlank() ? "osascript failed" : err);
        externalConsoleTitle = null;
        externalConsoleWindowId = null;
        externalConsoleTty = null;
      } else {
        if (!out.isBlank()) {
          try {
            externalConsoleWindowId = Long.parseLong(out);
          } catch (NumberFormatException ignored) {
            externalConsoleWindowId = null;
          }
        } else {
          externalConsoleWindowId = null;
        }
        externalConsoleTty = null;
      }
    } catch (Exception ex) {
      log.warn("[{}] failed to open Terminal console window", name, ex);
    }
  }

  private synchronized void closeExternalConsoleWindow() {
    Long windowId = externalConsoleWindowId;
    externalConsoleWindowId = null;
    String tty = externalConsoleTty;
    externalConsoleTty = null;
    String title = externalConsoleTitle;
    externalConsoleTitle = null;
    File commandFile = externalConsoleCommandFile;
    externalConsoleCommandFile = null;
    truncateCommandSpool(commandFile);
    if ((windowId == null) && (tty == null || tty.isBlank()) && (title == null || title.isBlank())) return;
    if (!isMacOs()) return;

    try {
      if (windowId != null) {
        String byWindowScript =
            "tell application \"Terminal\"\n" +
                "repeat with w in windows\n" +
                "if id of w is " + windowId + " then\n" +
                "close w saving no\n" +
                "return\n" +
                "end if\n" +
                "end repeat\n" +
                "end tell";
        Process p = new ProcessBuilder("osascript", "-e", byWindowScript).start();
        int exit = p.waitFor();
        if (exit != 0) {
          String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
          log.warn("[{}] failed to close console window {}: {}", name, windowId, err.isBlank() ? "osascript failed" : err);
        }
      }
      if (tty != null && !tty.isBlank()) {
        String byTtyScript =
            "tell application \"Terminal\"\n" +
                "repeat with w in windows\n" +
                "repeat with t in tabs of w\n" +
                "if (tty of t as text) is \"" + escapeAppleScriptString(tty) + "\" then\n" +
                "close t saving no\n" +
                "return\n" +
                "end if\n" +
                "end repeat\n" +
                "end repeat\n" +
                "end tell";
        Process p = new ProcessBuilder("osascript", "-e", byTtyScript).start();
        int exit = p.waitFor();
        if (exit != 0) {
          String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
          log.warn("[{}] failed to close console tab by tty {}: {}", name, tty, err.isBlank() ? "osascript failed" : err);
        }
      }
      if (title == null || title.isBlank()) return;
      String byTitleScript =
          "tell application \"Terminal\"\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "if custom title of t is \"" + escapeAppleScriptString(title) + "\" then\n" +
              "try\n" +
              "do script \"exit\" in t\n" +
              "end try\n" +
              "close t saving no\n" +
              "end if\n" +
              "end repeat\n" +
              "end repeat\n" +
              "end tell";
      Process p = new ProcessBuilder("osascript", "-e", byTitleScript).start();
      int exit = p.waitFor();
      if (exit != 0) {
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        log.warn("[{}] failed to close console tab by title '{}': {}", name, title, err.isBlank() ? "osascript failed" : err);
      }
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

  private static String sanitizeFileToken(String value) {
    return value == null ? "server" : value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String decodeRandomAccessLine(String raw) {
    return new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
  }

  private static void truncateCommandSpool(File commandFile) {
    if (commandFile == null) return;
    try {
      Files.writeString(
          commandFile.toPath(),
          "",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
      );
    } catch (Exception ignored) {
    }
  }

  private static String escapeAppleScriptString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
