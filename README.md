# VulkanFixes

A small client-side Fabric mod that fixes the heavy frame drops [VulkanMod](https://modrinth.com/mod/vulkanmod) shows when you **turn the camera toward a sunrise or sunset in exclusive fullscreen**. It doesn't change how anything looks.

| Turning across the sunset, 1080p fullscreen, Iris Xe, MC 1.21.11 | FPS | Avg frame | Frames over 10 ms |
|---|---|---|---|
| VulkanMod 0.6.8 | 68 | 14.7 ms | 459 of 546 |
| VulkanMod 0.6.8 + VulkanFixes | 408 | 2.5 ms | 0 |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) and VulkanMod for your Minecraft version.
2. From [Releases](https://github.com/Relapzzz/VulkanFixes/releases), download the jar for your Minecraft version and put it in your `mods` folder.

| Minecraft | Jar | Built against VulkanMod |
|---|---|---|
| 1.21, 1.21.1 | `vulkanfixes-<version>+mc1.21-1.21.1.jar` | 0.6.7 |
| 1.21.2 – 1.21.4 | `vulkanfixes-<version>+mc1.21.2-1.21.4.jar` | 0.5.3 / 0.5.6 |
| 1.21.5 | `vulkanfixes-<version>+mc1.21.5.jar` | 0.5.6 |
| 1.21.9, 1.21.10 | `vulkanfixes-<version>+mc1.21.9-1.21.10.jar` | 0.6.6 |
| 1.21.11 | `vulkanfixes-<version>+mc1.21.11.jar` | 0.6.8 |

There's no jar for 1.21.6–1.21.8 because VulkanMod has no release for those versions.

You need Fabric Loader 0.15.11 or newer and Java 21. Fabric API isn't needed.

Only the 1.21.11 jar has been tested in-game. The others were checked against each Minecraft version's code without launching the game (see [Checking a jar against Minecraft versions](#checking-a-jar-against-minecraft-versions)). If the fix fails at runtime on one of them, it logs an error once and falls back to vanilla behavior instead of crashing.

## What it fixes

Every frame, Minecraft clears the world framebuffer to the fog color. While the sun is near the horizon, vanilla blends that fog color toward the orange sky depending on how directly you face the sun. When you turn toward the sunset, the clear color therefore changes on every frame.

VulkanMod passes that color straight to `vkCmdClearAttachments`. On Intel Xe graphics in exclusive fullscreen, a clear color that changes every frame stalls the GPU for 10 ms or more per frame. If the color stays the same, the clear is nearly free.

VulkanFixes clears with a color that only follows the fog after the fog has stayed the same for 20 frames. While the fog is changing, the mod paints the exact fog color over the cleared frame with one full-screen draw, before anything else is drawn. The values that end up in the framebuffer are the same ones the vanilla clear writes, so frames are pixel-identical to vanilla. On 1.21.11 this was checked by comparing screenshots against vanilla.

Start the game with `-Dvulkanfixes.fogClear=false` to turn the fix off for comparison.

## Motion blur (experimental, 1.21.11 only)

An optional camera motion blur that works under VulkanMod. It's off by default. Turn it on in [Mod Menu](https://modrinth.com/mod/modmenu) → VulkanFixes → **Motion Blur (Experimental)**. The setting is saved in `config/vulkanfixes.json`. Mod Menu is optional; without it, edit that file.

- Only turning the camera blurs. Walking doesn't, and the hand, held item and HUD always stay sharp.
- No frames are held back, so it adds no input latency.
- **Blur Strength** (0.1 to 2, default 1): at 1 each frame is smeared across the camera motion of half a monitor refresh, like a film camera's shutter. The streak length doesn't depend on fps.
- It costs nothing while the camera is still or turning slowly. While turning, each frame takes about 0.6 to 0.85 ms longer:

| 1080p fullscreen, Iris Xe, MC 1.21.11 | Blur off | Blur on |
|---|---|---|
| Standing still | 483 fps | 467 fps |
| Slow sweep | 501 fps | 375–389 fps |
| Fast spin (270°/s) | 406 fps | 307–321 fps |
| Turning across the sunset | 372 fps | 272–306 fps |

How it works: after the world is drawn, [`MotionBlur`](versions/1.21.11/src/main/java/com/relapzz/vulkanfixes/MotionBlur.java) works out from last frame's camera rotation where each pixel was on screen, and [`motion_blur.fsh`](versions/1.21.11/src/main/resources/assets/vulkanfixes/shaders/core/motion_blur.fsh) averages along that line at half resolution. [`motion_blur_composite.fsh`](versions/1.21.11/src/main/resources/assets/vulkanfixes/shaders/core/motion_blur_composite.fsh) then blends the result over the sharp frame wherever pixels moved at least a pixel. Under VulkanMod the world is drawn straight into the swapchain image, which can't be read and drawn into in the same pass, hence the two passes.

## Project layout

Minecraft's rendering API changed several times during 1.21, so each range of versions has its own small subproject in `versions/`, named after the Minecraft version it compiles against:

| Subproject | Covers | Clear hook | Fill |
|---|---|---|---|
| `versions/1.21.1` | 1.21 – 1.21.1 | `BackgroundRenderer.render` sets the clear color, `WorldRenderer.render` clears | render layer with vanilla's `position` shader |
| `versions/1.21.4` | 1.21.2 – 1.21.4 | frame graph clear step `WorldRenderer.method_62218`: `RenderSystem.clearColor` + `clear` | render layer with vanilla's `position` shader |
| `versions/1.21.5` | 1.21.5 | `method_62218`: `CommandEncoder.clearColorAndDepthTextures` | render pass with vanilla's `core/position` pipeline, the same way vanilla draws the sky |
| `versions/1.21.10` | 1.21.9 – 1.21.10 | `method_62218`: `CommandEncoder.clearColorAndDepthTextures` | full-screen triangle with [`fog_fill.fsh`](versions/1.21.11/src/main/resources/assets/vulkanfixes/shaders/core/fog_fill.fsh) |
| `versions/1.21.11` | 1.21.11 | same as 1.21.10 | same as 1.21.10; this subproject also holds the bench harness |

Each subproject has:

- `gradle.properties`, with `minecraft_version`, `yarn_mappings`, `minecraft_range` (the `minecraft` dependency in `fabric.mod.json`) and `minecraft_label` (the jar name suffix);
- `FogFill.java`, which paints the fog color with that version's API;
- `mixin/`, the clear hooks;
- `vulkanfixes.accesswidener`, for the few vanilla internals the fill needs.

Code shared by every version is in `common/`. That's [`SteadyClearColor`](common/src/main/java/com/relapzz/vulkanfixes/SteadyClearColor.java), which decides the clear color and handles the fall-back, plus `fabric.mod.json`. The root `build.gradle` holds the build setup the subprojects share.

## Building

Requires JDK 21.

```bash
git clone https://github.com/Relapzzz/VulkanFixes.git
cd VulkanFixes
./gradlew build
```

The jars end up in `versions/<subproject>/build/libs/`. To build one version only, run for example `./gradlew :1.21.5:build`. On Windows, use `gradlew.bat`.

## Updating

- **New patch of a supported version, or a new VulkanMod release:** nothing to change unless Minecraft's code changed. Run the compatibility check (below) for the new version, then widen `minecraft_range` in that subproject's `gradle.properties` if it passes.
- **New Minecraft version:**
  1. Copy the subproject closest to it, e.g. `versions/1.21.11` to `versions/<new version>`. Settings picks up new folders automatically.
  2. Set `minecraft_version`, `yarn_mappings`, `minecraft_range` and `minecraft_label`. Current mappings are listed at <https://fabricmc.net/develop>.
  3. Build and fix whatever no longer compiles.
  4. Check the mixin targets: every version hooks the step that clears the world framebuffer with the fog color. Lambda names like `method_62218` can change between versions, so look in the decompiled `WorldRenderer` for the method that clears `client.getFramebuffer()` with the fog color.
- **Releasing:** bump `mod_version` in the root `gradle.properties`, then commit and push a tag:

  ```bash
  git commit -am "Release 1.1.1"
  git tag v1.1.1
  git push origin main v1.1.1
  ```

  GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml)) builds every subproject and publishes a release with all the jars. The tag has to match `mod_version`, or the release step fails. Every other push is just built, and the jars are attached to the workflow run.

