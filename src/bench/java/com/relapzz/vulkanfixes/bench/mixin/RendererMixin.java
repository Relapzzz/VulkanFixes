package com.relapzz.vulkanfixes.bench.mixin;

import com.relapzz.vulkanfixes.bench.FrameStats;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Times the CPU's wait for the frame fence. A long wait means the CPU is idle
// waiting for the GPU, so the frame is GPU-bound; a short one means CPU-bound.
@Mixin(value = Renderer.class, remap = false)
abstract class RendererMixin {
	private static final String WAIT_FENCES = "Lorg/lwjgl/vulkan/VK10;vkWaitForFences(Lorg/lwjgl/vulkan/VkDevice;JZJ)I";

	@Inject(method = "beginFrame", at = @At(value = "INVOKE", target = WAIT_FENCES, ordinal = 0))
	private void vulkanfixesBench$beforeFence(CallbackInfo ci) {
		FrameStats.fenceStart();
	}

	@Inject(method = "beginFrame", at = @At(value = "INVOKE", target = WAIT_FENCES, ordinal = 0, shift = At.Shift.AFTER))
	private void vulkanfixesBench$afterFence(CallbackInfo ci) {
		FrameStats.fenceEnd();
	}
}
