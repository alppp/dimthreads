package me.srrapero720.dimthread.mixin.impl.mi_405388;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "aztech.modern_industrialization.machines.multiblocks.world")
@Pseudo
public class ChunkEventListenerMixin {
    @Redirect(method = "onBlockStateChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    @Dynamic
    private static boolean redirect$isSameThread(MinecraftServer instance) {
        return true;
    }

    /**
     * Replace the original method with our version that doesn't check thread
     * @author SrRapero720
     * @reason Redundant thread check
     */
    @Inject(method = "ensureServerThread", at = @At("HEAD"), cancellable = true)
    @Dynamic
    private static void ensureServerThread(MinecraftServer server, CallbackInfo ci) {
        if (server == null) {
            throw new RuntimeException("Null server!");
        }
        // Skip the original method implementation (which would check isSameThread)
        ci.cancel();
    }
}