package com.relapzz.vulkanfixes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.relapzz.vulkanfixes.MotionBlur;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Motion blur runs between the world and the hand, so the hand, held item and HUD stay sharp.
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	private static final String RENDER_WORLD =
		"Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V";

	@WrapOperation(method = "renderWorld", at = @At(value = "INVOKE", target = RENDER_WORLD))
	private void vulkanfixes$motionBlurAfterWorld(WorldRenderer worldRenderer, ObjectAllocator allocator, RenderTickCounter tickCounter,
			boolean renderBlockOutline, Camera camera, Matrix4f view, Matrix4f projection, Matrix4f cullingProjection, GpuBufferSlice fog,
			Vector4f fogColor, boolean renderSky, Operation<Void> original) {
		original.call(worldRenderer, allocator, tickCounter, renderBlockOutline, camera, view, projection, cullingProjection, fog, fogColor, renderSky);
		MotionBlur.afterWorld(view, projection);
	}
}
