package org.mtrbr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 屏幕中央的提示文字（半透明灰底），用于替代会与物品栏重叠的动作栏提示。 */
public final class CenterToast {

	private static final int DURATION = 60;
	private static final int LINE_HEIGHT = 12;
	private static final List<Toast> TOASTS = new ArrayList<>();

	private CenterToast() {
	}

	public static void add(String text) {
		final long now = getGameTime();
		TOASTS.add(new Toast(text, now + DURATION));
		if (TOASTS.size() > 5) {
			TOASTS.remove(0);
		}
	}

	public static void onRenderGui(RenderGuiEvent.Post event) {
		final long now = getGameTime();
		TOASTS.removeIf(toast -> toast.expireTick() <= now);
		if (TOASTS.isEmpty()) {
			return;
		}
		final GuiGraphics guiGraphics = event.getGuiGraphics();
		final int width = event.getWindow().getGuiScaledWidth();
		final int height = event.getWindow().getGuiScaledHeight();
		final int totalHeight = TOASTS.size() * LINE_HEIGHT;
		final int startY = height / 2 - totalHeight / 2 - 40;
		int maxTextWidth = 0;
		for (final Toast toast : TOASTS) {
			maxTextWidth = Math.max(maxTextWidth, Minecraft.getInstance().font.width(toast.text()));
		}
		final int boxWidth = maxTextWidth + 20;
		guiGraphics.fill(width / 2 - boxWidth / 2, startY - 2, width / 2 + boxWidth / 2, startY + totalHeight + 2, 0xB0404040);
		for (int i = 0; i < TOASTS.size(); i++) {
			guiGraphics.drawCenteredString(Minecraft.getInstance().font, TOASTS.get(i).text(), width / 2, startY + i * LINE_HEIGHT, 0xFFFFFFFF);
		}
	}

	private static long getGameTime() {
		final Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? 0 : minecraft.level.getGameTime();
	}

	private record Toast(String text, long expireTick) {
	}
}
