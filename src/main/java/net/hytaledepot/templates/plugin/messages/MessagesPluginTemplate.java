package net.hytaledepot.templates.plugin.messages;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MessagesPluginTemplate extends JavaPlugin {
  private final MessagesPluginState state = new MessagesPluginState();
  private final MessagesDemoService demoService = new MessagesDemoService();
  private final AtomicLong heartbeatTicks = new AtomicLong();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hd-messages-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile long startedAtEpochMillis;

  public MessagesPluginTemplate(JavaPluginInit init) {
    super(init);
  }

  @Override
  public CompletableFuture<Void> preLoad() {
    state.setLifecycle(MessagesPluginLifecycle.PRELOADING);
    getLogger().atInfo().log("[Messages] preLoad -> %s", getIdentifier());
    return CompletableFuture.completedFuture(null);
  }

  @Override
  protected void setup() {
    state.setLifecycle(MessagesPluginLifecycle.SETTING_UP);
    state.setTemplateName("Messages");
    state.setDataDirectory(getDataDirectory().toString());

    demoService.initialize(getDataDirectory());
    state.markSetupCompleted();

    getCommandRegistry().registerCommand(new MessagesStatusCommand(state, demoService, heartbeatTicks, this::uptimeSeconds, this::isHeartbeatActive));
    getCommandRegistry().registerCommand(new MessagesDemoCommand(state, demoService, heartbeatTicks));

    state.setLifecycle(MessagesPluginLifecycle.READY);
  }

  @Override
  protected void start() {
    state.setLifecycle(MessagesPluginLifecycle.RUNNING);
    startedAtEpochMillis = System.currentTimeMillis();

    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                long tick = heartbeatTicks.incrementAndGet();
                demoService.onHeartbeat(tick);
                if (tick % 60 == 0) {
                  getLogger().atInfo().log("[Messages] heartbeat=%d", tick);
                }
              } catch (Exception exception) {
                state.incrementErrorCount();
                getLogger().atInfo().log("[Messages] heartbeat task failed: %s", exception.getMessage());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);

    getTaskRegistry().registerTask(CompletableFuture.completedFuture(null));
  }

  @Override
  protected void shutdown() {
    state.setLifecycle(MessagesPluginLifecycle.STOPPING);

    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }

    scheduler.shutdownNow();
    demoService.shutdown();
    state.setLifecycle(MessagesPluginLifecycle.STOPPED);
  }

  private long uptimeSeconds() {
    if (startedAtEpochMillis <= 0L) {
      return 0L;
    }
    return Math.max(0L, (System.currentTimeMillis() - startedAtEpochMillis) / 1000L);
  }

  private boolean isHeartbeatActive() {
    return heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone();
  }
}
