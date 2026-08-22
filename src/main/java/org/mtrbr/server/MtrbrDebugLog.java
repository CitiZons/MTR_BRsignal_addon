package org.mtrbr.server;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Append-only server audit trail for dispatcher and signal decisions. */
public final class MtrbrDebugLog {
	private static final String FILE_NAME = "mtrbr-debug.log";

	private MtrbrDebugLog() {
	}

	public static synchronized void event(String category, String detail) {
		final String line = Instant.now() + " [" + category + "] " + detail.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
		System.out.print("[MTRBR-DEBUG] " + line);
		try {
			final Path logFile = FMLPaths.GAMEDIR.get().resolve("logs").resolve(FILE_NAME);
			Files.createDirectories(logFile.getParent());
			Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			System.err.println("[MTRBR-DEBUG] Could not write " + FILE_NAME + ": " + exception.getMessage());
		}
	}
}
