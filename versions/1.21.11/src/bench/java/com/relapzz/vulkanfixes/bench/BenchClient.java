package com.relapzz.vulkanfixes.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.vulkanmod.render.profiling.Profiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays fixed scenes in the world saved as {@code run/saves/bench} and logs frame
 * times for each, so a change can be judged by numbers instead of by eye. The scene
 * positions below belong to the original test world; adjust them for another one.
 */
public final class BenchClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("vulkanfixes-bench");

	private static final long SETTLE_NS = Long.getLong("vulkanfixes.bench.settleMs", 6000) * 1_000_000L;
	private static final long MEASURE_NS = Long.getLong("vulkanfixes.bench.measureMs", 8000) * 1_000_000L;
	private static final String LABEL = System.getProperty("vulkanfixes.bench.label", "run");
	private static final Set<String> ONLY = parseList(System.getProperty("vulkanfixes.bench.scenes", ""));
	// e.g. 300 reproduces "/tick rate 300", which makes the client tick 15x as often.
	private static final int TICK_RATE = Integer.getInteger("vulkanfixes.bench.tickRate", 20);
	// Plays like a real player: creative with the hand drawn and F3 open, instead of a bare spectator camera.
	private static final boolean REALISTIC = Boolean.getBoolean("vulkanfixes.bench.realistic");
	// F3's text is the biggest garbage producer in the game, so it can be switched off separately.
	private static final boolean F3 = Boolean.parseBoolean(System.getProperty("vulkanfixes.bench.f3", String.valueOf(REALISTIC)));
	// The sunset stall only shows in exclusive fullscreen, where the swapchain image is flipped straight to the display.
	private static final boolean FULLSCREEN = Boolean.getBoolean("vulkanfixes.bench.fullscreen");
	// Also takes a screenshot this long into measuring, while the camera moves, e.g. to look at motion blur.
	// The screenshot stalls one frame, so leave it off when the numbers matter.
	private static final long MOTION_SHOT_NS = Long.getLong("vulkanfixes.bench.motionShotMs", 0) * 1_000_000L;

	// Positions and angles in the original test world; 12522 is the sun on the horizon.
	private static final String SUNSET_SPOT = "-277.456 72 42.426";
	private static final String CAVE_SPOT = "-77.223 -32 67.195";
	private static final List<Scene> SCENES = List.of(
		new Scene("sunset_west", 12522, SUNSET_SPOT, 89.5f, -4.4f, Motion.STILL),
		new Scene("sunset_west_sweep", 12522, SUNSET_SPOT, 89.5f, -4.4f, Motion.SWEEP),
		new Scene("sunset_east", 12522, SUNSET_SPOT, -90.5f, -4.4f, Motion.STILL),
		new Scene("sunset_east_sweep", 12522, SUNSET_SPOT, -90.5f, -4.4f, Motion.SWEEP),
		new Scene("sunset_spin", 12522, SUNSET_SPOT, 89.5f, -4.4f, Motion.SPIN),
		// 13000 is "/time set night": the sun is just down and the glow is strongest. Turning while
		// facing it changes the fog color, and with it the frame's clear color, every frame.
		new Scene("dusk_west", 13000, SUNSET_SPOT, 89.5f, -20f, Motion.STILL),
		new Scene("dusk_west_turn", 13000, SUNSET_SPOT, 89.5f, -20f, Motion.TURN),
		new Scene("dusk_east_turn", 13000, SUNSET_SPOT, -90.5f, -20f, Motion.TURN),
		new Scene("noon_west", 6000, SUNSET_SPOT, 89.5f, -4.4f, Motion.STILL),
		new Scene("noon_west_sweep", 6000, SUNSET_SPOT, 89.5f, -4.4f, Motion.SWEEP),
		new Scene("cave", 12522, CAVE_SPOT, -75.7f, 5.7f, Motion.STILL),
		new Scene("cave_sweep", 12522, CAVE_SPOT, -75.7f, 5.7f, Motion.SWEEP),
		new Scene("cave_spin", 12522, CAVE_SPOT, -75.7f, 5.7f, Motion.SPIN),
		new Scene("lava", 12522, null, 0, 0, Motion.STILL),
		new Scene("lava_spin", 12522, null, 0, 0, Motion.SPIN)
	);

	private static final FrameStats STATS = new FrameStats();
	private static final List<String> RESULTS = new ArrayList<>();

	private static Phase phase = Phase.WAIT_WORLD;
	private static long phaseStartNs;
	private static long lastFrameNs;
	private static SlowFrameEvent frameEvent;
	private static boolean motionShotTaken;
	private static int sceneIndex = -1;
	private static float baseYaw;
	private static float basePitch;
	private static Vec3d lavaEye;
	private static float lavaYaw;
	private static float lavaPitch;

	@Override
	public void onInitializeClient() {
		Profiler.setActive(true);
		LOGGER.info("Bench harness loaded: label={}, tickRate={}, realistic={}, fullscreen={}, settle={}ms, measure={}ms, scenes={}",
			LABEL, TICK_RATE, REALISTIC, FULLSCREEN, SETTLE_NS / 1_000_000, MEASURE_NS / 1_000_000, ONLY.isEmpty() ? "all" : ONLY);
	}

	public static void onFrame(MinecraftClient client) {
		long now = System.nanoTime();
		long frameNs = now - lastFrameNs;
		lastFrameNs = now;

		if (frameEvent != null) {
			frameEvent.commit();
		}
		frameEvent = new SlowFrameEvent();
		frameEvent.scene = sceneIndex >= 0 && sceneIndex < SCENES.size() ? scene().name : phase.name();
		frameEvent.begin();

		switch (phase) {
			case WAIT_WORLD -> {
				if (client.world != null && client.player != null && client.getServer() != null && client.currentScreen == null) {
					run(client, "tick rate " + TICK_RATE, "tick freeze", "gamemode " + (REALISTIC ? "creative" : "spectator") + " @a", "weather clear");
					client.debugHudEntryList.setF3Enabled(F3);
					if (FULLSCREEN && !client.getWindow().isFullscreen()) {
						client.getWindow().toggleFullscreen();
					}
					enter(Phase.WARMUP, now);
				}
			}
			case WARMUP -> {
				if (now - phaseStartNs > SETTLE_NS) {
					nextScene(client, now);
				}
			}
			case SETTLE -> {
				hold(client, now);
				if (now - phaseStartNs > SETTLE_NS) {
					ScreenshotRecorder.saveScreenshot(client.runDirectory, "bench-" + LABEL + "-" + scene().name + ".png", client.getFramebuffer(), 1, text -> {
					});
					enter(Phase.SHOT, now);
				}
			}
			case SHOT -> {
				// This frame's duration contains the screenshot readback stall, so measuring starts after it.
				enter(Phase.MEASURE, now);
				STATS.begin(now);
				motionShotTaken = false;
				hold(client, now);
			}
			case MEASURE -> {
				STATS.record(frameNs, now);
				hold(client, now);
				if (MOTION_SHOT_NS > 0 && !motionShotTaken && now - phaseStartNs > MOTION_SHOT_NS) {
					motionShotTaken = true;
					ScreenshotRecorder.saveScreenshot(client.runDirectory, "bench-" + LABEL + "-" + scene().name + "-motion.png", client.getFramebuffer(), 1, text -> {
					});
				}
				if (now - phaseStartNs > MEASURE_NS) {
					String line = String.format("%-18s %s", scene().name, STATS.summary());
					LOGGER.info("[bench:{}] {}", LABEL, line);
					RESULTS.add(line);
					RESULTS.addAll(STATS.spikes());
					for (String spike : STATS.spikes()) {
						LOGGER.info("[bench:{}] {}", LABEL, spike);
					}
					nextScene(client, now);
				}
			}
			case DONE -> {
			}
		}
		FrameStats.endFrame();
	}

	private static void nextScene(MinecraftClient client, long now) {
		do {
			sceneIndex++;
		} while (sceneIndex < SCENES.size() && !ONLY.isEmpty() && !ONLY.contains(scene().name));

		if (sceneIndex >= SCENES.size()) {
			writeResults();
			enter(Phase.DONE, now);
			client.scheduleStop();
			return;
		}

		Scene scene = scene();
		run(client, "time set " + scene.time);
		if (scene.spot != null) {
			baseYaw = scene.yaw;
			basePitch = scene.pitch;
			run(client, String.format("tp @a %s %s %s", scene.spot, scene.yaw, scene.pitch));
		} else if (findLavaView(client)) {
			baseYaw = lavaYaw;
			basePitch = lavaPitch;
			run(client, String.format("tp @a %.3f %.3f %.3f %s %s", lavaEye.x, lavaEye.y - 1.62, lavaEye.z, lavaYaw, lavaPitch));
		} else {
			LOGGER.warn("[bench] no visible lava near the camera, skipping {}", scene.name);
			nextScene(client, now);
			return;
		}
		enter(Phase.SETTLE, now);
	}

	/** Holds the camera on the scene's angle, or moves it like a mouse would while measuring. */
	private static void hold(MinecraftClient client, long now) {
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}
		float yaw = baseYaw;
		float pitch = basePitch;
		if (phase == Phase.MEASURE) {
			double t = (now - phaseStartNs) / 1e9;
			switch (scene().motion) {
				case STILL -> {
				}
				case SWEEP -> yaw += (float) (25.0 * Math.sin(t * Math.PI));
				// Flicking the mouse left and right across the sunset, once a second.
				case TURN -> yaw += (float) (45.0 * Math.sin(t * 2.0 * Math.PI));
				case SPIN -> {
					// A fast, full turn like whipping the mouse around: 270 degrees a second, nodding up and down.
					yaw += (float) (270.0 * t);
					pitch = MathHelper.clamp(basePitch + (float) (25.0 * Math.sin(t * 3.0)), -89f, 89f);
				}
			}
		}
		yaw = MathHelper.wrapDegrees(yaw);
		player.setYaw(yaw);
		player.lastYaw = yaw;
		player.setPitch(pitch);
		player.lastPitch = pitch;
	}

	/** Finds an air block a few blocks from the nearest lava with a clear line of sight to it. */
	private static boolean findLavaView(MinecraftClient client) {
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) {
			return false;
		}
		BlockPos origin = player.getBlockPos();
		BlockPos.Mutable pos = new BlockPos.Mutable();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int r = 48;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -24; dy <= 24; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (world.getFluidState(pos).isIn(FluidTags.LAVA) && world.getBlockState(pos.up()).isAir()) {
						double d = dx * dx + dy * dy + dz * dz;
						if (d < bestDist) {
							bestDist = d;
							best = pos.toImmutable();
						}
					}
				}
			}
		}
		if (best == null) {
			return false;
		}

		Vec3d target = Vec3d.ofCenter(best);
		for (int dist = 4; dist <= 7; dist++) {
			for (int i = 0; i < 16; i++) {
				double a = i * Math.PI / 8;
				for (int up = 1; up <= 3; up++) {
					Vec3d eye = target.add(Math.cos(a) * dist, up, Math.sin(a) * dist);
					BlockPos eyePos = BlockPos.ofFloored(eye);
					if (!world.getBlockState(eyePos).isAir() || !world.getBlockState(eyePos.down()).isAir()) {
						continue;
					}
					BlockHitResult hit = world.raycast(new RaycastContext(eye, target, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
					if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(best)) {
						Vec3d d = target.subtract(eye);
						lavaEye = eye;
						lavaYaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
						lavaPitch = (float) (-(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI));
						LOGGER.info("[bench] lava at {} viewed from {} yaw {} pitch {}", best, eye, lavaYaw, lavaPitch);
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void run(MinecraftClient client, String... commands) {
		IntegratedServer server = client.getServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			for (String command : commands) {
				server.getCommandManager().parseAndExecute(server.getCommandSource(), command);
			}
		});
	}

	private static void writeResults() {
		StringBuilder out = new StringBuilder();
		out.append("== ").append(LABEL).append(" @ ").append(LocalDateTime.now())
			.append(" (tick rate ").append(TICK_RATE).append(REALISTIC ? ", realistic" : "").append(F3 ? ", F3" : "")
			.append(FULLSCREEN ? ", fullscreen" : "").append(")\n");
		for (String line : RESULTS) {
			out.append(line).append('\n');
		}
		try {
			Files.writeString(Path.of("bench-results.txt"), out.append('\n'), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.error("Could not write bench results", e);
		}
		LOGGER.info("[bench] results:\n{}", out);
	}

	private static void enter(Phase next, long now) {
		phase = next;
		phaseStartNs = now;
	}

	private static Scene scene() {
		return SCENES.get(sceneIndex);
	}

	private static Set<String> parseList(String value) {
		return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
	}

	private enum Phase { WAIT_WORLD, WARMUP, SETTLE, SHOT, MEASURE, DONE }

	private enum Motion { STILL, SWEEP, TURN, SPIN }

	private record Scene(String name, int time, String spot, float yaw, float pitch, Motion motion) {
	}
}
