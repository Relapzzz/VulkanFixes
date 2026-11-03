package com.relapzz.vulkanfixes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a saved screenshot to the system clipboard when {@link VulkanFixesConfig#screenshotToClipboard} is on.
 * Called on the IO worker right after vanilla writes the PNG, see {@code ScreenshotRecorderMixin}.
 *
 * <p>Java's own clipboard (AWT) isn't used: Minecraft runs AWT headless, which has no clipboard, and once any mod
 * has touched AWT that can't be undone. Windows gets the clipboard API through JNA, which Minecraft ships; Linux and
 * macOS get the saved file through their clipboard commands.
 */
public final class ScreenshotClipboard {
	private static final Logger LOGGER = LoggerFactory.getLogger("VulkanFixes");

	private ScreenshotClipboard() {
	}

	/** Returns whether the image is on the clipboard now. */
	public static boolean copy(NativeImage image, File png) {
		try {
			return switch (Util.getOperatingSystem()) {
				case WINDOWS -> Windows.copy(image, png);
				case OSX -> run(List.of("osascript",
					"-e", "on run argv",
					"-e", "set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)",
					"-e", "end run",
					png.getAbsolutePath()), png);
				default -> System.getenv("WAYLAND_DISPLAY") != null
					? run(List.of("wl-copy", "--type", "image/png"), png)
					: run(List.of("xclip", "-selection", "clipboard", "-target", "image/png"), png);
			};
		} catch (Exception | LinkageError e) {
			LOGGER.warn("VulkanFixes: couldn't copy {} to the clipboard", png, e);
			return false;
		}
	}

	/** Runs a clipboard command with the PNG on its standard input. */
	private static boolean run(List<String> command, File png) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command)
			.redirectInput(png)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.destroy();
			return false;
		}
		return process.exitValue() == 0;
	}

	// Puts the PNG file itself (what browsers, Discord and Office paste) and a 32-bit bitmap (what Paint and older
	// apps paste) on the clipboard.
	private static final class Windows {
		private static final int CF_DIB = 8;
		private static final int GMEM_MOVEABLE = 0x0002;
		private static final int BITMAPINFOHEADER_SIZE = 40;

		interface User32 extends Library {
			User32 INSTANCE = Native.load("user32", User32.class);

			boolean OpenClipboard(Pointer owner);
			boolean EmptyClipboard();
			Pointer SetClipboardData(int format, Pointer memory);
			boolean CloseClipboard();
			int RegisterClipboardFormatW(WString name);
		}

		interface Kernel32 extends Library {
			Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

			Pointer GlobalAlloc(int flags, long bytes);
			Pointer GlobalLock(Pointer memory);
			boolean GlobalUnlock(Pointer memory);
			Pointer GlobalFree(Pointer memory);
		}

		static boolean copy(NativeImage image, File png) throws IOException, InterruptedException {
			byte[] pngBytes = Files.readAllBytes(png.toPath());
			int pngFormat = User32.INSTANCE.RegisterClipboardFormatW(new WString("PNG"));

			// EmptyClipboard makes the window passed here the owner. With no window, Windows may refuse the data.
			Pointer window = new Pointer(GLFWNativeWin32.glfwGetWin32Window(MinecraftClient.getInstance().getWindow().getHandle()));
			if (!open(window)) {
				return false;
			}
			try {
				if (!User32.INSTANCE.EmptyClipboard()) {
					return false;
				}
				boolean bitmap = set(CF_DIB, dib(image));
				boolean file = pngFormat != 0 && set(pngFormat, global(pngBytes));
				return bitmap || file;
			} finally {
				User32.INSTANCE.CloseClipboard();
			}
		}

		/** Another app may hold the clipboard for a moment, so try for about half a second. */
		private static boolean open(Pointer window) throws InterruptedException {
			for (int attempt = 0; attempt < 10; attempt++) {
				if (User32.INSTANCE.OpenClipboard(window)) {
					return true;
				}
				Thread.sleep(50);
			}
			return false;
		}

		/** Hands the memory to the clipboard, which then owns it; frees it if the clipboard refuses. */
		private static boolean set(int format, Pointer memory) {
			if (memory == null) {
				return false;
			}
			if (User32.INSTANCE.SetClipboardData(format, memory) == null) {
				Kernel32.INSTANCE.GlobalFree(memory);
				return false;
			}
			return true;
		}

		private static Pointer global(byte[] bytes) {
			Pointer memory = Kernel32.INSTANCE.GlobalAlloc(GMEM_MOVEABLE, bytes.length);
			if (memory == null) {
				return null;
			}
			Pointer data = Kernel32.INSTANCE.GlobalLock(memory);
			data.write(0, bytes, 0, bytes.length);
			Kernel32.INSTANCE.GlobalUnlock(memory);
			return memory;
		}

		/** A BITMAPINFOHEADER followed by bottom-up rows of BGRA pixels. */
		private static Pointer dib(NativeImage image) {
			int width = image.getWidth();
			int height = image.getHeight();
			int[] argb = image.copyPixelsArgb();
			Pointer memory = Kernel32.INSTANCE.GlobalAlloc(GMEM_MOVEABLE, BITMAPINFOHEADER_SIZE + 4L * width * height);
			if (memory == null) {
				return null;
			}
			Pointer data = Kernel32.INSTANCE.GlobalLock(memory);
			data.setInt(0, BITMAPINFOHEADER_SIZE);
			data.setInt(4, width);
			data.setInt(8, height); // positive: bottom-up, which every app reads
			data.setShort(12, (short)1); // planes
			data.setShort(14, (short)32); // bits per pixel
			data.setInt(16, 0); // BI_RGB
			data.setInt(20, 4 * width * height);
			data.setInt(24, 0);
			data.setInt(28, 0);
			data.setInt(32, 0);
			data.setInt(36, 0);
			// Little-endian ARGB ints are BGRA bytes, the DIB's pixel order. Alpha is forced opaque, since some apps
			// read the fourth byte as transparency.
			int[] row = new int[width];
			for (int y = 0; y < height; y++) {
				int source = (height - 1 - y) * width;
				for (int x = 0; x < width; x++) {
					row[x] = argb[source + x] | 0xFF000000;
				}
				data.write(BITMAPINFOHEADER_SIZE + 4L * y * width, row, 0, width);
			}
			Kernel32.INSTANCE.GlobalUnlock(memory);
			return memory;
		}
	}
}
