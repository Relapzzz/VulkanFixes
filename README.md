# VulkanFixes

A small client-side Fabric mod that fixes the heavy frame drops [VulkanMod](https://modrinth.com/mod/vulkanmod) shows when you **turn the camera toward a sunrise or sunset in exclusive fullscreen**. It doesn't change how anything looks.

| Turning across the sunset, 1080p fullscreen, Iris Xe | FPS | Avg frame | Frames over 10 ms |
|---|---|---|---|
| VulkanMod 0.6.8 | 68 | 14.7 ms | 459 of 546 |
| VulkanMod 0.6.8 + VulkanFixes | 408 | 2.5 ms | 0 |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) and VulkanMod for your Minecraft version.
2. Download `vulkanfixes-<version>.jar` from [Releases](https://github.com/Relapzzz/VulkanFixes/releases) and put it in your `mods` folder.

Fabric API isn't needed.

| VulkanFixes | Minecraft | VulkanMod | Fabric Loader | Java |
|---|---|---|---|---|
| 1.0.0 | 1.21.11 | 0.6.8 | 0.19.3+ | 21+ |

## What it fixes

Every frame, Minecraft clears the world framebuffer to the fog color. While the sun is near the horizon, vanilla blends that fog color toward the orange sky depending on how directly you face the sun (`AtmosphericFogModifier`). When you turn toward the sunset, the clear color therefore changes on every frame.

VulkanMod passes that color straight to `vkCmdClearAttachments`. On Intel Xe graphics in exclusive fullscreen, a clear color that changes every frame stalls the GPU for 10 ms or more per frame. If the color stays the same, the clear is nearly free.

VulkanFixes clears with a color that only follows the fog after the fog has stayed the same for 20 frames. While the fog is changing, the mod draws a full-screen triangle with the exact fog color right after the clear, before anything else is drawn. The 8-bit values that end up in the framebuffer are the same ones the vanilla clear writes, so frames are pixel-identical to vanilla. The bench harness below checked this by comparing screenshots against vanilla.

Code:

- [`FogClear`](src/main/java/com/relapzz/vulkanfixes/FogClear.java): the clear-color logic and the fill draw.
- [`WorldRendererMixin`](src/main/java/com/relapzz/vulkanfixes/mixin/WorldRendererMixin.java): wraps vanilla's clear call. It uses a MixinExtras `@WrapOperation`, so other mods can hook the same call.
- [`fog_fill.fsh`](src/main/resources/assets/vulkanfixes/shaders/core/fog_fill.fsh): writes the fog color. It runs with vanilla's `core/screenquad` vertex shader.

Start the game with `-Dvulkanfixes.fogClear=false` to turn the fix off for comparison.

## Building

Requires JDK 21.

```bash
git clone https://github.com/Relapzzz/VulkanFixes.git
cd VulkanFixes
./gradlew build
```

The mod jar is `build/libs/vulkanfixes-<version>.jar`. On Windows, use `gradlew.bat build`.

## Updating to a new Minecraft or VulkanMod version

1. In `gradle.properties`, update `minecraft_version`, `yarn_mappings`, `loader_version`, `fabric_api_version` and `vulkanmod_version`. Current values are listed at <https://fabricmc.net/develop> and on [VulkanMod's Modrinth page](https://modrinth.com/mod/vulkanmod/versions).
2. In `src/main/resources/fabric.mod.json`, update the `minecraft` and `vulkanmod` entries under `depends`.
3. Run `./gradlew build`, then start the game and check the mixin still applies. The mixin targets `WorldRenderer.method_62218`, the frame graph's world-framebuffer clear step, which calls `CommandEncoder.clearColorAndDepthTextures` with the fog color. That lambda's name can change between Minecraft versions. The injector is required, so if it no longer matches, the game fails at startup with a mixin error instead of silently doing nothing. To find the new name, look in the decompiled `WorldRenderer` for the lambda that clears `client.getFramebuffer()` with `ColorHelper.fromFloats(0, fog.x, fog.y, fog.z)`.
4. Bump `version` in `gradle.properties` and release (see below).

## Releasing

Releases are built by GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml)):

```bash
git commit -am "Update to 1.21.x"
git tag v1.0.1
git push origin main v1.0.1
```

Pushing a `v*` tag builds the jar and publishes a GitHub release with it attached. The tag has to match `version` in `gradle.properties`, or the release step fails. Every other push is just built, and the jar is attached to the workflow run.

## Bench harness

`src/bench` is a dev-only mod that never goes into the release jar. It loads a world, replays fixed camera scenes, and logs frame times and GPU fence waits to `run/bench-results.txt`:

```bash
./gradlew runBench -PbenchLabel=test -PbenchScenes=dusk_west_turn,dusk_east_turn -PbenchRealistic -PbenchFullscreen
```

- Put a singleplayer world at `run/saves/bench`, and any mods you want to test alongside in `run/mods`. The scene positions in `BenchClient` belong to the original test world, so edit `SUNSET_SPOT` and `CAVE_SPOT` for yours.
- `-PbenchFullscreen` uses exclusive fullscreen, which is the only mode where the sunset stall shows up.
- `-PbenchRealistic` plays in creative with F3 open. Without it the bench uses a bare spectator camera.
- `-PbenchNoFix` turns the fix off. `-PbenchAlwaysFill` forces the fill draw on every frame for screenshot comparisons.
- `-PbenchJvm="-XX:+UseZGC -XX:+ZGenerational"`, `-PbenchXmx=3072M` and `-PbenchJfr` set JVM flags, heap size, and a JFR recording.

## License

[CC0 1.0](LICENSE): public domain.
