package me.srrapero720.dimthread.thread;

import me.srrapero720.dimthread.DimThread;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static me.srrapero720.dimthread.DimThread.MOD_ID;

/**
 * Registry to keep track of all DimThreads worker threads.
 * Now includes tracking of active threads and their task start times to detect deadlocks.
 */
public class DimThreadRegistry {
    private static final Set<Thread> WORKER_THREADS = 
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // Track when each thread started its current task
    private static final Map<Thread, AtomicLong> THREAD_ACTIVITY_TIMESTAMPS = 
        new ConcurrentHashMap<>();
    
    // Default timeout for task execution (30 seconds)
    private static final long DEFAULT_TASK_TIMEOUT = TimeUnit.SECONDS.toMillis(30);
    
    // Track problematic dimensions and their failure counts
    private static final Map<String, AtomicInteger> DIMENSION_FAILURE_COUNTS = 
        new ConcurrentHashMap<>();
    private static final Set<String> BLACKLISTED_DIMENSIONS = 
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // How many failures before we blacklist a dimension
    private static final int MAX_FAILURES_BEFORE_BLACKLIST = 3;
    
    /**
     * Register a thread as a DimThread worker
     */
    public static void register(Thread thread) {
        WORKER_THREADS.add(thread);
        THREAD_ACTIVITY_TIMESTAMPS.put(thread, new AtomicLong(System.currentTimeMillis()));
    }
    
    /**
     * Unregister a thread from being a DimThread worker
     */
    public static void unregister(Thread thread) {
        WORKER_THREADS.remove(thread);
        THREAD_ACTIVITY_TIMESTAMPS.remove(thread);
    }
    
    /**
     * Check if the given thread is a DimThread worker
     */
    public static boolean isWorker(Thread thread) {
        return WORKER_THREADS.contains(thread);
    }
    
    /**
     * Update the activity timestamp for a thread
     */
    public static void updateActivity(Thread thread) {
        AtomicLong timestamp = THREAD_ACTIVITY_TIMESTAMPS.get(thread);
        if (timestamp != null) {
            timestamp.set(System.currentTimeMillis());
        }
    }
    
    /**
     * Record a timeout failure for a dimension
     * @param dimensionId The dimension ID that had a timeout
     * @return true if the dimension is now blacklisted
     */
    public static boolean recordDimensionFailure(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return false;
        }
        
        // If already blacklisted, just return true
        if (BLACKLISTED_DIMENSIONS.contains(dimensionId)) {
            return true;
        }
        
        // Increment failure count
        AtomicInteger failures = DIMENSION_FAILURE_COUNTS.computeIfAbsent(
            dimensionId, k -> new AtomicInteger(0));
        int count = failures.incrementAndGet();
        
        DimThread.LOGGER.warn("Dimension {} has had {} timeout failures", dimensionId, count);
        
        // Check if we should blacklist
        if (count >= MAX_FAILURES_BEFORE_BLACKLIST) {
            BLACKLISTED_DIMENSIONS.add(dimensionId);
            DimThread.LOGGER.error("Dimension {} has been blacklisted due to repeated timeouts. " +
                "It will now run on the main server thread.", dimensionId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if a dimension is blacklisted
     * @param dimensionId The dimension ID to check
     * @return true if the dimension should not be processed in a separate thread
     */
    public static boolean isDimensionBlacklisted(String dimensionId) {
        return dimensionId != null && BLACKLISTED_DIMENSIONS.contains(dimensionId);
    }
    
    /**
     * Detect potential deadlocked threads (threads that haven't updated their activity in a while)
     * @return true if any potential deadlocks were detected and cleared
     */
    public static boolean detectAndClearDeadlocks() {
        boolean deadlockDetected = false;
        long now = System.currentTimeMillis();
        
        for (Map.Entry<Thread, AtomicLong> entry : THREAD_ACTIVITY_TIMESTAMPS.entrySet()) {
            Thread thread = entry.getKey();
            long lastActivity = entry.getValue().get();
            
            if (now - lastActivity > DEFAULT_TASK_TIMEOUT && thread.isAlive()) {
                // Extract dimension name from thread name if possible
                String threadName = thread.getName();
                String dimensionId = null;
                if (threadName.startsWith(MOD_ID + "_server_")) {
                    dimensionId = threadName.substring((MOD_ID + "_server_").length());
                }
                
                // We have a potential deadlock - interrupt the thread
                DimThread.LOGGER.warn("Potential deadlock detected in thread {}. Last activity: {} ms ago", 
                    threadName, now - lastActivity);
                
                try {
                    // Try to interrupt the thread, which might help it break out of a lock
                    thread.interrupt();
                    deadlockDetected = true;
                    
                    // Record the failure for this dimension if we can identify it
                    if (dimensionId != null) {
                        recordDimensionFailure(dimensionId);
                    }
                } catch (Exception e) {
                    DimThread.LOGGER.error("Failed to interrupt potentially deadlocked thread", e);
                }
                
                // Reset the timestamp to prevent multiple interrupts
                entry.getValue().set(now);
            }
        }
        
        return deadlockDetected;
    }
    
    /**
     * Returns a set of blacklisted dimensions (for admin commands or UI)
     * @return Set of dimension IDs that are blacklisted
     */
    public static Set<String> getBlacklistedDimensions() {
        return Collections.unmodifiableSet(BLACKLISTED_DIMENSIONS);
    }
    
    /**
     * Manually add a dimension to the blacklist
     * @param dimensionId Dimension ID to blacklist
     */
    public static void blacklistDimension(String dimensionId) {
        if (dimensionId != null && !dimensionId.isEmpty()) {
            BLACKLISTED_DIMENSIONS.add(dimensionId);
            LOGGER.info("Manually blacklisted dimension: {}", dimensionId);
        }
    }
    
    /**
     * Remove a dimension from the blacklist
     * @param dimensionId Dimension ID to unblacklist
     * @return true if the dimension was removed from the blacklist
     */
    public static boolean unblacklistDimension(String dimensionId) {
        if (BLACKLISTED_DIMENSIONS.remove(dimensionId)) {
            DIMENSION_FAILURE_COUNTS.remove(dimensionId);
            LOGGER.info("Removed dimension from blacklist: {}", dimensionId);
            return true;
        }
        return false;
    }
    
    /**
     * Clear the registry (typically done during server shutdown)
     */
    public static void clear() {
        WORKER_THREADS.clear();
        THREAD_ACTIVITY_TIMESTAMPS.clear();
        // Don't clear blacklists - we want to remember problematic dimensions between server reloads
    }
}
