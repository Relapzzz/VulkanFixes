package com.relapzz.vulkanfixes.bench.mixin;

import com.relapzz.vulkanfixes.bench.BenchClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class MinecraftClientMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void vulkanfixesBench$onFrame(boolean tick, CallbackInfo ci) {
		BenchClient.onFrame((MinecraftClient) (Object) this);
	}
}
