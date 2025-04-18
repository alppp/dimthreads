package me.srrapero720.dimthread.util;

import me.srrapero720.dimthread.DimThread;
import me.srrapero720.dimthread.init.ModGameRules;
import me.srrapero720.dimthread.thread.DimThreadRegistry;
import me.srrapero720.dimthread.thread.ThreadPool;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;

public class ServerManager {
    public final Map<MinecraftServer, ThreadPool> threadPools = new HashMap<>();
    private final Map<MinecraftServer, Boolean> active = new HashMap<>();
    
    // Time in ms to consider a thread pool potentially deadlocked
    private static final long DEADLOCK_TIMEOUT = 30000; // 30 seconds

    public ThreadPool getThreadPool(MinecraftServer server) {
        return threadPools.computeIfAbsent(server, s ->
                new ThreadPool(s.getGameRules().getInt(ModGameRules.THREAD_COUNT.getKey())));
    }

    public void setActive(MinecraftServer server, boolean value) {
        if (server == null) return;

        if (value && !this.isActive(server)) {
            if (this.threadPools.containsKey(server)) {
                this.threadPools.get(server).restart();
            }
        } else if (!value && this.isActive(server)) {
            if (this.threadPools.containsKey(server)) {
                this.threadPools.get(server).shutdown();
            }
        }

        this.active.put(server, value);
    }

    public boolean isActive(MinecraftServer server) {
        return server != null && this.active.getOrDefault(server, false);
    }

    public void setThreadCount(MinecraftServer server, int value) {
        if (server == null) return;

        boolean oldActive = this.isActive(server);

        if (oldActive) {
            this.setActive(server, false);
        }

        this.threadPools.put(server, new ThreadPool(value));

        if (oldActive) {
            this.setActive(server, true);
        }
    }
    
    /**
     * Detects and handles potential deadlocks in dimension loading
     * @param server The Minecraft server instance
     * @return true if a deadlock was detected and handled
     */
    public boolean detectAndHandleDeadlocks(MinecraftServer server) {
        if (!isActive(server)) return false;
        
        ThreadPool pool = getThreadPool(server);
        
        // First check the registry for deadlocks
        boolean deadlocksDetected = DimThreadRegistry.detectAndClearDeadlocks();
        
        // Then check if threads have been active for too long
        if (pool.getActiveCount() > 0) {
            long currentTime = System.currentTimeMillis();
            
            // If we detect a potential deadlock, restart the thread pool
            if (pool.getLastActivityTime() + DEADLOCK_TIMEOUT < currentTime) {
                DimThread.LOGGER.warn("Potential deadlock detected in dimension loading. Thread pool has been inactive for {} ms. Restarting thread pool.", 
                    currentTime - pool.getLastActivityTime());
                    
                try {
                    // Give threads a chance to respond to interrupts
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                
                pool.shutdown();
                threadPools.put(server, new ThreadPool(server.getGameRules().getInt(ModGameRules.THREAD_COUNT.getKey())));
                return true;
            }
        }
        
        return deadlocksDetected;
    }

    public void clear() {
        this.threadPools.clear();
        this.active.clear();
    }
}