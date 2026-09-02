package org.mtrbr.server;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Append-only server audit trail for dispatcher and signal decisions. */
public final class MtrbrDebugLog {
	private static final DateTimeFormatter STARTUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
	private static final String STARTUP_PREFIX = STARTUP_FORMAT.format(Instant.now());
	private static final boolean DEBUG = Boolean.getBoolean("mtrbr.debug");
	private static final long WARNING_REPEAT_INTERVAL_MILLIS = 5_000;
	private static final int MAX_WARNING_KEYS = 1_024;
	private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
	private static final Map<String, Long> LAST_WARNING_TIMES = new HashMap<>();
	private static boolean rotationFailureReported;
	private static Path activeLogFile;
	private static long activeBytes;
	private static int volume;

	private MtrbrDebugLog() {
	}

	public static synchronized void event(String category, String detail) {
		if (!DEBUG && !isWarning(category)) return;
		if (!DEBUG && isRepeatedWarning(category, detail)) return;
		final String line = Instant.now() + " [" + category + "] " + detail.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
		System.out.print("[MTRBR-DEBUG] " + line);
		try {
			final Path logDirectory = FMLPaths.GAMEDIR.get().resolve("logs");
			Files.createDirectories(logDirectory);
			final byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
			ensureActiveFile(logDirectory);
			rotateIfNeeded(logDirectory, bytes.length);
			Files.write(activeLogFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			activeBytes += bytes.length;
		} catch (IOException exception) {
			System.err.println("[MTRBR-DEBUG] Could not write debug log: " + exception.getMessage());
		}
	}

	private static void ensureActiveFile(Path logDirectory) throws IOException {
		if (activeLogFile != null) return;
		activeLogFile = logDirectory.resolve(STARTUP_PREFIX + ".log");
		activeBytes = Files.exists(activeLogFile) ? Files.size(activeLogFile) : 0;
		while (activeBytes >= MAX_FILE_BYTES) {
			volume++;
			activeLogFile = logDirectory.resolve(STARTUP_PREFIX + "-" + volume + ".log");
			activeBytes = Files.exists(activeLogFile) ? Files.size(activeLogFile) : 0;
		}
	}

	private static void rotateIfNeeded(Path logDirectory, long incomingBytes) throws IOException {
		if (activeLogFile == null || activeBytes + incomingBytes <= MAX_FILE_BYTES) return;
		try {
			volume++;
			activeLogFile = logDirectory.resolve(STARTUP_PREFIX + "-" + volume + ".log");
			activeBytes = Files.exists(activeLogFile) ? Files.size(activeLogFile) : 0;
			rotationFailureReported = false;
		} catch (IOException exception) {
			if (!rotationFailureReported) {
				rotationFailureReported = true;
				System.err.println("[MTRBR-DEBUG] Could not rotate debug log: " + exception.getMessage());
			}
		}
	}

	private static boolean isRepeatedWarning(String category, String detail) {
		final long now = System.currentTimeMillis();
		final String key = category + '\n' + detail;
		final Long previous = LAST_WARNING_TIMES.put(key, now);
		if (LAST_WARNING_TIMES.size() > MAX_WARNING_KEYS) LAST_WARNING_TIMES.clear();
		return previous != null && now - previous < WARNING_REPEAT_INTERVAL_MILLIS;
	}

	private static boolean isWarning(String category) {
		if (category.startsWith("WEB-")) return true;
		return category.contains("INVALID") || category.contains("FAIL") || category.contains("CONFLICT")
				|| category.contains("DEADLOCK") || category.contains("STALE") || category.contains("RECOVERY")
				|| category.contains("REJECT") || category.contains("MISMATCH") || category.contains("OVEREXTEND")
				|| category.contains("EXHAUSTED") || category.contains("LOCK-OWNER") || category.contains("RESOURCE")
				|| category.equals("MTRBR-PROTECTION-REGENERATE") || category.equals("MTRBR-ROUTE-PROJECTION")
				|| category.equals("MTRBR-MAPPING-DIAGNOSTIC");
	}
}
