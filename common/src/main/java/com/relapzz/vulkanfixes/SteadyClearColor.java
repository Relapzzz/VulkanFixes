package com.relapzz.vulkanfixes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the color the world framebuffer is cleared with, so that it doesn't change every frame.
 *
 * <p>Vanilla clears the world framebuffer with the fog color, and while the camera turns toward a sunrise or
 * sunset the fog color is blended by view direction, so it changes every frame. Under VulkanMod on Intel Xe in
 * exclusive fullscreen a clear color that keeps changing stalls the GPU for 10 ms or more a frame. Instead the
 * clear keeps using a color that only follows the fog once the fog has held still, and whenever the two differ
 * each version's {@code FogFill} paints the exact fog color over the cleared frame before anything else is
 * drawn. The values written are the same ones the vanilla clear would have written, so frames look identical.
 *
 * <p>The same logic serves every Minecraft version; {@code T} is however that version passes the color
 * (a packed ARGB {@code Integer} on 1.21.5+, a {@link Rgba} on older versions).
 */
public final class SteadyClearColor<T> {
	private static final Logger LOGGER = LoggerFactory.getLogger("VulkanFixes");

	// -Dvulkanfixes.fogClear=false restores vanilla behavior, for comparing the two.
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vulkanfixes.fogClear", "true"));
	// How long the fog color has to hold still before the clear color follows it. The bench raises it
	// to keep the fill path in use while comparing screenshots against vanilla.
	private static final int SETTLE_FRAMES = Integer.getInteger("vulkanfixes.fogClear.settleFrames", 20);

	private static boolean failed;

	private T clearColor;
	private T lastFogColor;
	private int stableFrames;

	/** Whether the fix is on; false if it was turned off or a fill ever failed. */
	public static boolean active() {
		return ENABLED && !failed;
	}

	/**
	 * Turns the fix off for the rest of the session and goes back to vanilla clearing. Called when a fill
	 * throws, so a version-specific problem costs at most one frame of wrong horizon color, not a crash.
	 */
	public static void fail(Throwable error) {
		if (!failed) {
			failed = true;
			LOGGER.error("VulkanFixes: fog fill failed, falling back to vanilla clearing", error);
		}
	}

	/** Returns the color to clear with this frame instead of {@code fogColor}. */
	public T clearColorFor(T fogColor) {
		if (fogColor.equals(lastFogColor)) {
			stableFrames++;
		} else {
			lastFogColor = fogColor;
			stableFrames = 0;
		}
		if (clearColor == null || (!fogColor.equals(clearColor) && stableFrames >= SETTLE_FRAMES)) {
			clearColor = fogColor;
		}
		return clearColor;
	}

	/** Whether the last clear used a different color than the fog, so the fog color must be painted over it. */
	public boolean needsFill() {
		return clearColor != null && !clearColor.equals(lastFogColor);
	}

	public T fogColor() {
		return lastFogColor;
	}

	/** A float RGBA color as passed to {@code RenderSystem.clearColor}, compared exactly. */
	public record Rgba(float red, float green, float blue, float alpha) {
	}
}
