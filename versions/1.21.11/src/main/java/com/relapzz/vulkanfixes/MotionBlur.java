package com.relapzz.vulkanfixes;

import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental camera motion blur, run after the world is drawn and before the hand and HUD.
 *
 * <p>Each pixel's position on screen last frame is found from last frame's camera rotation, and the pixel is
 * averaged along the line between the two. Only the current frame is read, so no frames are held back and no
 * input latency is added. Only turning the camera blurs; walking doesn't. The streak covers the camera motion of
 * half a monitor refresh times the strength, so it looks the same at 400 fps as at 120 fps.
 *
 * <p>The integrated GPUs this is aimed at are limited by memory bandwidth, so the blur is computed at half
 * resolution and blended over the sharp frame, only where pixels moved at least a pixel. Frames where
 * nothing moved that far aren't touched at all. The world framebuffer under VulkanMod is the swapchain image,
 * which can't be read and drawn into in the same pass, hence the separate half-resolution target.
 */
public final class MotionBlur {
	private static final Logger LOGGER = LoggerFactory.getLogger("VulkanFixes");

	// Longest streak as a fraction of the screen, and the most samples along it.
	private static final float MAX_BLUR = 0.06f;
	private static final int MAX_SAMPLES = 16;
	// Streaks shorter than this, in pixels, are left sharp (see motion_blur_composite.fsh).
	private static final float MIN_BLUR_PIXELS = 1.0f;
	// Frames further apart than this (a lag spike, a closed menu) aren't blurred into each other.
	private static final long MAX_FRAME_GAP_NANOS = 100_000_000L;

