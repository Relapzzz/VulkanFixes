package com.relapzz.vulkanfixes.bench;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Threshold;

/** Marks frames over 10 ms in a JFR recording, so CPU samples can be matched to the stutter. */
@Name("vulkanfixes.SlowFrame")
@Label("Slow frame")
@Category("VulkanFixes")
@Threshold("10 ms")
final class SlowFrameEvent extends Event {
	@Label("Scene")
	String scene;
}
