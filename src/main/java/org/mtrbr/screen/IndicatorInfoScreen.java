package org.mtrbr.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.data.ClientSignalNames;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.network.Network;
import org.mtrbr.network.UnbindIndicatorPacket;

import java.util.List;
import java.util.Locale;

/** 进路指示器信息界面（只读 + 解绑）：显示绑定信号机及其可用的显示。LED 只显示 path 类，色灯式只显示 route 类。 */
public final class IndicatorInfoScreen extends Screen {

	private static final int LINE_HEIGHT = 12;

	private final BlockPos indicatorPos;
	private final boolean isLed;
	private boolean needsRebuild = false;
	private boolean unbindRequested = false;

	public IndicatorInfoScreen(BlockPos indicatorPos, boolean isLed) {
		super(Component.literal(isLed ? "LED进路显示器" : "色灯式进路指示器"));
		this.indicatorPos = indicatorPos;
		this.isLed = isLed;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose()).bounds(width / 2 - 50, height - 30, 100, 20).build());
		if (getBoundSignal() != null) {
			addRenderableWidget(Button.builder(Component.literal("解绑"), button -> unbind()).bounds(width / 2 + 60, height - 30, 80, 20).build());
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (needsRebuild) {
			needsRebuild = false;
			clearWidgets();
			init();
		}
		guiGraphics.fillGradient(0, 0, width, height, 0xB0404040, 0xB0404040);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		final Minecraft minecraft = Minecraft.getInstance();
		final Level level = minecraft.level;
		if (level == null) {
			return;
		}

		int y = 30;
		guiGraphics.drawString(font, (isLed ? "LED进路显示器 " : "色灯式进路指示器 ") + indicatorPos, width / 2 - 200, y, 0xFFFFFFFF);
		y += LINE_HEIGHT;

		final BlockPos boundSignalPos = getBoundSignal();
		final String boundSignalName = boundSignalPos == null ? null : ClientSignalNames.get(boundSignalPos);
		guiGraphics.drawString(font, "绑定信号机: " + (boundSignalPos == null ? "未绑定" : (boundSignalName != null ? boundSignalName : boundSignalPos)), width / 2 - 200, y, 0xFFFFFFFF);
		y += LINE_HEIGHT * 2;

		guiGraphics.drawString(font, "可用显示（" + (isLed ? "仅 path 类型" : "仅 route 类型") + "）:", width / 2 - 200, y, 0xFFFFFFFF);
		y += LINE_HEIGHT;
		if (boundSignalPos == null) {
			guiGraphics.drawString(font, "  (未绑定信号机)", width / 2 - 200, y, 0xFFAAAAAA);
			return;
		}
		final List<RouteBinding> bindings = ClientBindings.get(boundSignalPos);
		boolean any = false;
		for (final RouteBinding binding : bindings) {
			final boolean pathType = binding.content().toLowerCase(Locale.ROOT).startsWith("path=");
			final boolean routeType = binding.content().toLowerCase(Locale.ROOT).startsWith("route=");
			if (isLed ? !pathType : !routeType) {
				continue;
			}
			any = true;
			final String display = binding.content().equalsIgnoreCase("path=NULL") ? binding.content() + "（无显示）" : binding.content();
			guiGraphics.drawString(font, "  " + display, width / 2 - 200, y, 0xFFFFFFFF);
			y += LINE_HEIGHT;
		}
		if (!any) {
			guiGraphics.drawString(font, "  (无" + (isLed ? " path" : " route") + "类进路绑定)", width / 2 - 200, y, 0xFFAAAAAA);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private BlockPos getBoundSignal() {
		if (unbindRequested) {
			return null;
		}
		final Level level = Minecraft.getInstance().level;
		BlockPos bound = ClientIndicatorBindings.get(indicatorPos);
		if (bound == null && level != null) {
			final BlockEntity blockEntity = level.getBlockEntity(indicatorPos);
			if (blockEntity instanceof LedIndicatorBlockEntity led) {
				bound = led.getBoundSignalPos();
			} else if (blockEntity instanceof ColorLightIndicatorBlockEntity colorLight) {
				bound = colorLight.getBoundSignalPos();
			}
		}
		return bound;
	}

	private void unbind() {
		Network.CHANNEL.sendToServer(new UnbindIndicatorPacket(indicatorPos));
		ClientIndicatorBindings.remove(indicatorPos);
		unbindRequested = true;
		needsRebuild = true;
	}
}
