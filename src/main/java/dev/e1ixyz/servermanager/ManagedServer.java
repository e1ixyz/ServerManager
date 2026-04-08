package dev.e1ixyz.servermanager;

import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ManagedServer {
  private final String name;
  private volatile ServerConfig cfg;
  private final Logger log;

  private volatile Process process;
  private volatile boolean starting;
  private volatile long lastStartMs = 0L;
  private volatile long lifecycleGeneration = 0L;
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
      long generation = ++lifecycleGeneration;
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
        openExternalConsoleWindow(snapshot, started.pid(), generation, logOutputFile, commandSpoolFile);
      }
      Thread watcher = new Thread(() -> {
        try {
          started.waitFor();
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        } finally {
          closeExternalConsoleWindow(generation);
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
    Process current = process;
    long generation = lifecycleGeneration;
    if (!isRunning()) {
      closeExternalConsoleWindow(generation);
      return;
    }
    try {
      OutputStream os = current.getOutputStream();
      ServerConfig snapshot = this.cfg;
      os.write((snapshot.stopCommand + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      os.flush();
      log.info("[{}] sent stop command", name);

      if (!current.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
        log.warn("[{}] did not exit in time; destroying forcibly", name);
        current.destroyForcibly();
      }
    } catch (Exception ex) {
      log.error("[{}] error during stop", name, ex);
      if (current != null) current.destroyForcibly();
    } finally {
      if (process == current) {
        process = null;
      }
      starting = false;
      closeExternalConsoleWindow(generation);
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
      ServerConfig snapshot, long serverPid, long generation, File logOutputFile, File commandSpoolFile) {
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
      String titlePrefix = "SM-" + sanitizeFileToken(name);
      closeConsoleWindowsByTitlePrefix(titlePrefix);
      String title = titlePrefix + "-" + serverPid;
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
              "LOG=" + shQuote(logPath) + "; " +
              "PID=" + serverPid + "; " +
              "touch \"$LOG\"; " +
              commandInit +
              "tail -n 200 -F \"$LOG\" & TAIL_PID=$!; " +
              inputLoop +
              "kill \"$TAIL_PID\" >/dev/null 2>&1; " +
              "wait \"$TAIL_PID\" 2>/dev/null; " +
              "exit";
      String terminalCommand = "exec bash -lc " + shQuote(monitorScript);
      String appleScript =
          "tell application \"Terminal\"\n" +
              "activate\n" +
              "set t to do script \"" + escapeAppleScriptString(terminalCommand) + "\"\n" +
              "set tabTty to \"\"\n" +
              "repeat 10 times\n" +
              "delay 0.1\n" +
              "try\n" +
              "set custom title of t to \"" + escapeAppleScriptString(title) + "\"\n" +
              "end try\n" +
              "try\n" +
              "set tabTty to (tty of t as string)\n" +
              "if tabTty is not \"\" then exit repeat\n" +
              "end try\n" +
              "end repeat\n" +
              "return tabTty\n" +
              "end tell";

      AppleScriptResult result = runAppleScript(appleScript);
      if (result.exitCode != 0) {
        log.warn("[{}] failed to open Terminal console window: {}", name,
            result.stderr.isBlank() ? "osascript failed" : result.stderr);
        externalConsoleTty = null;
      } else {
        String tty = normalizeTty(result.stdout);
        if (tty == null || tty.isBlank()) {
          tty = resolveTerminalTtyByTitle(title);
        }
        if (generation == lifecycleGeneration) {
          externalConsoleTty = normalizeTty(tty);
        }
      }
    } catch (Exception ex) {
      log.warn("[{}] failed to open Terminal console window", name, ex);
    }
  }

  private synchronized void closeExternalConsoleWindow(long expectedGeneration) {
    if (expectedGeneration != 0L && lifecycleGeneration != expectedGeneration) {
      return;
    }
    String tty = externalConsoleTty;
    externalConsoleTty = null;
    String title = externalConsoleTitle;
    externalConsoleTitle = null;
    File commandFile = externalConsoleCommandFile;
    externalConsoleCommandFile = null;
    truncateCommandSpool(commandFile);
    if ((tty == null || tty.isBlank()) && (title == null || title.isBlank())) return;
    if (!isMacOs()) return;
    String titlePrefix = "SM-" + sanitizeFileToken(name);

    try {
      if ((tty == null || tty.isBlank()) && title != null && !title.isBlank()) {
        tty = resolveTerminalTtyByTitle(title);
      }
      if (tty != null && !tty.isBlank()) {
        terminateTerminalTty(tty);
        Thread.sleep(100L);
      }
      boolean closedByTitle = title != null && !title.isBlank() && closeConsoleWindowByTitle(title);
      boolean clearedByPrefix = closeConsoleWindowsByTitlePrefix(titlePrefix);
      if (!closedByTitle && !clearedByPrefix) terminateTerminalTty(tty);
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

  private String resolveTerminalTtyByTitle(String title) {
    if (!isMacOs() || title == null || title.isBlank()) return null;
    try {
      String script =
          "tell application \"Terminal\"\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "if custom title of t is \"" + escapeAppleScriptString(title) + "\" then\n" +
              "try\n" +
              "return tty of t as string\n" +
              "end try\n" +
              "end if\n" +
              "end repeat\n" +
              "end repeat\n" +
              "end tell";
      AppleScriptResult result = runAppleScript(script);
      if (result.exitCode != 0) {
        log.debug("[{}] failed to resolve console tty for '{}': {}", name, title,
            result.stderr.isBlank() ? "osascript failed" : result.stderr);
        return null;
      }
      return normalizeTty(result.stdout);
    } catch (Exception ex) {
      log.debug("[{}] failed to resolve console tty for '{}'", name, title, ex);
      return null;
    }
  }

  private boolean closeConsoleWindowByTitle(String title) {
    if (!isMacOs() || title == null || title.isBlank()) return false;
    try {
      String byTitleScript =
          "tell application \"Terminal\"\n" +
              "set targetTitle to \"" + escapeAppleScriptString(title) + "\"\n" +
              "set targetId to \"\"\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "if custom title of t is targetTitle then\n" +
              "try\n" +
              "set targetId to (id of w as string)\n" +
              "end try\n" +
              "exit repeat\n" +
              "end if\n" +
              "end repeat\n" +
              "if targetId is not \"\" then exit repeat\n" +
              "end repeat\n" +
              "if targetId is not \"\" then\n" +
              "try\n" +
              "close (first window whose id is (targetId as integer)) saving no\n" +
              "return \"closed\"\n" +
              "end try\n" +
              "end if\n" +
              "return \"none\"\n" +
              "end tell";
      AppleScriptResult result = runAppleScript(byTitleScript);
      if (result.exitCode != 0) {
        log.warn("[{}] failed to close console window by title '{}': {}", name, title,
            result.stderr.isBlank() ? "osascript failed" : result.stderr);
        return false;
      }
      if (!"closed".equalsIgnoreCase(result.stdout)) {
        return false;
      }
      Thread.sleep(75L);
      return !hasConsoleWindowWithTitle(title);
    } catch (Exception ex) {
      log.debug("[{}] failed to close console window by title '{}'", name, title, ex);
      return false;
    }
  }

  private boolean closeConsoleWindowsByTitlePrefix(String titlePrefix) {
    if (!isMacOs() || titlePrefix == null || titlePrefix.isBlank()) return false;
    try {
      List<String> titles = listConsoleTitlesByPrefix(titlePrefix);
      if (titles.isEmpty()) return false;
      for (String tty : listTerminalTtysByTitlePrefix(titlePrefix)) {
        terminateTerminalTty(tty);
      }
      Thread.sleep(100L);
      int passes = 0;
      while (!titles.isEmpty() && passes < 6) {
        for (String title : titles) {
          closeConsoleWindowByTitle(title);
          Thread.sleep(75L);
        }
        titles = listConsoleTitlesByPrefix(titlePrefix);
        passes++;
      }
      if (!titles.isEmpty()) {
        for (String tty : listTerminalTtysByTitlePrefix(titlePrefix)) {
          terminateTerminalTty(tty);
        }
        Thread.sleep(100L);
        passes = 0;
        while (!titles.isEmpty() && passes < 6) {
          for (String title : titles) {
            closeConsoleWindowByTitle(title);
            Thread.sleep(75L);
          }
          titles = listConsoleTitlesByPrefix(titlePrefix);
          passes++;
        }
      }
      return titles.isEmpty();
    } catch (Exception ex) {
      log.debug("[{}] stale console window cleanup failed", name, ex);
      return false;
    }
  }

  private List<String> listConsoleTitlesByPrefix(String titlePrefix) {
    if (!isMacOs() || titlePrefix == null || titlePrefix.isBlank()) return List.of();
    try {
      String script =
          "tell application \"Terminal\"\n" +
              "set targetPrefix to \"" + escapeAppleScriptString(titlePrefix) + "\"\n" +
              "set out to \"\"\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "set ct to \"\"\n" +
              "try\n" +
              "set ct to (custom title of t as string)\n" +
              "end try\n" +
              "if ct starts with targetPrefix then set out to out & ct & linefeed\n" +
              "end repeat\n" +
              "end repeat\n" +
              "return out\n" +
              "end tell";
      AppleScriptResult result = runAppleScript(script);
      if (result.exitCode != 0) {
        log.warn("[{}] failed to inspect console window titles for prefix '{}': {}", name, titlePrefix,
            result.stderr.isBlank() ? "osascript failed" : result.stderr);
        return List.of();
      }
      LinkedHashSet<String> titles = new LinkedHashSet<>();
      for (String line : result.stdout.split("\\R")) {
        String title = line == null ? null : line.trim();
        if (title != null && !title.isEmpty()) titles.add(title);
      }
      return new ArrayList<>(titles);
    } catch (Exception ex) {
      log.debug("[{}] failed to inspect console window titles for prefix '{}'", name, titlePrefix, ex);
      return List.of();
    }
  }

  private boolean hasConsoleWindowWithTitle(String title) {
    if (!isMacOs() || title == null || title.isBlank()) return false;
    try {
      String script =
          "tell application \"Terminal\"\n" +
              "set targetTitle to \"" + escapeAppleScriptString(title) + "\"\n" +
              "set matches to 0\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "set ct to \"\"\n" +
              "try\n" +
              "set ct to (custom title of t as string)\n" +
              "end try\n" +
              "if ct is targetTitle then set matches to matches + 1\n" +
              "end repeat\n" +
              "end repeat\n" +
              "return matches\n" +
              "end tell";
      AppleScriptResult result = runAppleScript(script);
      if (result.exitCode != 0) {
        log.warn("[{}] failed to inspect console window title '{}': {}", name, title,
            result.stderr.isBlank() ? "osascript failed" : result.stderr);
        return false;
      }
      return !result.stdout.isBlank() && !"0".equals(result.stdout);
    } catch (Exception ex) {
      log.debug("[{}] failed to inspect console window title '{}'", name, title, ex);
      return false;
    }
  }

  private List<String> listTerminalTtysByTitlePrefix(String titlePrefix) {
    if (!isMacOs() || titlePrefix == null || titlePrefix.isBlank()) return List.of();
    try {
      String ttysScript =
          "tell application \"Terminal\"\n" +
              "set targetPrefix to \"" + escapeAppleScriptString(titlePrefix) + "\"\n" +
              "set out to \"\"\n" +
              "repeat with w in windows\n" +
              "repeat with t in tabs of w\n" +
              "set ct to \"\"\n" +
              "try\n" +
              "set ct to (custom title of t as string)\n" +
              "end try\n" +
              "if ct starts with targetPrefix then\n" +
              "try\n" +
              "set out to out & (tty of t as string) & linefeed\n" +
              "end try\n" +
              "end if\n" +
              "end repeat\n" +
              "end repeat\n" +
              "return out\n" +
              "end tell";
      AppleScriptResult result = runAppleScript(ttysScript);
      if (result.exitCode != 0) {
        log.warn("[{}] failed to inspect console windows with prefix '{}': {}", name, titlePrefix,
            result.stderr.isBlank() ? "osascript failed" : result.stderr);
        return List.of();
      }
      LinkedHashSet<String> ttys = new LinkedHashSet<>();
      for (String line : result.stdout.split("\\R")) {
        String tty = normalizeTty(line);
        if (tty != null) ttys.add(tty);
      }
      return new ArrayList<>(ttys);
    } catch (Exception ex) {
      log.debug("[{}] failed to inspect console windows with prefix '{}'", name, titlePrefix, ex);
      return List.of();
    }
  }

  private AppleScriptResult runAppleScript(String script) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("osascript", "-e", script).start();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    int exitCode = process.waitFor();
    return new AppleScriptResult(exitCode, stdout, stderr);
  }

  private static final class AppleScriptResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private AppleScriptResult(int exitCode, String stdout, String stderr) {
      this.exitCode = exitCode;
      this.stdout = stdout;
      this.stderr = stderr;
    }
  }

  private void terminateTerminalTty(String tty) {
    if (!isMacOs()) return;
    String normalized = normalizeTty(tty);
    if (normalized == null || normalized.isBlank()) return;
    String shortTty = normalized.startsWith("/dev/") ? normalized.substring(5) : normalized;
    try {
      List<Long> pids = listTerminalProcessIds(shortTty);
      if (pids.isEmpty()) return;
      for (Long pid : pids) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
      }
      Thread.sleep(250L);

      List<Long> survivors = listTerminalProcessIds(shortTty);
      for (Long pid : survivors) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
      }
      Thread.sleep(250L);
    } catch (Exception ex) {
      log.debug("[{}] failed to terminate tty {}", name, shortTty, ex);
    }
  }

  private List<Long> listTerminalProcessIds(String shortTty) {
    if (shortTty == null || shortTty.isBlank()) return List.of();
    try {
      Process process = new ProcessBuilder("ps", "-t", shortTty, "-o", "pid=").start();
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      boolean finished = process.waitFor(2, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return List.of();
      }
      int exit = process.exitValue();
      if (exit != 0) {
        if (!stderr.isBlank() && !stderr.contains("No such file or directory")) {
          log.debug("[{}] failed to inspect tty {}: {}", name, shortTty, stderr);
        }
        return List.of();
      }
      List<Long> pids = new ArrayList<>();
      for (String line : stdout.split("\\R")) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) continue;
        try {
          pids.add(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
        }
      }
      return pids;
    } catch (Exception ex) {
      log.debug("[{}] failed to inspect tty {}", name, shortTty, ex);
      return List.of();
    }
  }

  private static String normalizeTty(String tty) {
    if (tty == null) return null;
    String trimmed = tty.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.startsWith("/dev/")) return trimmed;
    if (trimmed.startsWith("ttys") || trimmed.startsWith("tty")) return "/dev/" + trimmed;
    return trimmed;
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
