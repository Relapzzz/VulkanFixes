package com.relapzz.vulkanfixes.mixin;

import com.relapzz.vulkanfixes.FogFill;
import com.relapzz.vulkanfixes.SteadyClearColor;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The world framebuffer is cleared once per frame in render(), right after BackgroundRenderer.render
// has set the clear color (see BackgroundRendererMixin).
@Mixin(WorldRenderer.class)
abstract class WorldRendererMixin {
	@Inject(
		method = "render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0, shift = At.Shift.AFTER))
	private void vulkanfixes$paintFogColor(CallbackInfo ci) {
		if (SteadyClearColor.active()) {
			FogFill.fillIfNeeded();
		}
	}
}
