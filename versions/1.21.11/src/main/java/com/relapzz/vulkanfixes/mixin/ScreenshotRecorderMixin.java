package com.relapzz.vulkanfixes.mixin;

import java.io.File;

import com.llamalad7.mixinextras.sugar.Local;
import com.relapzz.vulkanfixes.ScreenshotClipboard;
import com.relapzz.vulkanfixes.VulkanFixesConfig;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Copies each screenshot to the clipboard once vanilla has written the PNG, and says so in the "Saved screenshot" message.
// VulkanMod replaces only the GPU readback (takeScreenshot), so this save step is still vanilla's.
@Mixin(ScreenshotRecorder.class)
abstract class ScreenshotRecorderMixin {
	// method_22691: the IO worker task that writes the image to the file and reports success (first accept) or failure.
	@ModifyArg(method = "method_22691", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0))
	private static Object vulkanfixes$copyToClipboard(Object message, @Local(argsOnly = true) File file) {
		if (!VulkanFixesConfig.get().screenshotToClipboard) {
			return message;
		}
		String note = ScreenshotClipboard.copy(file) ? "vulkanfixes.screenshot.copied" : "vulkanfixes.screenshot.copyFailed";
		return Text.empty().append((Text)message).append(" ").append(Text.translatable(note).formatted(Formatting.GRAY));
	}
}
