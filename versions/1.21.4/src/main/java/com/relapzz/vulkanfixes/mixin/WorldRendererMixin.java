package com.relapzz.vulkanfixes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.relapzz.vulkanfixes.FogFill;
import com.relapzz.vulkanfixes.SteadyClearColor;
import com.relapzz.vulkanfixes.SteadyClearColor.Rgba;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// method_62218 is the frame graph's clear step for the world framebuffer:
// RenderSystem.clearColor(fog.x, fog.y, fog.z, 0) followed by RenderSystem.clear.
@Mixin(WorldRenderer.class)
abstract class WorldRendererMixin {
	@WrapOperation(method = "method_62218", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clearColor(FFFF)V"))
	private static void vulkanfixes$clearWithSteadyColor(float red, float green, float blue, float alpha, Operation<Void> original) {
		if (!SteadyClearColor.active()) {
			original.call(red, green, blue, alpha);
			return;
		}
		Rgba color = FogFill.CLEAR_COLOR.clearColorFor(new Rgba(red, green, blue, alpha));
		original.call(color.red(), color.green(), color.blue(), color.alpha());
	}

	@Inject(method = "method_62218", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V", shift = At.Shift.AFTER))
	private static void vulkanfixes$paintFogColor(Vector4f fogColor, CallbackInfo ci) {
		if (SteadyClearColor.active()) {
			FogFill.fillIfNeeded();
		}
	}
}
