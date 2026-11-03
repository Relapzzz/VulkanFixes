package com.relapzz.vulkanfixes;

import java.awt.AWTError;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a saved screenshot to the system clipboard when {@link VulkanFixesConfig#screenshotToClipboard} is on.
 * Called on the IO worker right after vanilla writes the PNG, see {@code ScreenshotRecorderMixin}.
 */
public final class ScreenshotClipboard {
	private static final Logger LOGGER = LoggerFactory.getLogger("VulkanFixes");

	private ScreenshotClipboard() {
	}

	/** Returns whether the image is on the clipboard now. */
	public static boolean copy(File png) {
		try {
			if (Util.getOperatingSystem() == Util.OperatingSystem.OSX) {
				return Mac.copy(png);
			}
			return Awt.copy(png);
		} catch (Exception | AWTError | LinkageError e) {
			LOGGER.warn("VulkanFixes: couldn't copy {} to the clipboard", png, e);
			return false;
		}
	}

	// AWT can't start next to GLFW's window on macOS, so there osascript reads the PNG instead.
	private static final class Mac {
		static boolean copy(File png) throws IOException, InterruptedException {
			Process process = new ProcessBuilder("osascript",
				"-e", "on run argv",
				"-e", "set the clipboard to (read (POSIX file (item 1 of argv)) as «class PNGf»)",
				"-e", "end run",
				png.getAbsolutePath())
				.redirectErrorStream(true)
				.start();
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroy();
				return false;
			}
			return process.exitValue() == 0;
		}
	}

	// A separate class so a Java runtime without java.desktop only fails here, at copy time.
	private static final class Awt {
		static boolean copy(File png) throws IOException {
			// Minecraft starts with java.awt.headless=true, which has no clipboard. Nothing in the game
			// touches AWT, so it still reads the property when the clipboard is first used.
			System.setProperty("java.awt.headless", "false");

			BufferedImage read = ImageIO.read(png);
			if (read == null) {
				return false;
			}
			// Drop alpha: some apps paste a transparent background as black or checkerboard.
			int width = read.getWidth();
			int height = read.getHeight();
			BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			rgb.setRGB(0, 0, width, height, read.getRGB(0, 0, width, height, null, 0, width), 0, width);

			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(rgb), null);
			return true;
		}
	}

	private record ImageSelection(BufferedImage image) implements Transferable {
		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] {DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!this.isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return this.image;
		}
	}
}
