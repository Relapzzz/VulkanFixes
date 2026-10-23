package com.relapzz.vulkanfixes;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/** Settings screen opened from Mod Menu. Built from vanilla widgets, so it needs no config library. */
public final class ConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 210;

	private final @Nullable Screen parent;
	private final VulkanFixesConfig config = VulkanFixesConfig.get();

	public ConfigScreen(@Nullable Screen parent) {
		super(Text.translatable("vulkanfixes.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = (this.width - WIDGET_WIDTH) / 2;
		int y = this.height / 4;

		this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.config.motionBlur)
			.tooltip(enabled -> Tooltip.of(Text.translatable("vulkanfixes.config.motionBlur.tooltip")))
			.build(x, y, WIDGET_WIDTH, 20, Text.translatable("vulkanfixes.config.motionBlur"), (button, enabled) -> this.config.motionBlur = enabled));
		this.addDrawableChild(new StrengthSlider(x, y + 24, WIDGET_WIDTH));

		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> this.close())
			.dimensions(x, this.height - 28, WIDGET_WIDTH, 20)
			.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		this.config.save();
		this.client.setScreen(this.parent);
	}

	private final class StrengthSlider extends SliderWidget {
		StrengthSlider(int x, int y, int width) {
			super(x, y, width, 20, Text.empty(), toSlider(ConfigScreen.this.config.motionBlurStrength));
			this.updateMessage();
		}

		private static double toSlider(float strength) {
			return (strength - VulkanFixesConfig.MIN_STRENGTH) / (VulkanFixesConfig.MAX_STRENGTH - VulkanFixesConfig.MIN_STRENGTH);
		}

		private float strength() {
			// Steps of 0.05.
			float strength = (float)MathHelper.lerp(this.value, VulkanFixesConfig.MIN_STRENGTH, VulkanFixesConfig.MAX_STRENGTH);
			return Math.round(strength * 20.0f) / 20.0f;
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Text.translatable("vulkanfixes.config.motionBlurStrength", String.format("%.2f", this.strength())));
		}

		@Override
		protected void applyValue() {
			ConfigScreen.this.config.motionBlurStrength = this.strength();
		}
	}
}
