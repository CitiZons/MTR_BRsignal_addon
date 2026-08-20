package org.mtrbr.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.SectionStateManager;

/** Temporary operator surface; a later dispatcher panel calls the same server API. */
public final class MTRBRCommands {
	private MTRBRCommands() {
	}

	public static void register(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("mtrbr")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("manual_override")
						.then(Commands.argument("vehicle_id", LongArgumentType.longArg(0))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> setManualOverride(context.getSource().getLevel(), LongArgumentType.getLong(context, "vehicle_id"), BoolArgumentType.getBool(context, "enabled"), context.getSource())))))
				.then(Commands.literal("priority")
						.then(Commands.argument("vehicle_id", LongArgumentType.longArg(0))
								.then(Commands.argument("value", IntegerArgumentType.integer(0, 1000))
										.executes(context -> setPriority(context.getSource().getLevel(), LongArgumentType.getLong(context, "vehicle_id"), IntegerArgumentType.getInteger(context, "value"), context.getSource())))))
				.then(Commands.literal("revoke_pending")
						.then(Commands.argument("vehicle_id", LongArgumentType.longArg(0))
								.executes(context -> revokePending(context.getSource().getLevel(), LongArgumentType.getLong(context, "vehicle_id"), context.getSource())))));
	}

	private static int setManualOverride(ServerLevel level, long vehicleId, boolean enabled, net.minecraft.commands.CommandSourceStack source) {
		final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
		final Simulator simulator = SectionStateManager.getSimulator(dimension);
		if (simulator == null) {
			source.sendFailure(Component.literal("MTR simulator is not ready for this dimension."));
			return 0;
		}
		RouteRequestManager.setManualDrivingOverride(simulator, vehicleId, enabled);
		source.sendSuccess(() -> Component.literal("Manual override " + (enabled ? "queued" : "cleared") + " for vehicle " + vehicleId + "."), false);
		return 1;
	}

	private static int setPriority(ServerLevel level, long vehicleId, int priority, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		RouteRequestManager.setManualPriority(simulator, vehicleId, priority);
		source.sendSuccess(() -> Component.literal("Dispatcher priority queued for vehicle " + vehicleId + ": " + priority + "."), false);
		return 1;
	}

	private static int revokePending(ServerLevel level, long vehicleId, net.minecraft.commands.CommandSourceStack source) {
		final Simulator simulator = getSimulator(level, source);
		if (simulator == null) {
			return 0;
		}
		RouteRequestManager.revokePendingAuthorization(simulator, vehicleId);
		source.sendSuccess(() -> Component.literal("Pending authorization revoke queued for vehicle " + vehicleId + "."), false);
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
}
