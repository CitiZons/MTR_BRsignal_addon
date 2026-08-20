package org.mtrbr.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderGuiEvent;
import org.mtr.mapping.mapper.EntityHelper;
import org.mtrbr.MTRBR;
import org.mtrbr.client.ClientDispatcherData;
import org.mtrbr.data.ClientBindings;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;

import java.util.List;

/**
 * 乘车 HUD：手持进路工具并处于 MTR 列车上时，
 * 在屏幕下侧显示前方路径上下一信号机的进路绑定内容。
 */
public final class VehicleHudRenderer {

	private static final int LINE_HEIGHT = 12;
	private static final int BOX_WIDTH = 240;
	private static final int BOX_COLOR = 0xAA000000;

	private VehicleHudRenderer() {
	}

	public static void onRenderGui(RenderGuiEvent.Post event) {
		final Minecraft minecraft = Minecraft.getInstance();
		final Player player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		final boolean routeHeld = player.getMainHandItem().is(MTRBR.ROUTE_TOOL.get()) || player.getOffhandItem().is(MTRBR.ROUTE_TOOL.get());
		final boolean dispatcherHeld = player.getMainHandItem().is(MTRBR.DISPATCHER_TOOL.get()) || player.getOffhandItem().is(MTRBR.DISPATCHER_TOOL.get());
		if (!EntityHelper.HIDDEN_PLAYERS.contains(player.getUUID())) {
			return;
		}
		if (dispatcherHeld) {
			renderDispatcherHud(event, player);
			return;
		}
		if (!routeHeld) {
			return;
		}
		if (ClientBindings.isEmpty()) {
			return;
		}

		final List<String> lines = VehicleRouteReader.getNextSignalContents();
		if (lines.isEmpty()) {
			return;
		}

		final GuiGraphics guiGraphics = event.getGuiGraphics();
		final int width = event.getWindow().getGuiScaledWidth();
		final int height = event.getWindow().getGuiScaledHeight();
		final int startY = height / 2 - LINE_HEIGHT * lines.size() / 2 - 30;

		guiGraphics.fill(width / 2 - BOX_WIDTH / 2, startY - 2, width / 2 + BOX_WIDTH / 2, startY + LINE_HEIGHT * lines.size() + 2, BOX_COLOR);
		for (int i = 0; i < lines.size(); i++) {
			guiGraphics.drawCenteredString(minecraft.font, lines.get(i), width / 2, startY + i * LINE_HEIGHT, 0xFFFFFFFF);
		}
	}

	private static void renderDispatcherHud(RenderGuiEvent.Post event, Player player) {
		final VehicleExtension vehicle = findRidingVehicle(player);
		if (vehicle == null) {
			return;
		}
		final long vehicleId = vehicle.getId();
		ClientDispatcherData.Entry entry = null;
		for (final ClientDispatcherData.Entry candidate : ClientDispatcherData.getEntries()) {
			if (candidate.vehicleId() == vehicleId) {
				entry = candidate;
				break;
			}
		}
		final String route = entry != null && !entry.routeName().isEmpty() ? entry.routeName() : vehicle.vehicleExtraData.getThisRouteName();
		final String destination = entry != null && !entry.destination().isEmpty() ? entry.destination() : vehicle.vehicleExtraData.getThisRouteDestination();
		final List<String> lines = List.of("Vehicle ID: " + vehicleId, "Route: " + route, "Destination: " + destination);
		final GuiGraphics guiGraphics = event.getGuiGraphics();
		final int width = event.getWindow().getGuiScaledWidth();
		guiGraphics.fill(width / 2 - BOX_WIDTH / 2, 6, width / 2 + BOX_WIDTH / 2, 6 + LINE_HEIGHT * lines.size() + 2, BOX_COLOR);
		for (int i = 0; i < lines.size(); i++) {
			guiGraphics.drawCenteredString(Minecraft.getInstance().font, lines.get(i), width / 2, 9 + i * LINE_HEIGHT, 0xFFFFFFFF);
		}
	}

	private static VehicleExtension findRidingVehicle(Player player) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final boolean[] found = {false};
			vehicle.vehicleExtraData.iterateRidingEntities(ridingEntity -> {
				if (ridingEntity.uuid.equals(player.getUUID())) {
					found[0] = true;
				}
			});
			if (found[0]) {
				return vehicle;
			}
		}
		return null;
	}
}
