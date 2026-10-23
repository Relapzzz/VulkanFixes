package com.relapzz.vulkanfixes;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Paints the fog color over the world framebuffer when the clear used a different one (see {@link SteadyClearColor}). */
public final class FogFill {
	public static final SteadyClearColor<Integer> CLEAR_COLOR = new SteadyClearColor<>();

	// A full-screen triangle (vanilla's screenquad) whose fragment shader writes ColorModulator.
	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
		.withLocation(Identifier.of("vulkanfixes", "pipeline/fog_fill"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(Identifier.of("vulkanfixes", "core/fog_fill"))
		.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
		.withoutBlend()
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withColorWrite(true, true)
		.withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
		.build();

	private FogFill() {
	}

	/** Called right after the clear. */
	public static void fillIfNeeded() {
		if (!CLEAR_COLOR.needsFill()) {
			return;
		}
		try {
			fill(CLEAR_COLOR.fogColor());
		} catch (RuntimeException e) {
			SteadyClearColor.fail(e);
		}
	}

	private static void fill(int argb) {
		Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
		// Each channel is n/255, which the color attachment stores back as exactly n, the same as the clear.
		Vector4f color = new Vector4f(
			ColorHelper.getRedFloat(argb), ColorHelper.getGreenFloat(argb), ColorHelper.getBlueFloat(argb), ColorHelper.getAlphaFloat(argb));
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(new Matrix4f(), color, new Vector3f(), new Matrix4f());
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "VulkanFixes fog fill",
			framebuffer.getColorAttachmentView(), OptionalInt.empty(), framebuffer.getDepthAttachmentView(), OptionalDouble.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.draw(0, 3);
		}
	}
}
