package com.relapzz.vulkanfixes;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Fog;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/** Paints the fog color over the world framebuffer when the clear used a different one (see {@link SteadyClearColor}). */
public final class FogFill {
	public static final SteadyClearColor<Integer> CLEAR_COLOR = new SteadyClearColor<>();

	// Vanilla's sky pipeline (core/position, which outputs ColorModulator when fog is off), drawn over the
	// whole screen without depth test or blending.
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_FOG_SNIPPET)
		.withLocation(Identifier.of("vulkanfixes", "pipeline/fog_fill"))
		.withVertexShader("core/position")
		.withFragmentShader("core/position")
		.withoutBlend()
		.withCull(false)
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withColorWrite(true, true)
		.withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.TRIANGLE_FAN)
		.build();

	private static GpuBuffer quad;

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
		if (quad == null) {
			quad = createQuad();
		}
		Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();

		// The pipeline reads its uniforms from RenderSystem, like vanilla's sky does, so set them and put them back.
		Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
		ProjectionType projectionType = RenderSystem.getProjectionType();
		float[] shaderColor = RenderSystem.getShaderColor().clone();
		Fog fog = RenderSystem.getShaderFog();
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		try {
			modelView.identity();
			RenderSystem.setProjectionMatrix(new Matrix4f(), ProjectionType.ORTHOGRAPHIC);
			// Each channel is n/255, which the color attachment stores back as exactly n, the same as the clear.
			RenderSystem.setShaderColor(
				ColorHelper.getRedFloat(argb), ColorHelper.getGreenFloat(argb), ColorHelper.getBlueFloat(argb), ColorHelper.getAlphaFloat(argb));
			// Fog starts at Float.MAX_VALUE, so the shader returns ColorModulator unchanged.
			RenderSystem.setShaderFog(Fog.DUMMY);

			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				framebuffer.getColorAttachment(), OptionalInt.empty(), framebuffer.getDepthAttachment(), OptionalDouble.empty())) {
				pass.setPipeline(PIPELINE);
				pass.setVertexBuffer(0, quad);
				pass.draw(0, 4);
			}
		} finally {
			modelView.popMatrix();
			RenderSystem.setProjectionMatrix(projection, projectionType);
			RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
			RenderSystem.setShaderFog(fog);
		}
	}

	/** A full-screen quad in clip space, drawn with identity matrices. */
	private static GpuBuffer createQuad() {
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION);
		builder.vertex(-1.0F, -1.0F, 0.0F);
		builder.vertex(1.0F, -1.0F, 0.0F);
		builder.vertex(1.0F, 1.0F, 0.0F);
		builder.vertex(-1.0F, 1.0F, 0.0F);
		try (BuiltBuffer built = builder.end()) {
			return RenderSystem.getDevice().createBuffer(() -> "VulkanFixes fog fill quad", BufferType.VERTICES, BufferUsage.STATIC_WRITE, built.getBuffer());
		}
	}
}
