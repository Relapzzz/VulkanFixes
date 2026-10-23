package com.relapzz.vulkanfixes;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The settings in {@code config/vulkanfixes.json}, edited from the Mod Menu screen ({@link ConfigScreen}). */
public final class VulkanFixesConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("VulkanFixes");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("vulkanfixes.json");

	public static final float MIN_STRENGTH = 0.1f;
	public static final float MAX_STRENGTH = 2.0f;

	private static VulkanFixesConfig instance;

	/** Experimental camera motion blur, see {@link MotionBlur}. */
	public boolean motionBlur = false;
	/** 1 blurs each frame across the camera motion of half a monitor refresh, 2 across a whole one. */
	public float motionBlurStrength = 1.0f;

	public static VulkanFixesConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static VulkanFixesConfig load() {
		VulkanFixesConfig config = null;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				config = GSON.fromJson(reader, VulkanFixesConfig.class);
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("VulkanFixes: couldn't read {}, using defaults", PATH, e);
			}
		}
		if (config == null) {
			config = new VulkanFixesConfig();
		}
		config.motionBlurStrength = MathHelper.clamp(config.motionBlurStrength, MIN_STRENGTH, MAX_STRENGTH);
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("VulkanFixes: couldn't save {}", PATH, e);
		}
	}
}
