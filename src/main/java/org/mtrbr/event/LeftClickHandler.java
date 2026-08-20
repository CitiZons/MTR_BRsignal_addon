package org.mtrbr.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.mtrbr.MTRBR;
import org.mtrbr.client.CenterToast;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.logic.SignalLogic;
import org.mtrbr.network.Network;
import org.mtrbr.network.ToggleNodeBindingDirectionPacket;

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
		final Level level = event.getLevel();
		if (debugHeld && level.isClientSide() && SignalLogic.isNodeBlock(level.getBlockState(event.getPos()))) {
			final BlockPos signalPos = ClientBindings.getSignalForNode(event.getPos());
			if (signalPos != null) {
				Network.CHANNEL.sendToServer(new ToggleNodeBindingDirectionPacket(signalPos));
				CenterToast.add("已切换信号机 " + signalPos + " 的节点绑定方向");
			}
		}
		event.setCanceled(true);
	}
}
