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

  /**
   * When true, players present on this backend's vanilla whitelist are allowed to bypass the
   * network whitelist. If unset, legacy behavior is used (only the primary server follows the
   * global allowVanillaBypass flag).
   */
  public Boolean vanillaWhitelistBypassesNetwork;
  /** When true, players added to the network whitelist are mirrored into this backend's whitelist. */
  public Boolean mirrorNetworkWhitelist;
}
