package me.srrapero720.dimthread.init;

import me.srrapero720.dimthread.DimConfig;
import me.srrapero720.dimthread.gamerule.BoolRule;
import me.srrapero720.dimthread.gamerule.IntRule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import me.srrapero720.dimthread.DimThread;

public class ModGameRules {

	public static BoolRule ACTIVE;
	public static IntRule THREAD_COUNT;

	public static void register() {
		// Fix callback to properly convert BooleanValue to boolean
		ACTIVE = BoolRule.builder("active", GameRules.Category.UPDATES)
				.setInitial(true)
				.setCallback((server, value) -> DimThread.MANAGER.setActive(server, value.get()))
				.build();

		// Fix callback to properly convert IntegerValue to int
		THREAD_COUNT = IntRule.builder("thread_count", GameRules.Category.UPDATES)
				.setInitial(Math.min(DimConfig.DEFAULT_GAMERULE_THREADS.get(), Runtime.getRuntime().availableProcessors()))
				.setBounds(1, Runtime.getRuntime().availableProcessors())
				.setCallback((server, value) -> DimThread.MANAGER.setThreadCount(server, value.get()))
				.build();
	}
}