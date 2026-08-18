package org.mtrbr.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.mtrbr.client.CenterToast;
import org.mtrbr.client.ClientHooks;
import org.mtrbr.logic.SignalLogic;

import java.util.List;

/**
 * 进路工具：右击信号机（选中），再 shift+右击轨道节点完成进路绑定，
 * 随后弹出文本输入对话框（route=X / path=Y）。
 */
public final class RouteToolItem extends Item {

	private static BlockPos selectedSignal;

	public RouteToolItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
		tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.hold_shift")
				.withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.ITALIC));
		if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.route_tool.1"));
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.route_tool.2"));
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.route_tool.3"));
		}
	}
	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getLevel().isClientSide() && context.getPlayer() != null) {
			final BlockPos clickedPos = context.getClickedPos();
			final boolean isShift = context.isSecondaryUseActive();

			if (isShift && SignalLogic.isNodeBlock(context.getLevel().getBlockState(clickedPos))) {
				if (selectedSignal != null) {
					final BlockPos signalPos = selectedSignal;
					final BlockPos nodePos = clickedPos.immutable();
					DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openRouteTextInputScreen(signalPos, nodePos));
				} else {
					CenterToast.add("请先用进路工具右击一个 MTR 信号机");
				}
				return InteractionResult.SUCCESS;
			}

			if (SignalLogic.isSignalBlock(context.getLevel().getBlockState(clickedPos))) {
				selectedSignal = clickedPos.immutable();
				CenterToast.add("已选中信号机 " + selectedSignal + "，请 shift+右击轨道节点完成进路绑定");
			}
		}
		return InteractionResult.SUCCESS;
	}
}
