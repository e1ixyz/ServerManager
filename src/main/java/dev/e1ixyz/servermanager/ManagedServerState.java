package dev.e1ixyz.servermanager;

import java.nio.file.Path;

public record ManagedServerState(
    String name,
    Path workingDir,
    boolean running,
    boolean ready,
    boolean holdActive,
    long holdRemainingSeconds,
    boolean primary,
    boolean startOnJoin
) {
}
