package me.srrapero720.dimthread.mixin.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import me.srrapero720.dimthread.DimConfig;
import me.srrapero720.dimthread.DimThread;
import me.srrapero720.dimthread.thread.ThreadPool;
import me.srrapero720.dimthread.util.CrashInfo;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Shadow protected int tickCount;
    @Shadow @Final protected net.minecraft.server.players.PlayerList playerList;
    @Shadow public abstract Iterable<ServerLevel> getAllLevels();
    
    // Deadlock detection interval (check every 10 seconds)
    private static final long DEADLOCK_CHECK_INTERVAL = 10000;
    private long dimthreads$lastDeadlockCheck = 0;
    
    // Store initial exceptions for crash handling
    private final AtomicBoolean dimthreads$initialException = new AtomicBoolean(false);

    private MinecraftServer self() {
        return (MinecraftServer) (Object) this;
    }

    // Fix the injection point to target a more specific method that exists
    @Inject(method = "tickServer",
            at = @At(value = "INVOKE", 
                    target = "Lnet/minecraft/server/MinecraftServer;tickChildren(Ljava/util/function/BooleanSupplier;)V"),
            cancellable = true)
    private void skipLevelTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // Don't cancel if not active - just let vanilla handle it
        if (!DimThread.MANAGER.isActive(self())) {
            return;
        }
        
        // We'll manually tick the worlds using our thread system
        tickWorlds(hasTimeLeft);
        
        // Skip vanilla world ticking by cancelling
        ci.cancel();
    }

    /**
     * Handles world ticking with thread optimization
     */
    private void tickWorlds(BooleanSupplier shouldKeepTicking) {
        // Periodically check for deadlocks
        long currentTime = System.currentTimeMillis();
        if (currentTime - dimthreads$lastDeadlockCheck > DEADLOCK_CHECK_INTERVAL) {
            dimthreads$lastDeadlockCheck = currentTime;
            if (DimThread.MANAGER.detectAndHandleDeadlocks(self())) {
                DimThread.LOGGER.info("Deadlock detection ran and resolved issues. Continuing server operation.");
                return; // Skip this tick as we've just restarted the thread pool
            }
        }

        AtomicReference<CrashInfo> crash = new AtomicReference<>();
        ThreadPool pool = DimThread.getThreadPool(self());

        // Create a list to hold dimensions that should be processed on the main thread
        List<ServerLevel> mainThreadWorlds = new ArrayList<>();
        
        // Process all dimensions, checking blacklist
        for (ServerLevel level : this.getAllLevels()) {
            if (DimThread.shouldProcessDimensionOnWorker(level)) {
                // Process dimension in worker thread
                pool.execute(() -> {
                    DimThread.attach(Thread.currentThread(), level);

                    if (this.tickCount % 20 == 0) {
                        ClientboundSetTimePacket timeUpdatePacket = new ClientboundSetTimePacket(
                            level.getGameTime(), level.getDayTime(),
                            level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));

                        this.playerList.broadcastAll(timeUpdatePacket, level.dimension());
                    }

                    DimThread.swapThreadsAndRun(() -> {
                        ForgeEventFactory.onPreLevelTick(level, shouldKeepTicking);
                        try {
                            level.tick(shouldKeepTicking);
                        } catch (Throwable throwable) {
                            crash.set(new CrashInfo(level, throwable));
                        }
                        ForgeEventFactory.onPostLevelTick(level, shouldKeepTicking);
                    }, level, level.getChunkSource());
                });
            } else {
                // Add to list for main thread processing
                mainThreadWorlds.add(level);
            }
        }
        
        // Wait for all worker threads to complete
        pool.awaitCompletion();
        
        // Process blacklisted dimensions on the main thread
        for (ServerLevel level : mainThreadWorlds) {
            DimThread.LOGGER.debug("Processing blacklisted dimension {} on main thread", level.dimension().location().getPath());
            if (this.tickCount % 20 == 0) {
                ClientboundSetTimePacket timeUpdatePacket = new ClientboundSetTimePacket(
                    level.getGameTime(), level.getDayTime(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
                this.playerList.broadcastAll(timeUpdatePacket, level.dimension());
            }
            
            ForgeEventFactory.onPreLevelTick(level, shouldKeepTicking);
            try {
                level.tick(shouldKeepTicking);
            } catch (Throwable throwable) {
                crash.set(new CrashInfo(level, throwable));
            }
            ForgeEventFactory.onPostLevelTick(level, shouldKeepTicking);
        }

        if (crash.get() != null) {
            if (DimConfig.IGNORE_TICK_CRASH.get() && !dimthreads$initialException.getAndSet(true)) {
                crash.get().report("Exception ticking world (asynchronously) -> EFFECTIVELY IGNORED");
            } else {
                crash.get().crash("Exception ticking world (asynchronously)");
            }
        }
    }

    /**
     * Shutdown all threadpools when the server stops.
     * Prevent server hang when stopping the server.
     */
    @Inject(method = "stopServer", at = @At("HEAD"))
    public void shutdownThreadpool(CallbackInfo ci) {
        // Make sure to clean up all thread resources
        DimThread.LOGGER.info("Server stopping, shutting down all dimension threads");
        DimThread.MANAGER.threadPools.forEach((server, pool) -> pool.shutdown());
        DimThread.MANAGER.clear();
    }
}