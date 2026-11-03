package com.relapzz.vulkanfixes.common.mixin;

import com.relapzz.vulkanfixes.FaceInset;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * VulkanMod's compact terrain vertex writer, the same in every VulkanMod version VulkanFixes supports. Only the
 * position conversion changes (see {@link FaceInset}); the layout and everything else is VulkanMod's own.
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.render.vertex.VertexBuilder$CompressedVertexBuilder", remap = false)
public abstract class CompressedVertexBuilderMixin {
	/**
	 * @author relapzz
	 * @reason Keep faces that sit just inside a block from snapping onto its boundary and z-fighting.
	 */
	@Overwrite
	public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
		MemoryUtil.memPutShort(ptr, FaceInset.position(x));
		MemoryUtil.memPutShort(ptr + 2L, FaceInset.position(y));
		MemoryUtil.memPutShort(ptr + 4L, FaceInset.position(z));
		MemoryUtil.memPutShort(ptr + 6L, (short) (light >>> 8 & 0xFF00 | light & 0xFF));
		MemoryUtil.memPutShort(ptr + 8L, (short) (u * 32768.0F));
		MemoryUtil.memPutShort(ptr + 10L, (short) (v * 32768.0F));
		MemoryUtil.memPutInt(ptr + 12L, color);
	}

	/**
	 * @author relapzz
	 * @reason Keep faces that sit just inside a block from snapping onto its boundary and z-fighting.
	 */
	@Overwrite
	public void position(long ptr, float x, float y, float z) {
		MemoryUtil.memPutShort(ptr, FaceInset.position(x));
		MemoryUtil.memPutShort(ptr + 2L, FaceInset.position(y));
		MemoryUtil.memPutShort(ptr + 4L, FaceInset.position(z));
	}
}
