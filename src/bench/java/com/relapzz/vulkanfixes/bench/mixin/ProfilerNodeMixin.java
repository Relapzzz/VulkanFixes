package com.relapzz.vulkanfixes.bench.mixin;

import com.relapzz.vulkanfixes.bench.FrameStats;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every VulkanMod profiler section reports its duration here when it closes,
// so a slow frame can be broken down into the stages that took the time.
@Mixin(targets = "net.vulkanmod.render.profiling.Profiler$Node", remap = false)
abstract class ProfilerNodeMixin {
	@Shadow
	@Final
	String name;

	@Inject(method = "push", at = @At("HEAD"))
	private void vulkanfixesBench$onSection(long duration, CallbackInfo ci) {
		FrameStats.stage(this.name, duration);
	}
}
