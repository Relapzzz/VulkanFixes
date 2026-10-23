package com.relapzz.vulkanfixes.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Collects frame durations and GPU fence waits over one measurement window. */
public final class FrameStats {
	static final long SPIKE_NS = 10_000_000L;
	private static final int MAX_LOGGED_SPIKES = 40;

	private static long fenceStartNs;
	private static long fenceTotalNs;
	private static final List<Stage> FRAME_STAGES = new ArrayList<>();

	private final long[] frameNs = new long[1 << 17];
	private final List<String> spikes = new ArrayList<>();
	private int count;
	private long fenceAtStartNs;
	private long beginNs;
	private long elapsedNs;

	public static void fenceStart() {
		fenceStartNs = System.nanoTime();
	}

	public static void fenceEnd() {
		fenceTotalNs += System.nanoTime() - fenceStartNs;
	}

	public static void stage(String name, long durationNs) {
		FRAME_STAGES.add(new Stage(name, durationNs));
	}

	/** Called at the start of every frame: the stages collected so far belong to the frame that just ended. */
	static void endFrame() {
		FRAME_STAGES.clear();
	}

	void begin(long now) {
		count = 0;
		spikes.clear();
		fenceAtStartNs = fenceTotalNs;
		beginNs = now;
	}

	void record(long ns, long now) {
		if (count < frameNs.length) {
			frameNs[count++] = ns;
		}
		elapsedNs = now - beginNs;
		if (ns > SPIKE_NS && spikes.size() < MAX_LOGGED_SPIKES) {
			String stages = FRAME_STAGES.stream()
				.filter(s -> s.ns > 1_000_000L)
				.sorted(Comparator.comparingLong(Stage::ns).reversed())
				.limit(6)
				.map(s -> String.format("%s %.1f", s.name, s.ns / 1e6))
				.collect(Collectors.joining(", "));
			spikes.add(String.format("  +%5.2fs %6.1f ms  [%s]", elapsedNs / 1e9, ns / 1e6, stages));
		}
	}

	List<String> spikes() {
		return spikes;
	}

	String summary() {
		if (count == 0) {
			return "no frames";
		}
		long[] sorted = Arrays.copyOf(frameNs, count);
		Arrays.sort(sorted);
		long total = 0;
		int over10 = 0;
		int over33 = 0;
		for (long ns : sorted) {
			total += ns;
			if (ns > SPIKE_NS) {
				over10++;
			}
			if (ns > 33_300_000L) {
				over33++;
			}
		}
		double avgMs = total / 1e6 / count;
		double fenceMs = (fenceTotalNs - fenceAtStartNs) / 1e6 / count;
		return String.format(
			"fps %6.1f | avg %6.2f ms | p50 %6.2f | p95 %6.2f | p99 %6.2f | max %6.2f | >10ms %4d | >33ms %3d | gpu-wait %5.2f ms/frame | frames %d",
			1000.0 / avgMs, avgMs, pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99), sorted[count - 1] / 1e6, over10, over33, fenceMs, count);
	}

	private double pct(long[] sorted, double p) {
		return sorted[Math.min(count - 1, (int) (p * count))] / 1e6;
	}

	private record Stage(String name, long ns) {
	}
}
