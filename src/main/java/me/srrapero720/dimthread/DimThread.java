package me.srrapero720.dimthread;

import me.srrapero720.dimthread.init.ModGameRules;
import me.srrapero720.dimthread.thread.DimThreadRegistry;
import me.srrapero720.dimthread.thread.IMutableMainThread;
import me.srrapero720.dimthread.thread.ThreadPool;
import me.srrapero720.dimthread.util.ServerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(DimThread.MOD_ID)
@Mod.EventBusSubscriber(modid = DimThread.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class DimThread {
    public static final String MOD_ID = "dimthread";
    public static final ServerManager MANAGER = new ServerManager();
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public DimThread() {
        DimConfig.register();
    }

    @SubscribeEvent
    public static void onCommonSetupEvent(FMLCommonSetupEvent e) {
        ModGameRules.register();
    }

    public static ThreadPool getThreadPool(MinecraftServer server) {
        return MANAGER.getThreadPool(server);
    }

    public static boolean isModPresent(String modid) {
        return FMLLoader.getLoadingModList().getModFileById(modid) != null;
    }

    /**
     * Check if a dimension should be processed on a separate thread
     */
    public static boolean shouldProcessDimensionOnWorker(ServerLevel world) {
        if (world == null) return false;
        String dimensionPath = world.dimension().location().getPath();
        return !DimThreadRegistry.isDimensionBlacklisted(dimensionPath);
    }

    /**
     * Swaps the main thread of the given objects to the current thread, runs the given task,
     * and swaps the main thread back to the original thread.
     * 
     * Now with improved error handling and dimension blacklisting.
     */
    public static void swapThreadsAndRun(Runnable task, Object... threadedObjects) {
        Thread currentThread = Thread.currentThread();
        Thread[] oldThreads = new Thread[threadedObjects.length];
        
        // Extract dimension ID if this is a ServerLevel for tracking purposes
        String dimensionId = null;
        for (Object obj : threadedObjects) {
            if (obj instanceof ServerLevel) {
                dimensionId = ((ServerLevel)obj).dimension().location().getPath();
                // If dimension is blacklisted, run natively and return immediately
                if (DimThreadRegistry.isDimensionBlacklisted(dimensionId)) {
                    LOGGER.debug("Running blacklisted dimension {} on main thread", dimensionId);
                    task.run();
                    return;
                }
                break;
            }
        }

        // Store original threads and swap to current thread
        try {
            for (int i = 0; i < oldThreads.length; i++) {
                if (threadedObjects[i] instanceof IMutableMainThread) {
                    oldThreads[i] = ((IMutableMainThread) threadedObjects[i]).dimThreads$getMainThread();
                    ((IMutableMainThread) threadedObjects[i]).dimThreads$setMainThread(currentThread);
                } else {
                    LOGGER.warn("Object {} doesn't implement IMutableMainThread", threadedObjects[i].getClass().getName());
                    oldThreads[i] = null;
                }
            }

            // Update activity timestamp before running the task
            DimThreadRegistry.updateActivity(currentThread);
            
            // Create final variable for the watchdog thread
            final String finalDimensionId = dimensionId;
            final long startTime = System.currentTimeMillis();
            final long TIMEOUT = 10000; // 10-second timeout for individual tasks
            
            // Run the task with a watchdog to detect hung tasks
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(TIMEOUT);
                    if (!Thread.currentThread().isInterrupted()) {
                        LOGGER.warn("Task in thread {} is taking too long ({} ms). Interrupting thread.",
                            currentThread.getName(), TIMEOUT);
                        currentThread.interrupt();
                        
                        // Blacklist the dimension if repeatedly causing problems
                        if (finalDimensionId != null) {
                            if (DimThreadRegistry.recordDimensionFailure(finalDimensionId)) {
                                LOGGER.error("Dimension {} has been blacklisted due to repeated timeouts", finalDimensionId);
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    // Normal completion
                }
            }, "DimThread-Watchdog-" + currentThread.getId());
            watchdog.setDaemon(true);
            watchdog.start();
            
            try {
                task.run();
            } finally {
                // Stop the watchdog
                watchdog.interrupt();
                try {
                    watchdog.join(100); // Wait up to 100ms for watchdog to terminate
                } catch (InterruptedException ignored) {}
            }
            
            // Update activity timestamp again after task completion
            DimThreadRegistry.updateActivity(currentThread);
        } catch (Exception e) {
            LOGGER.error("Error processing dimension task", e);
            // If we encounter an error, consider recording a failure for this dimension
            if (dimensionId != null) {
                DimThreadRegistry.recordDimensionFailure(dimensionId);
            }
        } finally {
            // Always restore original threads, even if an exception occurs
            for (int i = 0; i < oldThreads.length; i++) {
                if (oldThreads[i] != null && threadedObjects[i] instanceof IMutableMainThread) {
                    ((IMutableMainThread) threadedObjects[i]).dimThreads$setMainThread(oldThreads[i]);
                }
            }
        }
    }

    /**
     * Makes it easy to understand what is happening in crash reports and helps identify dimthread workers.
     */
    public static void attach(Thread thread, String name) {
        thread.setName(MOD_ID + "_server_" + name);
        DimThreadRegistry.register(thread);
    }

    public static void attach(Thread thread, ServerLevel world) {
        String dimensionPath = world.dimension().location().getPath();
        attach(thread, dimensionPath);
    }

    /**
     * Checks if the given thread is a dimthread worker.
     */
    public static boolean owns(Thread thread) {
        // First check the registry (reliable method)
        if (DimThreadRegistry.isWorker(thread)) {
            return true;
        }
        // Fallback to name check for backward compatibility
        return thread != null && thread.getName().startsWith(MOD_ID + "_server_");
    }
}