## Checking a jar against Minecraft versions

[`tools/CompatCheck.java`](tools/CompatCheck.java) checks a built jar against a Minecraft version's code without launching the game. It verifies that:

- every Minecraft class, method and field the mod uses exists;
- every mixin target method exists and contains the call it hooks;
- every access widener entry exists.

It needs ASM and the Minecraft jar in intermediary names, both of which are already in your Gradle cache after a build:

```bash
java -cp "asm-9.x.jar;asm-tree-9.x.jar" tools/CompatCheck.java \
  versions/1.21.4/build/libs/vulkanfixes-1.1.0+mc1.21.2-1.21.4.jar \
  ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary/1.21.2-<...>/minecraft-merged-intermediary-1.21.2-<...>.jar
```

Use `:` instead of `;` in the classpath on Linux and macOS. It prints `OK`, or one `PROBLEM` line per missing piece. Loom only downloads the Minecraft versions some subproject builds against. To get another one, temporarily set `minecraft_version` and `yarn_mappings` in a subproject and run `./gradlew :<subproject>:compileJava`.

Every jar in 1.1.0 passed this check for each Minecraft version in its range.

## Bench harness

`versions/1.21.11/src/bench` is a dev-only mod that never goes into the release jars. It loads a world, replays fixed camera scenes, and logs frame times and GPU fence waits to `run/bench-results.txt`:

```bash
./gradlew :1.21.11:runBench -PbenchLabel=test -PbenchScenes=dusk_west_turn,dusk_east_turn -PbenchRealistic -PbenchFullscreen
```

- Put a singleplayer world at `run/saves/bench`, and any mods you want to test alongside in `run/mods`. The scene positions in `BenchClient` belong to the original test world, so edit `SUNSET_SPOT` and `CAVE_SPOT` for yours.
- `-PbenchFullscreen` uses exclusive fullscreen, which is the only mode where the sunset stall shows up.
- `-PbenchRealistic` plays in creative with F3 open. Without it the bench uses a bare spectator camera.
- `-PbenchNoFix` turns the fix off. `-PbenchAlwaysFill` forces the fill draw on every frame for screenshot comparisons.
- `-PbenchMotionShotMs=1300` also takes a screenshot that long into each scene's measurement, while the camera moves, to look at motion blur. It stalls one frame, so leave it off when the numbers matter. Motion blur follows `run/config/vulkanfixes.json`.
- Put [Mod Menu](https://modrinth.com/mod/modmenu) in `run/mods` to open the config screen in dev runs.
- `-PbenchJvm="-XX:+UseZGC -XX:+ZGenerational"`, `-PbenchXmx=3072M` and `-PbenchJfr` set JVM flags, heap size, and a JFR recording.

## License

[CC0 1.0](LICENSE): public domain.
