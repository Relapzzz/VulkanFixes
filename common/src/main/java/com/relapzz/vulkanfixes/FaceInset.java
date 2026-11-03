package com.relapzz.vulkanfixes;

/**
 * Converts block vertex positions to VulkanMod's compact terrain format without letting faces that vanilla keeps
 * a hair inside a block land on the block's boundary.
 *
 * <p>VulkanMod stores terrain positions as shorts in steps of 1/2048 block, relative to the chunk section, and
 * converts them with a plain {@code (short)} cast. Some models keep a face just inside the block so it can't touch
 * a neighbour: the top and bottom faces of iron bars sit 1/16000 of a block from the block's top and bottom. That
 * is far less than one step, and the cast truncates toward zero, so in the lower quarter of each chunk section the
 * top faces of bars snap up onto the boundary (and elsewhere their bottom faces snap down onto it). There they lie
 * exactly in the face of the block above or below, a ceiling or a floor, and the two z-fight: jagged patches that
 * flicker as the camera moves. Here such a position is kept one step inside the block instead, and every other
 * position is rounded to the nearest step.
 */
public final class FaceInset {
	// -Dvulkanfixes.faceInset=false packs positions the way VulkanMod does, for comparing the two.
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vulkanfixes.faceInset", "true"));
	private static final float STEPS_PER_BLOCK = 2048.0F;
	private static final float OFFSET = -8192.0F;
	// Float error in a position that is meant to sit on a block boundary is around 0.002 steps; vanilla's
	// smallest inset is 0.128 steps. Anything between counts as a deliberate inset.
	private static final float MIN_INSET = 0.01F;

	private FaceInset() {
	}

	/** One position coordinate relative to its chunk section, same scale and offset as VulkanMod. */
	public static short position(float coord) {
		float scaled = coord * STEPS_PER_BLOCK + OFFSET;
		if (!ENABLED) {
			return (short) (int) scaled;
		}
		int step = Math.round(scaled);
		int boundary = Math.round(coord) * (int) STEPS_PER_BLOCK + (int) OFFSET;
		float inset = scaled - boundary;
		if (step == boundary && Math.abs(inset) > MIN_INSET) {
			step += inset > 0 ? 1 : -1;
		}
		return (short) step;
	}
}
