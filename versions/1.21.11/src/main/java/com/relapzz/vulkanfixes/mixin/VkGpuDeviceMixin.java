package com.relapzz.vulkanfixes.mixin;

import java.util.Map;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderSourceGetter;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.vulkanmod.render.engine.VkGpuDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets resource packs replace core shaders, as they can without VulkanMod. Servers use this to draw their HUD: a
 * pack's text shader moves text in marker colours out of the action bar into corners and bands, and turns glyphs
 * into panels.
 *
 * <p>VulkanMod remembers the source of every shader the first time a pipeline needs it and never forgets it. A
 * resource reload clears the pipeline cache and compiles every pipeline again from the new packs, but VulkanMod
 * hands it the remembered source, so a pack that arrives after startup (a server's always does) changes nothing.
 * Forgetting the sources along with the pipelines makes each reload read the packs loaded now.
 *
 * <p>That alone would break one pipeline. VulkanMod swaps in its own copy of the item entity vertex shader, whose
 * only change is declaring UV1 as the integers the vertex data holds instead of vanilla's floats. Paired with a
 * pack's fragment shader that reads outputs of the pack's own vertex shader, it fails to compile and the game
 * drops every server pack. When a pack replaces that vertex shader, its copy is used, with UV1 corrected the same
 * way.
 */
@Mixin(value = VkGpuDevice.class, remap = false)
abstract class VkGpuDeviceMixin {
	// -Dvulkanfixes.packShaders=false keeps VulkanMod's behaviour, for comparing the two.
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vulkanfixes.packShaders", "true"));
	private static final Identifier ITEM_ENTITY_VERTEX = Identifier.ofVanilla("core/rendertype_item_entity_translucent_cull");

	@Shadow
	@Final
	private Map<?, String> shaderSrcCache;

	@Inject(method = "clearPipelineCache", at = @At("TAIL"))
	private void vulkanfixes$forgetShaderSources(CallbackInfo ci) {
		if (ENABLED) {
			this.shaderSrcCache.clear();
		}
	}

	@Inject(method = "getCachedShaderSrc", at = @At("HEAD"), cancellable = true)
	private void vulkanfixes$packItemEntityShader(Identifier id, ShaderType type, Defines defines, ShaderSourceGetter sources,
			CallbackInfoReturnable<String> cir) {
		if (!ENABLED || type != ShaderType.VERTEX || !id.equals(ITEM_ENTITY_VERTEX) || !fromPack(id.withPath(path -> "shaders/" + path + ".vsh"))) {
			return;
		}
		String source = sources.get(id, type);
		if (source != null) {
			cir.setReturnValue(source.replace("in vec2 UV1;", "in ivec2 UV1;"));
		}
	}

	private static boolean fromPack(Identifier file) {
		return MinecraftClient.getInstance().getResourceManager().getResource(file)
			.map(Resource::getPackId)
			.filter(pack -> !pack.equals("vanilla"))
			.isPresent();
	}
}
