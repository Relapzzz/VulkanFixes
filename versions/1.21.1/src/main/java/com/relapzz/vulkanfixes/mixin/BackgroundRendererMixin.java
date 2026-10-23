package com.relapzz.vulkanfixes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.relapzz.vulkanfixes.FogFill;
import com.relapzz.vulkanfixes.SteadyClearColor;
import com.relapzz.vulkanfixes.SteadyClearColor.Rgba;
import net.minecraft.client.render.BackgroundRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// BackgroundRenderer.render ends by setting the clear color to the fog color (alpha 0); both of its
// RenderSystem.clearColor calls do this, one for the powder snow early exit and one for everything else.
// WorldRenderer.render clears right after (see WorldRendererMixin).
@Mixin(BackgroundRenderer.class)
abstract class BackgroundRendererMixin {
	@WrapOperation(
		method = "render(Lnet/minecraft/client/render/Camera;FLnet/minecraft/client/world/ClientWorld;IF)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clearColor(FFFF)V"))
	private static void vulkanfixes$clearWithSteadyColor(float red, float green, float blue, float alpha, Operation<Void> original) {
		if (!SteadyClearColor.active()) {
			original.call(red, green, blue, alpha);
			return;
		}
		Rgba color = FogFill.CLEAR_COLOR.clearColorFor(new Rgba(red, green, blue, alpha));
		original.call(color.red(), color.green(), color.blue(), color.alpha());
	}
}
