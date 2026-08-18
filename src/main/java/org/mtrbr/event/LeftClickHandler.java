package org.mtrbr.event;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.mtrbr.MTRBR;

/** 手持调试工具/进路工具时无法破坏任何方块。 */
public final class LeftClickHandler {

	private LeftClickHandler() {
	}

	public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
		final Player player = event.getEntity();
		if (player == null) {
			return;
		}
		final ItemStack stack = player.getMainHandItem();
		final boolean debugHeld = stack.is(MTRBR.DEBUG_TOOL.get());
		final boolean routeHeld = stack.is(MTRBR.ROUTE_TOOL.get());
		if (!debugHeld && !routeHeld) {
			return;
		}
		event.setCanceled(true);
	}
}
