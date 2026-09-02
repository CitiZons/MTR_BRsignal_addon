package org.mtrbr.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.server.MtrbrDebugLog;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.List;
import java.util.Map;

/** Temporary operator surface; a later dispatcher panel calls the same server API. */
public final class MTRBRCommands {
	private MTRBRCommands() {
	}

	public static void register(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("mtrbr")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("manual_override")
						.then(Commands.argument("vehicle_code", StringArgumentType.word())
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> setManualOverride(context.getSource().getLevel(), StringArgumentType.getString(context, "vehicle_code"), BoolArgumentType.getBool(context, "enabled"), context.getSource())))))
				.then(Commands.literal("priority")
						.then(Commands.argument("vehicle_code", StringArgumentType.word())
								.then(Commands.argument("value", IntegerArgumentType.integer(0, 1000))
										.executes(context -> setPriority(context.getSource().getLevel(), StringArgumentType.getString(context, "vehicle_code"), IntegerArgumentType.getInteger(context, "value"), context.getSource())))))
				.then(Commands.literal("revoke_pending")
						.then(Commands.argument("vehicle_code", StringArgumentType.word())
								.executes(context -> revokePending(context.getSource().getLevel(), StringArgumentType.getString(context, "vehicle_code"), context.getSource()))))
				.then(Commands.literal("requests")
						.executes(context -> listRequests(context.getSource().getLevel(), context.getSource())))
				.then(Commands.literal("web_token")
						.then(Commands.literal("generate")
								.executes(context -> issueWebToken(context.getSource())))
						.then(Commands.literal("list")
								.executes(context -> listWebTokens(context.getSource())))
						.then(Commands.literal("revocation")
								.then(Commands.argument("number", IntegerArgumentType.integer(1, 5))
										.executes(context -> revokeWebToken(context.getSource(), IntegerArgumentType.getInteger(context, "number"))))))
				.then(Commands.literal("approve")
						.then(Commands.argument("vehicle_code", StringArgumentType.word())
								.executes(context -> approve(context.getSource().getLevel(), StringArgumentType.getString(context, "vehicle_code"), context.getSource()))))
				.then(Commands.literal("audit")
						.executes(context -> audit(context.getSource().getLevel(), context.getSource())))
				.then(Commands.literal("protection")
						.then(Commands.literal("initialize")
								.executes(context -> initializeProtection(context.getSource().getLevel(), context.getSource())))
						.then(Commands.literal("regenerate")
								.executes(context -> regenerateProtection(context.getSource().getLevel(), context.getSource())))
						.then(Commands.literal("set")
								.then(Commands.argument("signal_pos", BlockPosArgument.blockPos())
										.then(Commands.argument("reverse", BoolArgumentType.bool())
												.then(Commands.argument("rail_ids", StringArgumentType.greedyString())
														.executes(context -> setProtection(context.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(context, "signal_pos"), BoolArgumentType.getBool(context, "reverse"), StringArgumentType.getString(context, "rail_ids"), context.getSource()))))))
						.then(Commands.literal("clear")
								.then(Commands.argument("signal_pos", BlockPosArgument.blockPos())
										.then(Commands.argument("reverse", BoolArgumentType.bool())
												.executes(context -> clearProtection(context.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(context, "signal_pos"), BoolArgumentType.getBool(context, "reverse"), context.getSource())))))
						.then(Commands.literal("show")
								.then(Commands.argument("signal_pos", BlockPosArgument.blockPos())
										.then(Commands.argument("reverse", BoolArgumentType.bool())
												.executes(context -> showProtection(context.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(context, "signal_pos"), BoolArgumentType.getBool(context, "reverse"), context.getSource())))))));
	}

	private static int issueWebToken(net.minecraft.commands.CommandSourceStack source) {
		final var player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("This command must be run by an in-game operator."));
			return 0;
		}
		final int webserverPort = org.mtr.mod.Init.getServerPort();
		if (webserverPort <= 0) {
			player.sendSystemMessage(Component.literal("MTR web server is disabled."));
			return 0;
		}
		final String host;
		if (player.server.isDedicatedServer()) {
			host = org.mtrbr.config.MtrbrServerConfig.webPublicHost();
			if (host.isBlank()) {
				player.sendSystemMessage(Component.literal("MTRBR web_public_host is not configured."));
				return 0;
			}
		} else {
			host = "localhost";
		}
		final org.mtrbr.web.WebSessionManager.IssueResult issued = org.mtrbr.web.WebSessionManager.issue(player);
		if (!issued.issued()) {
			player.sendSystemMessage(Component.literal("Web token limit reached (5). Revoke an existing token first."));
			return 0;
		}
		final String url = "http://" + host + ":" + webserverPort + "/mtrbr/?token=" + issued.token();
		player.sendSystemMessage(Component.literal("Web dispatch URL: ")
				.append(Component.literal(url).withStyle(style -> style
						.withColor(net.minecraft.ChatFormatting.AQUA)
						.withUnderlined(true)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, url)))));
		return 1;
	}

	private static int listWebTokens(net.minecraft.commands.CommandSourceStack source) {
		final var player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("This command must be run by an in-game operator."));
			return 0;
		}
		final int webserverPort = org.mtr.mod.Init.getServerPort();
		if (webserverPort <= 0) {
			player.sendSystemMessage(Component.literal("MTR web server is disabled."));
			return 0;
		}
		final String host = player.server.isDedicatedServer() ? org.mtrbr.config.MtrbrServerConfig.webPublicHost() : "localhost";
		if (host.isBlank()) {
			player.sendSystemMessage(Component.literal("MTRBR web_public_host is not configured."));
			return 0;
		}
		final List<org.mtrbr.web.WebSessionManager.TokenView> tokens = org.mtrbr.web.WebSessionManager.list(player.getUUID());
		if (tokens.isEmpty()) {
			player.sendSystemMessage(Component.literal("No web tokens."));
			return 1;
		}
		for (int index = 0; index < tokens.size(); index++) {
			final var entry = tokens.get(index);
			final String url = "http://" + host + ":" + webserverPort + "/mtrbr/?token=" + entry.token();
			player.sendSystemMessage(Component.literal((index + 1) + ". [" + entry.status() + "] " + url));
		}
		return 1;
	}

	private static int revokeWebToken(net.minecraft.commands.CommandSourceStack source, int number) {
		final var player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("This command must be run by an in-game operator."));
			return 0;
		}
		if (!org.mtrbr.web.WebSessionManager.revoke(player.getUUID(), number)) {
			source.sendFailure(Component.literal("No web token with number " + number + "."));
			return 0;
		}
		player.sendSystemMessage(Component.literal("Web token " + number + " revoked. Remaining tokens were renumbered."));
		return 1;
	}

	private static int setProtection(ServerLevel level, BlockPos signalPos, boolean reverse, String rawRailIds, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		final String faceId = org.mtrbr.server.SignalTopology.id(signalPos, reverse);
		final List<String> railIds = java.util.Arrays.stream(rawRailIds.split("[,\\s]+"))
				.map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
		final List<String> unknown = railIds.stream().filter(id -> !simulator.railIdMap.containsKey(id)).toList();
		if (!unknown.isEmpty()) {
			source.sendFailure(Component.literal("Unknown MTR Rail ID(s): " + String.join(", ", unknown)));
			return 0;
		}
		final SignalBlockSavedData saved = SignalBlockSavedData.get(level);
		final String blockId = saved.getBlockId(faceId);
		if (blockId.isBlank()) {
			source.sendFailure(Component.literal("No canonical A->B block exists for " + faceId + "; regenerate protection first."));
			return 0;
		}
		saved.setBlock(faceId, blockId, railIds);
		MtrbrDebugLog.event("TOPOLOGY", "protection-set face=" + faceId + " block=" + blockId + " rails=" + railIds + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Protection binding saved for " + faceId + " (" + blockId + "): " + String.join(", ", railIds)), false);
		return 1;
	}

	/** Persists only currently missing mappings; it never replaces an operator-approved topology. */
	private static int initializeProtection(ServerLevel level, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) return 0;
		final SignalBlockSavedData saved = SignalBlockSavedData.get(level);
		final Map<String, RouteRequestManager.GeneratedProtection> generated = RouteRequestManager.getGeneratedProtectionBlocks(simulator,
				org.mtrbr.server.ServerAspectManager.getFaceSnapshot(simulator.dimension));
		for (final Map.Entry<String, RouteRequestManager.GeneratedProtection> entry : generated.entrySet()) {
			final RouteRequestManager.GeneratedProtection protection = entry.getValue();
			final String blockAudit = "face=" + entry.getKey() + " blockId=" + protection.blockId() + " nextBoundary=" + protection.boundaryId() + " railCount=" + protection.railIds().size();
			MtrbrDebugLog.event("BLOCK", blockAudit);
			System.out.println("[MTRBR-BLOCK] " + blockAudit);
		}
		final int written = saved.addGeneratedBlocks(generated);
		final int savedCount = written;
		MtrbrDebugLog.event("TOPOLOGY", "protection-initialize written=" + written + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Saved " + savedCount + " missing SignalBlock mappings from observed paths."), false);
		return written > 0 ? 1 : 0;
	}

	private static int regenerateProtection(ServerLevel level, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) return 0;
		final org.mtrbr.server.ServerAspectManager.FaceSnapshot topology = org.mtrbr.server.ServerAspectManager.getFaceSnapshot(simulator.dimension);
		final Map<String, RouteRequestManager.GeneratedProtection> generated = RouteRequestManager.getGeneratedProtectionBlocks(simulator,
				topology);
		final Map<String, RouteRequestManager.GeneratedProtection> generatedOccurrences = RouteRequestManager.getGeneratedOccurrenceProtectionBlocks(simulator,
				topology);
		for (final Map.Entry<String, RouteRequestManager.GeneratedProtection> entry : generated.entrySet()) {
			final RouteRequestManager.GeneratedProtection protection = entry.getValue();
			final String blockAudit = "face=" + entry.getKey() + " blockId=" + protection.blockId() + " nextBoundary=" + protection.boundaryId() + " railCount=" + protection.railIds().size();
			MtrbrDebugLog.event("BLOCK", blockAudit);
			System.out.println("[MTRBR-BLOCK] " + blockAudit);
		}
		for (final String faceId : topology.faces().keySet()) {
			if (!generated.containsKey(faceId)) {
				final String diagnostic = "face=" + faceId + " blockId=<missing> nextBoundary=<unknown> railCount=0 reason=PROTECTION_BOUNDARY_UNCERTAIN";
				MtrbrDebugLog.event("BLOCK", diagnostic);
				System.out.println("[MTRBR-BLOCK] " + diagnostic);
			}
		}
		final SignalBlockSavedData saved = SignalBlockSavedData.get(level);
		final int legacyBefore = saved.legacyFaceCount();
		final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
		final SignalBlockSavedData.RegenerationResult result = saved.replaceGeneratedMappings(dimension, generated, generatedOccurrences);
		final long revision = SectionStateManager.getTopologyRevision(simulator);
		final String summary = "faceBlocks=" + result.faceBlocks() + " blockRails=" + result.blockRails()
				+ " occurrenceBlocks=" + result.occurrenceBlocks() + " revision=" + revision;
		MtrbrDebugLog.event("MTRBR-PROTECTION-REGENERATE", summary + " legacyDiscarded=" + legacyBefore + " actor=" + source.getTextName());
		System.out.println("[MTRBR-PROTECTION-REGENERATE] " + summary);
		source.sendSuccess(() -> Component.literal("Regenerated protection mappings: " + summary), false);
		return result.faceBlocks() > 0 ? 1 : 0;
	}

	private static int clearProtection(ServerLevel level, BlockPos signalPos, boolean reverse, net.minecraft.commands.CommandSourceStack source) {
		final String faceId = org.mtrbr.server.SignalTopology.id(signalPos, reverse);
		final SignalBlockSavedData saved = SignalBlockSavedData.get(level);
		saved.setBlock(faceId, saved.getBlockId(faceId), List.of());
		MtrbrDebugLog.event("TOPOLOGY", "protection-clear face=" + faceId + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Protection binding cleared for " + faceId + "."), false);
		return 1;
	}

	private static int showProtection(ServerLevel level, BlockPos signalPos, boolean reverse, net.minecraft.commands.CommandSourceStack source) {
		final String faceId = org.mtrbr.server.SignalTopology.id(signalPos, reverse);
		final List<String> railIds = SignalBlockSavedData.get(level).getRailIds(faceId);
		source.sendSuccess(() -> Component.literal(faceId + " -> " + (railIds.isEmpty() ? "<unbound>" : String.join(", ", railIds))), false);
		return 1;
	}

	private static int setManualOverride(ServerLevel level, String vehicleCode, boolean enabled, net.minecraft.commands.CommandSourceStack source) {
		final Long vehicleId = resolveVehicleId(source, vehicleCode);
		if (vehicleId == null) {
			return 0;
		}
		final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
		final Simulator simulator = SectionStateManager.getSimulator(dimension);
		if (simulator == null) {
			source.sendFailure(Component.literal("MTR simulator is not ready for this dimension."));
			return 0;
		}
		RouteRequestManager.setManualDrivingOverride(simulator, vehicleId, enabled);
		MtrbrDebugLog.event("DISPATCH", "command=manual_override vehicle=" + vehicleId + " enabled=" + enabled + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Manual override " + (enabled ? "queued" : "cleared") + " for vehicle " + vehicleCode + "."), false);
		return 1;
	}

	private static int setPriority(ServerLevel level, String vehicleCode, int priority, net.minecraft.commands.CommandSourceStack source) {
		final Long vehicleId = resolveVehicleId(source, vehicleCode);
		if (vehicleId == null) {
			return 0;
		}
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		RouteRequestManager.setManualPriority(simulator, vehicleId, priority);
		MtrbrDebugLog.event("DISPATCH", "command=priority vehicle=" + vehicleId + " value=" + priority + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Dispatcher priority queued for vehicle " + vehicleCode + ": " + priority + "."), false);
		return 1;
	}

	private static int revokePending(ServerLevel level, String vehicleCode, net.minecraft.commands.CommandSourceStack source) {
		final Long vehicleId = resolveVehicleId(source, vehicleCode);
		if (vehicleId == null) {
			return 0;
		}
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		RouteRequestManager.revokePendingAuthorization(simulator, vehicleId);
		MtrbrDebugLog.event("DISPATCH", "command=revoke_pending vehicle=" + vehicleId + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Pending authorization revoke queued for vehicle " + vehicleCode + "."), false);
		return 1;
	}

	private static int listRequests(ServerLevel level, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		final StringBuilder message = new StringBuilder("Requests:");
		for (final RouteRequestManager.RequestSnapshot request : RouteRequestManager.getRequestSnapshots(simulator)) {
			message.append("\n vehicle=").append(request.vehicleCode())
					.append(" state=").append(request.state())
					.append(" control=").append(String.format("%.1f", request.controlDistance()))
					.append(" reqEnd=").append(String.format("%.1f", request.endDistance()))
					.append(" authEnd=").append(String.format("%.1f", request.authorizationEndDistance()))
					.append(" authorized=").append(request.authorized());
		}
		source.sendSuccess(() -> Component.literal(message.toString()), false);
		return 1;
	}

	private static int approve(ServerLevel level, String vehicleCode, net.minecraft.commands.CommandSourceStack source) {
		final Long vehicleId = resolveVehicleId(source, vehicleCode);
		if (vehicleId == null) {
			return 0;
		}
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		RouteRequestManager.setManualPriority(simulator, vehicleId, 100000);
		MtrbrDebugLog.event("DISPATCH", "command=approve vehicle=" + vehicleId + " actor=" + source.getTextName());
		source.sendSuccess(() -> Component.literal("Approval priority queued for vehicle " + vehicleCode + "."), false);
		return 1;
	}

	private static int audit(ServerLevel level, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		source.sendSuccess(() -> Component.literal(String.join("\n", RouteRequestManager.getAudit(simulator))), false);
		return 1;
	}

	private static Simulator getSimulator(ServerLevel level, net.minecraft.commands.CommandSourceStack source) {
		final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
		final Simulator simulator = SectionStateManager.getSimulator(dimension);
		if (simulator == null) {
			source.sendFailure(Component.literal("MTR simulator is not ready for this dimension."));
		}
		return simulator;
	}

	private static Long resolveVehicleId(net.minecraft.commands.CommandSourceStack source, String vehicleCode) {
		final Long vehicleId = RouteRequestManager.resolveVehicleCode(vehicleCode.toUpperCase(java.util.Locale.ROOT));
		if (vehicleId == null) {
			source.sendFailure(Component.literal("Unknown vehicle code: " + vehicleCode));
		}
		return vehicleId;
	}
}