	private static final RenderPipeline BLUR_PIPELINE = RenderPipeline.builder()
		.withLocation(Identifier.of("vulkanfixes", "pipeline/motion_blur"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(Identifier.of("vulkanfixes", "core/motion_blur"))
		.withSampler("InSampler")
		.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
		.withoutBlend()
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withColorWrite(true, false)
		.withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
		.build();

	// Leaves alpha alone, so only the colors change.
	private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder()
		.withLocation(Identifier.of("vulkanfixes", "pipeline/motion_blur_composite"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(Identifier.of("vulkanfixes", "core/motion_blur_composite"))
		.withSampler("InSampler")
		.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
		.withBlend(BlendFunction.TRANSLUCENT)
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withColorWrite(true, false)
		.withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
		.build();

	private static boolean failed;

	private static SimpleFramebuffer halfRes;

	private static boolean hasLastFrame;
	private static final Matrix4f lastViewProjection = new Matrix4f();
	private static long lastFrameNanos;
	private static float frameSeconds;

	private MotionBlur() {
	}

	/** Called right after {@code WorldRenderer.render}, with the matrices it drew the world with. */
	public static void afterWorld(Matrix4fc view, Matrix4fc projection) {
		if (failed || !VulkanFixesConfig.get().motionBlur) {
			hasLastFrame = false;
			return;
		}
		try {
			blur(view, projection);
		} catch (RuntimeException e) {
			failed = true;
			LOGGER.error("VulkanFixes: motion blur failed, turning it off for this session", e);
		}
	}

	private static void blur(Matrix4fc view, Matrix4fc projection) {
		long now = System.nanoTime();
		// The view matrix is the camera's rotation only, so this leaves out the camera's movement.
		Matrix4f viewProjection = new Matrix4f(projection).mul(view);

		Matrix4f reprojection = null;
		if (hasLastFrame && now - lastFrameNanos < MAX_FRAME_GAP_NANOS) {
			// Clip space this frame -> view direction -> clip space last frame.
			reprojection = new Matrix4f(lastViewProjection).mul(new Matrix4f(viewProjection).invert());
			float seconds = (now - lastFrameNanos) / 1.0e9f;
			frameSeconds = frameSeconds == 0.0f ? seconds : frameSeconds + (seconds - frameSeconds) * 0.1f;
		} else {
			frameSeconds = 0.0f;
		}
		hasLastFrame = true;
		lastViewProjection.set(viewProjection);
		lastFrameNanos = now;
		if (reprojection == null) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		Framebuffer main = client.getFramebuffer();
		// Strength 1 is a film camera's half-open shutter: the motion of half a monitor refresh.
		float exposureSeconds = 0.5f * VulkanFixesConfig.get().motionBlurStrength / refreshRate(client);
		float blurScale = exposureSeconds / frameSeconds;
		if (longestBlurPixels(reprojection, blurScale, main.textureWidth, main.textureHeight) < MIN_BLUR_PIXELS) {
			return;
		}
		draw(main, reprojection, blurScale);
	}

	private static int refreshRate(MinecraftClient client) {
		int rate = client.getWindow().getRefreshRate();
		return rate > 0 ? rate : 60;
	}

	/**
	 * The longest streak on screen, in pixels, the same way the shaders compute it. Rotation moves the corners of
	 * the screen the most, so checking them and the center is enough.
	 */
	private static float longestBlurPixels(Matrix4fc reprojection, float blurScale, int width, int height) {
		float longest = 0.0f;
		Vector4f point = new Vector4f();
		for (int i = 0; i < 5; i++) {
			float x = i == 4 ? 0.0f : (i & 1) * 2 - 1;
			float y = i == 4 ? 0.0f : (i >> 1) * 2 - 1;
			reprojection.transform(point.set(x, y, 1.0f, 1.0f));
			if (point.w <= 0.0f) {
				// Out of last frame's view: the shaders clamp this streak to the longest one.
				return Float.MAX_VALUE;
			}
			float dx = (x - point.x / point.w) * 0.5f * width;
			float dy = (y - point.y / point.w) * 0.5f * height;
			longest = Math.max(longest, (float)Math.sqrt(dx * dx + dy * dy) * blurScale);
		}
		return longest;
	}

	private static void draw(Framebuffer main, Matrix4f reprojection, float blurScale) {
		int halfWidth = (main.textureWidth + 1) / 2;
		int halfHeight = (main.textureHeight + 1) / 2;
		if (halfRes == null) {
			halfRes = new SimpleFramebuffer("VulkanFixes motion blur", halfWidth, halfHeight, false);
		} else if (halfRes.textureWidth != halfWidth || halfRes.textureHeight != halfHeight) {
			halfRes.resize(halfWidth, halfHeight);
		}

		Vector4f parameters = new Vector4f(blurScale, MAX_BLUR, MAX_SAMPLES, 0.0f);
		GpuBufferSlice blurUniforms = RenderSystem.getDynamicUniforms()
			.write(reprojection, parameters, new Vector3f(1.0f / halfWidth, 1.0f / halfHeight, 0.0f), new Matrix4f());
		GpuBufferSlice compositeUniforms = RenderSystem.getDynamicUniforms()
			.write(reprojection, parameters, new Vector3f(1.0f / main.textureWidth, 1.0f / main.textureHeight, 0.0f), new Matrix4f());
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

		// VulkanMod leaves layout transitions of sampled textures to the caller (as its own PostPassM does), and
		// they must be recorded outside a render pass.
		Renderer.getInstance().endRenderPass();
		((VkGpuTexture)main.getColorAttachment()).getVulkanImage().readOnlyLayout();
		try (RenderPass pass = encoder.createRenderPass(() -> "VulkanFixes motion blur", halfRes.getColorAttachmentView(), OptionalInt.empty())) {
			pass.setPipeline(BLUR_PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", blurUniforms);
			pass.bindTexture("InSampler", main.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
			pass.draw(0, 3);
		}

		Renderer.getInstance().endRenderPass();
		((VkGpuTexture)halfRes.getColorAttachment()).getVulkanImage().readOnlyLayout();
		// Drawing into the main framebuffer again moves it back to an attachment layout.
		try (RenderPass pass = encoder.createRenderPass(() -> "VulkanFixes motion blur composite", main.getColorAttachmentView(), OptionalInt.empty())) {
			pass.setPipeline(COMPOSITE_PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", compositeUniforms);
			pass.bindTexture("InSampler", halfRes.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
			pass.draw(0, 3);
		}
	}
}
