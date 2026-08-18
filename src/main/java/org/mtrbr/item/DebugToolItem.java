package org.mtrbr.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.client.CenterToast;
import org.mtrbr.client.ClientHooks;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.logic.SignalLogic;
import org.mtrbr.network.BindIndicatorPacket;
import org.mtrbr.network.Network;
import org.mtrbr.network.SetNodeBindingPacket;

/**
 * 信号机调试工具：
 * - 右击 MTR 信号机 -> 打开调试 GUI；
 * - shift+右击信号机 -> 选中（节点绑定模式），再 shift+右击轨道节点完成手动节点绑定；
 * - shift+右击进路指示器 -> 选中指示器，再 shift+右击信号机完成指示器绑定（顺序不可颠倒）；
 * - 右击 LED 进路显示器 -> 打开其信息界面；
 * - 左键已绑定节点 -> 轮换绑定方向（见 LeftClickHandler）。
 */
public final class DebugToolItem extends Item {

	private static BlockPos selectedSignal;
	private static BlockPos selectedIndicator;

	public DebugToolItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
		tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.hold_shift")
				.withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.ITALIC));
		if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.signal_debug_tool.1"));
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.signal_debug_tool.2"));
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.signal_debug_tool.3"));
			tooltipComponents.add(Component.translatable("tooltip.mtr_brsignal_addon.signal_debug_tool.4"));
		}
	}
	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getLevel().isClientSide() && context.getPlayer() != null) {
			final BlockPos clickedPos = context.getClickedPos();
			final boolean isShift = context.isSecondaryUseActive();

			if (!isShift && SignalLogic.isIndicatorBlock(context.getLevel().getBlockState(clickedPos))) {
				final BlockPos pos = clickedPos.immutable();
				final boolean isLed = SignalLogic.isLedIndicatorBlock(context.getLevel().getBlockState(clickedPos));
				DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openIndicatorScreen(pos, isLed));
				return InteractionResult.SUCCESS;
			}

			// 第一步：shift+右击进路指示器，选中
			if (isShift && SignalLogic.isIndicatorBlock(context.getLevel().getBlockState(clickedPos))) {
				selectedIndicator = clickedPos.immutable();
				selectedSignal = null;
				CenterToast.add("已选中进路指示器，请 shift+右击信号机完成绑定");
				return InteractionResult.SUCCESS;
			}

			// 第二步：shift+右击信号机，完成指示器绑定（或进入节点绑定模式）
			if (isShift && SignalLogic.isSignalBlock(context.getLevel().getBlockState(clickedPos))) {
				if (selectedIndicator != null) {
					final BlockPos indicatorPos = selectedIndicator;
					final BlockPos signalPos = clickedPos.immutable();
					Network.CHANNEL.sendToServer(new BindIndicatorPacket(indicatorPos, signalPos));
					// 客户端乐观更新，UI 立即显示已绑定
					final BlockEntity blockEntity = context.getLevel().getBlockEntity(indicatorPos);
					if (blockEntity instanceof LedIndicatorBlockEntity led) {
						led.setBoundSignalPos(signalPos);
					} else if (blockEntity instanceof ColorLightIndicatorBlockEntity colorLight) {
						colorLight.setBoundSignalPos(signalPos);
					}
					ClientIndicatorBindings.set(indicatorPos, signalPos);
					CenterToast.add("进路指示器已绑定到信号机 " + signalPos);
					selectedIndicator = null;
				} else {
					selectedSignal = clickedPos.immutable();
					CenterToast.add("已选中信号机（节点绑定），请 shift+右击轨道节点");
				}
				return InteractionResult.SUCCESS;
			}

			// 节点绑定：shift+右击轨道节点
			if (isShift && SignalLogic.isNodeBlock(context.getLevel().getBlockState(clickedPos))) {
				if (selectedSignal != null) {
					final BlockPos signalPos = selectedSignal;
					final BlockPos nodePos = clickedPos.immutable();
					Network.CHANNEL.sendToServer(new SetNodeBindingPacket(signalPos, nodePos));
					selectedSignal = null;
					CenterToast.add("已将信号机 " + signalPos + " 绑定到节点 " + nodePos);
				} else {
					CenterToast.add("请先 shift+右击一个 MTR 信号机");
				}
				return InteractionResult.SUCCESS;
			}

			if (!isShift && SignalLogic.isSignalBlock(context.getLevel().getBlockState(clickedPos))) {
				final BlockPos pos = clickedPos.immutable();
				DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openSignalDebugScreen(pos));
			}
		}
		return InteractionResult.SUCCESS;
	}
}
