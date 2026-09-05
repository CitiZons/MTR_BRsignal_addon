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
import org.mtrbr.block.RepeatingSignalBlockEntity;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.data.ClientSignalNames;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.network.Network;
import org.mtrbr.network.SetIndicatorMountPacket;
import org.mtrbr.block.IndicatorMount;
import org.mtrbr.client.ServerAspectCache;
import org.mtrbr.logic.RepeatingSignalDisplay;
import org.mtrbr.network.UnbindIndicatorPacket;

import java.util.List;
import java.util.Locale;

/** 进路指示器 / 复示信号信息、解绑及安装方式设置。 */
public final class IndicatorInfoScreen extends Screen {

	private static final int LINE_HEIGHT = 12;

	private final BlockPos indicatorPos;
	private final boolean isLed;
	private final boolean isRepeater;
	private Button mountButton;
	private boolean needsRebuild = false;
	private boolean unbindRequested = false;

	public IndicatorInfoScreen(BlockPos indicatorPos, boolean isLed) {
		super(isRepeater(indicatorPos) ? Component.translatable("block.mtr_brsignal_addon.banner_repeating_signal") : Component.literal(isLed ? "LED进路显示器" : "色灯式进路指示器"));
		this.indicatorPos = indicatorPos;
		this.isLed = isLed;
		this.isRepeater = isRepeater(indicatorPos);
	}

	private static boolean isRepeater(BlockPos pos) {
        return Minecraft.getInstance().level != null && Minecraft.getInstance().level.getBlockEntity(pos) instanceof RepeatingSignalBlockEntity;
    }

    private boolean isHanging() {
        final Level level = Minecraft.getInstance().level;
        return level != null && IndicatorMount.isHanging(level.getBlockState(indicatorPos));
    }

    private Component mountLabel() {
        return Component.translatable(isHanging() ? "screen.mtr_brsignal_addon.mount.hanging" : "screen.mtr_brsignal_addon.mount.standing");
    }

    @Override
    protected void init() {
        mountButton = addRenderableWidget(Button.builder(mountLabel(), button ->
            Network.CHANNEL.sendToServer(new SetIndicatorMountPacket(indicatorPos, !isHanging())))
            .bounds(width / 2 - 140, height - 58, 280, 20).build());
        mountButton.active = minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
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
		if (mountButton != null) mountButton.setMessage(mountLabel());
		guiGraphics.fillGradient(0, 0, width, height, 0xB0404040, 0xB0404040);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		final Minecraft minecraft = Minecraft.getInstance();
		final Level level = minecraft.level;
		if (level == null) {
			return;
		}

        guiGraphics.drawCenteredString(font, Component.translatable("screen.mtr_brsignal_addon.mount.hint"), width / 2, height - 72, 0xFFAAAAAA);
        int y = 30;
		guiGraphics.drawString(font, getTitle().getString() + " " + indicatorPos, width / 2 - 200, y, 0xFFFFFFFF);
		y += LINE_HEIGHT;

		final BlockPos boundSignalPos = getBoundSignal();
		final String boundSignalName = boundSignalPos == null ? null : ClientSignalNames.get(boundSignalPos);
		guiGraphics.drawString(font, "绑定信号机: " + (boundSignalPos == null ? "未绑定" : (boundSignalName != null ? boundSignalName : boundSignalPos)), width / 2 - 200, y, 0xFFFFFFFF);
		y += LINE_HEIGHT * 2;

        if (isRepeater) {
            final var display = RepeatingSignalDisplay.forBinding(boundSignalPos != null, boundSignalPos == null ? null : ServerAspectCache.get(boundSignalPos, false));
            guiGraphics.drawString(font, Component.translatable("screen.mtr_brsignal_addon.repeater.current", display.textureName()), width / 2 - 200, y, 0xFFFFFFFF);
            y += LINE_HEIGHT * 2;
            for (String key : List.of("red", "green", "other", "missing", "bind")) {
                guiGraphics.drawString(font, Component.translatable("screen.mtr_brsignal_addon.repeater." + key), width / 2 - 200, y, 0xFFCCCCCC);
                y += LINE_HEIGHT;
            }
            return;
        }
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
			if (blockEntity instanceof RepeatingSignalBlockEntity repeating) {
                bound = repeating.getBoundSignalPos();
            } else if (blockEntity instanceof LedIndicatorBlockEntity led) {
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
