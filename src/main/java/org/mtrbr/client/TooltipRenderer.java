package org.mtrbr.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderTooltipEvent;
import org.mtrbr.MTRBR;

import java.util.ArrayList;
import java.util.List;

/** 工具提示自定义渲染：按住 Shift 时以较小一号字体显示用法说明，渲染在最顶层，且不丢任何原版行（注册名/mod 名）。 */
public final class TooltipRenderer {

	private static final float DETAIL_SCALE = 0.78F;

	private TooltipRenderer() {
	}

	public static void onTooltipPre(RenderTooltipEvent.Pre event) {
		final ItemStack stack = event.getItemStack();
		final boolean debug = stack.is(MTRBR.DEBUG_TOOL.get());
		final boolean route = stack.is(MTRBR.ROUTE_TOOL.get());
		if (!debug && !route) {
			return;
		}
		if (!Screen.hasShiftDown()) {
			return; // 未按住 Shift：使用原版默认工具提示
		}
		event.setCanceled(true);

		final Minecraft minecraft = Minecraft.getInstance();
		final Font font = event.getFont();
		final int lineH = font.lineHeight;
		final TooltipFlag flag = minecraft.options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
		final List<Component> lines = stack.getTooltipLines(minecraft.player, flag);

		final Component name = lines.isEmpty() ? stack.getHoverName() : lines.get(0);
		final String prefix = debug ? "signal_debug_tool" : "route_tool";
		final String holdKey = "tooltip.mtr_brsignal_addon.hold_shift";
		final List<Component> usage = new ArrayList<>();
		final List<Component> meta = new ArrayList<>();
		Component holdShift = null;
		for (int i = 1; i < lines.size(); i++) {
			final Component c = lines.get(i);
			final String key = translationKey(c);
			if (holdKey.equals(key)) {
				holdShift = c;
			} else if (key != null && key.startsWith("tooltip.mtr_brsignal_addon." + prefix + ".")) {
				usage.add(c);
			} else {
				meta.add(c); // 注册名/mod 名等原版行，不丢弃
			}
		}
		if (holdShift == null) {
			holdShift = Component.translatable(holdKey).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC);
		}

		int width = font.width(name);
		width = Math.max(width, font.width(holdShift));
		for (final Component c : usage) {
			width = Math.max(width, (int) Math.ceil(font.width(c) * DETAIL_SCALE));
		}
		for (final Component c : meta) {
			width = Math.max(width, font.width(c));
		}
		width += 8;

		final int detailLine = (int) Math.ceil(lineH * DETAIL_SCALE);
		final int height = lineH * 2 + detailLine * usage.size() + lineH * meta.size() + 8;

		int x = event.getX();
		int y = event.getY();
		x = Math.max(4, Math.min(x, event.getScreenWidth() - width - 6));
		y = Math.max(4, Math.min(y, event.getScreenHeight() - height - 6));

		// 顶层渲染（与原版工具提示同一高度层，避免被物品栏/物品遮挡）
		final GuiGraphics graphics = event.getGraphics();
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, 400);

		graphics.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xF0100010);
		graphics.fill(x - 3, y - 3, x + width + 3, y, 0x505000FF);
		graphics.fill(x - 3, y + height, x + width + 3, y + height + 3, 0x5028007F);
		graphics.fill(x - 3, y - 3, x, y + height + 3, 0x505000FF);
		graphics.fill(x + width, y - 3, x + width + 3, y + height + 3, 0x5028007F);

		int yy = y;
		graphics.drawString(font, name, x, yy, 0xFFFFFFFF, true);
		yy += lineH;
		graphics.drawString(font, holdShift, x, yy, 0xFFFFFFFF, true);
		yy += lineH;

		graphics.pose().pushPose();
		graphics.pose().translate(x, yy, 0);
		graphics.pose().scale(DETAIL_SCALE, DETAIL_SCALE, 1.0F);
		int sy = 0;
		for (final Component c : usage) {
			graphics.drawString(font, c, 0, sy, 0xFFFFFFFF, true);
			sy += lineH;
		}
		graphics.pose().popPose();
		yy += detailLine * usage.size();

		for (final Component c : meta) {
			graphics.drawString(font, c, x, yy, 0xFFFFFFFF, true);
			yy += lineH;
		}
		graphics.pose().popPose();
	}

	private static String translationKey(Component component) {
		if (component instanceof MutableComponent mutable && mutable.getContents() instanceof TranslatableContents contents) {
			return contents.getKey();
		}
		return null;
	}
}
