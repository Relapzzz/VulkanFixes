package com.relapzz.vulkanfixes;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.relapzz.vulkanfixes.SteadyClearColor.Rgba;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/** Paints the fog color over the world framebuffer when the clear used a different one (see {@link SteadyClearColor}). */
public final class FogFill {
	public static final SteadyClearColor<Rgba> CLEAR_COLOR = new SteadyClearColor<>();

	// Vanilla's position shader (outputs ColorModulator when fog is off) over the whole screen, with color and
	// alpha writes only. A render layer sets and resets GL state the same way every vanilla draw does.
	private static final RenderLayer LAYER = RenderLayer.of("vulkanfixes_fog_fill", VertexFormats.POSITION, VertexFormat.DrawMode.QUADS, 256,
		RenderLayer.MultiPhaseParameters.builder()
			.program(RenderPhase.POSITION_PROGRAM)
			.depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
			.writeMaskState(RenderPhase.COLOR_MASK)
			.transparency(RenderPhase.NO_TRANSPARENCY)
			.cull(RenderPhase.DISABLE_CULLING)
			.build(false));

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

	private static void fill(Rgba color) {
		// The shader reads its uniforms from RenderSystem, so set them and put them back.
		Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
		VertexSorter vertexSorting = RenderSystem.getVertexSorting();
		float[] shaderColor = RenderSystem.getShaderColor().clone();
		float fogStart = RenderSystem.getShaderFogStart();
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		try {
			modelView.identity();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorter.BY_Z);
			// The same floats the clear was given, so the attachment ends up with the same values.
			RenderSystem.setShaderColor(color.red(), color.green(), color.blue(), color.alpha());
			// With fog starting at Float.MAX_VALUE the shader returns ColorModulator unchanged.
			RenderSystem.setShaderFogStart(Float.MAX_VALUE);

			BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
			builder.vertex(-1.0F, -1.0F, 0.0F);
			builder.vertex(1.0F, -1.0F, 0.0F);
			builder.vertex(1.0F, 1.0F, 0.0F);
			builder.vertex(-1.0F, 1.0F, 0.0F);
			LAYER.draw(builder.end());
		} finally {
			modelView.popMatrix();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.setProjectionMatrix(projection, vertexSorting);
			RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
			RenderSystem.setShaderFogStart(fogStart);
		}
	}
}
