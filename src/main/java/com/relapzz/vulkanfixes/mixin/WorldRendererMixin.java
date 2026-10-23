package com.relapzz.vulkanfixes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.relapzz.vulkanfixes.FogClear;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// method_62218 is the frame graph's clear step for the world framebuffer, called with the fog color.
@Mixin(WorldRenderer.class)
abstract class WorldRendererMixin {
	private static final String CLEAR =
		"Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V";

	@WrapOperation(method = "method_62218", at = @At(value = "INVOKE", target = CLEAR))
	private void vulkanfixes$clearWithSteadyColor(CommandEncoder encoder, GpuTexture color, int fogColor, GpuTexture depth, double clearDepth,
			Operation<Void> original) {
		if (!FogClear.ENABLED) {
			original.call(encoder, color, fogColor, depth, clearDepth);
			return;
		}
		original.call(encoder, color, FogClear.clearColor(fogColor), depth, clearDepth);
		FogClear.fillIfNeeded(fogColor);
	}
}
