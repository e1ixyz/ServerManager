package dev.e1ixyz.servermanager;

import java.util.UUID;

public interface ManagedLifecycleListener {
  default void onServerReady(String server) {
  }

  default void onPlayerDelivered(UUID playerUuid, String server) {
  }
}
