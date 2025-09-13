package com.example.soj;

public final class ServerConfig {
  public Boolean startOnJoin = false;
  public String workingDir;
  public String startCommand;
  public String stopCommand = "stop";

  /** If true, write server stdout/stderr to a file instead of Velocity console. */
  public Boolean logToFile = true;
  /** Where to write logs if logToFile is true. Relative paths are resolved against workingDir. */
  public String logFile = "logs/proxy-managed.log";
}
