package me.srrapero720.dimthread.thread;

import me.srrapero720.dimthread.DimThread;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.*;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static me.srrapero720.dimthread.DimThread.MOD_ID;

public class ThreadPool {
	private ThreadPoolExecutor executor;
	private final int threadCount;
	private final IntLatch activeCount = new IntLatch();
	private volatile long lastActivityTime;

	public ThreadPool() {
		this(Runtime.getRuntime().availableProcessors());
	}

	public ThreadPool(int threadCount) {
		this.threadCount = threadCount;
		this.lastActivityTime = System.currentTimeMillis();
		this.restart();
	}

	public int getThreadCount() {
		return this.threadCount;
	}

	public int getActiveCount() {
		return this.activeCount.getCount();
	}

	public ThreadPoolExecutor getExecutor() {
		return this.executor;
	}

	public void execute(Runnable action) {
		this.activeCount.increment();
		this.lastActivityTime = System.currentTimeMillis();

		this.executor.execute(() -> {
			Thread currentThread = Thread.currentThread();
			try {
				// Register activity at the start
				DimThreadRegistry.updateActivity(currentThread);
				
				// Run the actual task
				action.run();
				
				// Update last activity time when task completes
				this.lastActivityTime = System.currentTimeMillis();
				DimThreadRegistry.updateActivity(currentThread);
			} catch (Throwable t) {
				// Log any unhandled exceptions
				DimThread.LOGGER.error("Uncaught exception in DimThread worker", t);
			} finally {
				this.activeCount.decrement();
			}
		});
	}

	public <T> void execute(Iterator<T> iterator, Consumer<T> action) {
		iterator.forEachRemaining(t -> this.execute(() -> action.accept(t)));
	}

	public <T> void execute(Iterable<T> iterable, Consumer<T> action) {
		iterable.forEach(t -> this.execute(() -> action.accept(t)));
	}

	public <T> void execute(Stream<T> stream, Consumer<T> action) {
		stream.forEach(t -> this.execute(() -> action.accept(t)));
	}

	public void execute(IntStream stream, IntConsumer action) {
		stream.forEach(t -> this.execute(() -> action.accept(t)));
	}

	public void execute(LongStream stream, LongConsumer action) {
		stream.forEach(t -> this.execute(() -> action.accept(t)));
	}

	public void execute(DoubleStream stream, DoubleConsumer action) {
		stream.forEach(t -> this.execute(() -> action.accept(t)));
	}

	public <T> void execute(T[] array, Consumer<T> action) {
		for(T t : array) this.execute(() -> action.accept(t));
	}

	public void execute(boolean[] array, Consumer<Boolean> action) {
		for(boolean t : array) this.execute(() -> action.accept(t));
	}

	public void execute(byte[] array, Consumer<Byte> action) {
		for(byte t : array) this.execute(() -> action.accept(t));
	}

	public void execute(short[] array, Consumer<Short> action) {
		for(short t : array) this.execute(() -> action.accept(t));
	}

	public void execute(int[] array, IntConsumer action) {
		for(int t : array) this.execute(() -> action.accept(t));
	}

	public void execute(long[] array, LongConsumer action) {
		for(long t : array) this.execute(() -> action.accept(t));
	}

	public void execute(float[] array, Consumer<Float> action) {
		for(float t : array) this.execute(() -> action.accept(t));
	}

	public void execute(double[] array, DoubleConsumer action) {
		for(double t : array) this.execute(() -> action.accept(t));
	}

	public void execute(char[] array, Consumer<Character> action) {
		for(char t : array) this.execute(() -> action.accept(t));
	}

	public void awaitFreeThread() {
		this.waitFor(value -> value < this.getThreadCount());
	}

	public void awaitCompletion() {
		// First try waiting normally
		try {
			if (this.waitFor(value -> value == 0, 30000)) {
				return; // Successfully completed
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		// If we're still here, we've timed out
		DimThread.LOGGER.warn("Thread pool tasks did not complete within timeout, checking for deadlocks");
		
		// Check for and attempt to clear deadlocks
		if (DimThreadRegistry.detectAndClearDeadlocks()) {
			DimThread.LOGGER.warn("Deadlocks detected and threads interrupted, continuing execution");
		} else {
			DimThread.LOGGER.error("No deadlocks detected but tasks are not completing. This may indicate a performance issue.");
		}
	}

	/**
	 * Wait until the predicate returns true for the active count, with a timeout
	 * @param condition The condition to check
	 * @param timeoutMillis Maximum time to wait in milliseconds
	 * @return true if condition was met, false if timed out
	 */
	public boolean waitFor(IntPredicate condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (!condition.test(this.activeCount.getCount())) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				return false; // Timeout
			}
			synchronized (this.activeCount) {
				this.activeCount.wait(Math.min(remaining, 1000)); // Wait up to 1 second at a time
			}
		}
		return true;
	}

	// Replace the existing waitFor method with this one
	public void waitFor(IntPredicate condition) {
		try {
			this.waitFor(condition, 30000); // Default 30 second timeout
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Get the timestamp of the last activity in this thread pool
	 * @return timestamp in milliseconds
	 */
	public long getLastActivityTime() {
		return this.lastActivityTime;
	}

	public void restart() {
		if(this.executor == null || this.executor.isShutdown()) {
			this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(this.threadCount, r -> {
				Thread t = new Thread(r);
				t.setDaemon(true);
				t.setName(MOD_ID + "_server_" + "unassigned");
				// Register the thread when it's created
				DimThreadRegistry.register(t);
				return t;
			});
		}
	}

	public void shutdown() {
		if (this.executor != null && !this.executor.isShutdown()) {
			this.executor.shutdown();
		}
	}

	public boolean isShutdown() {
		return this.executor.isShutdown();
	}

	private static class IntLatch {
		private int count;
		
		private IntLatch() {
			this(0);
		}
		
		private IntLatch(int count) {
			this.count = count;
		}
		
		private synchronized int getCount() {
			return this.count;
		}
		
		private synchronized void decrement() {
			this.count--;
			this.notifyAll();
		}
		
		private synchronized void increment() {
			this.count++;
		}
		
		private synchronized void waitUntil(IntPredicate predicate) throws InterruptedException {
			while (!predicate.test(this.getCount())) {
				this.wait(1000); // Wait in 1-second increments for better responsiveness
			}
		}
	}
}