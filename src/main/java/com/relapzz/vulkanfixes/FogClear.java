package com.relapzz.vulkanfixes;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
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

/**
 * Clears the world framebuffer without changing the clear color every frame.
 *
 * <p>Vanilla clears it with the fog color, and while the camera turns toward a sunrise or sunset the fog
 * color is blended by view direction, so it changes every frame. On Intel Xe in exclusive fullscreen a
 * clear color that keeps changing stalls the GPU for several milliseconds a frame. Instead, the clear keeps
 * using a color that only changes once the fog has settled, and when the fog differs from it a full-screen
 * triangle writes the exact fog color over it before anything else is drawn. The 8-bit values written are
 * the same ones the clear would have written, so the frame looks exactly as it does in vanilla.
 */
public final class FogClear {
	// How long the fog color has to hold still before the clear color follows it. The bench raises it
	// to keep the fill path in use while comparing screenshots against vanilla.
	private static final int SETTLE_FRAMES = Integer.getInteger("vulkanfixes.fogClear.settleFrames", 20);

	private static final RenderPipeline FILL = RenderPipeline.builder()
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

	// -Dvulkanfixes.fogClear=false restores vanilla behavior, for comparing the two.
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vulkanfixes.fogClear", "true"));

	private static boolean hasClearColor;
	private static int clearColor;
	private static int lastFogColor;
	private static int stableFrames;

	private FogClear() {
	}

	/** Returns the color to clear the world framebuffer with this frame instead of the fog color. */
	public static int clearColor(int fogColor) {
		if (fogColor == lastFogColor) {
			stableFrames++;
		} else {
			lastFogColor = fogColor;
			stableFrames = 0;
		}
		if (!hasClearColor || (fogColor != clearColor && stableFrames >= SETTLE_FRAMES)) {
			clearColor = fogColor;
			hasClearColor = true;
		}
		return clearColor;
	}

	/** Called right after the clear: paints the fog color over it if the clear used a different one. */
	public static void fillIfNeeded(int fogColor) {
		if (fogColor != clearColor) {
			fill(RenderSystem.getDevice().createCommandEncoder(), fogColor);
		}
	}

	private static void fill(CommandEncoder encoder, int argb) {
		Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
		// Each channel is n/255, which the color attachment stores back as exactly n, the same as the clear.
		Vector4f color = new Vector4f(
			ColorHelper.getRedFloat(argb), ColorHelper.getGreenFloat(argb), ColorHelper.getBlueFloat(argb), ColorHelper.getAlphaFloat(argb));
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(new Matrix4f(), color, new Vector3f(), new Matrix4f());
		try (RenderPass pass = encoder.createRenderPass(() -> "VulkanFixes fog fill",
			framebuffer.getColorAttachmentView(), OptionalInt.empty(), framebuffer.getDepthAttachmentView(), OptionalDouble.empty())) {
			pass.setPipeline(FILL);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.draw(0, 3);
		}
	}
}
