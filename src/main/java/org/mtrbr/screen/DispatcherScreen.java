package org.mtrbr.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrbr.client.ClientDispatcherData;
import org.mtrbr.client.CenterToast;
import org.mtrbr.network.DispatcherActionPacket;
import org.mtrbr.network.Network;
import org.mtrbr.network.RequestDispatcherDataPacket;

import java.util.ArrayList;
import java.util.List;

/** 调度面板：模仿 SignalDebugScreen 的自适应、透明背景和手动行绘制。 */
public final class DispatcherScreen extends Screen {

	private static final int CONTENT_WIDTH = 700;
	private static final int LIST_TOP = 50;
	private static final int ROW_HEIGHT = 18;
	private static final int[] COLUMN_OFFSETS = {0, 60, 125, 195, 265, 345, 405, 465, 525, 595};
	private static final String[] HEADERS = {"Code", "State", "Route", "Next", "Dest", "Ctrl", "Req", "Auth", "Head", "Occ/Res/Lock"};
	private static final String[] CHINESE_HEADERS = {"编号", "状态", "路线", "下一站", "终点", "控制", "请求", "授权", "车头", "占/预/锁"};

	private final List<ClientDispatcherData.Entry> entries = new ArrayList<>();
	private int selectedIndex = -1;
	private int scrollOffset;
	private int leftX;
	private float scale;
	private int refreshTicks;

	public DispatcherScreen() {
		super(Component.literal("Dispatcher Console 调度台"));
	}

	@Override
	protected void init() {
		leftX = Math.max(10, (width - CONTENT_WIDTH) / 2);
		scale = Math.min(1.0F, (float) (width - 20) / CONTENT_WIDTH);
		final int buttonWidth = 55;
		final int gap = 10;
		final double startX = leftX + (CONTENT_WIDTH - buttonWidth * 5 - gap * 4) / 2.0;
		addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> requestRefresh()).bounds((int) (startX * 0.45 + (buttonWidth + gap) * 0), height - 28, buttonWidth, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Approve"), button -> approveSelected()).bounds((int) (startX * 0.45 + (buttonWidth + gap) * 1), height - 28, buttonWidth, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Revoke"), button -> revokeSelected()).bounds((int) (startX * 0.45 + (buttonWidth + gap) * 2), height - 28, buttonWidth, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Override"), button -> overrideSelected()).bounds((int) (startX * 0.45 + (buttonWidth + gap) * 3), height - 28, buttonWidth, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose()).bounds((int) (startX * 0.45 + (buttonWidth + gap) * 4), height - 28, buttonWidth, 18).build());
		requestRefresh();
		refreshTicks = 20;
	}

	@Override
	public void tick() {
		if (--refreshTicks <= 0) {
			requestRefresh();
			refreshTicks = 20;
		} else {
			syncEntries();
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		guiGraphics.fillGradient(0, 0, width, height, 0xB0404040, 0xB0404040);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
		drawHeader(guiGraphics);
		drawEntries(guiGraphics, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (mouseY >= LIST_TOP && mouseY < height - 34) {
			final int visibleIndex = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
			final int index = scrollOffset + visibleIndex;
			if (index >= 0 && index < entries.size()) {
				selectedIndex = index;
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		final int visibleRows = (height - 34 - LIST_TOP) / ROW_HEIGHT;
		final int maxOffset = Math.max(0, entries.size() - visibleRows);
		scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(delta)));
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void requestRefresh() {
		Network.CHANNEL.sendToServer(new RequestDispatcherDataPacket());
		syncEntries();
	}

	private void syncEntries() {
		final ClientDispatcherData.Entry selectedEntry = selectedEntry();
		final long selectedVehicleId = selectedEntry == null ? Long.MIN_VALUE : selectedEntry.vehicleId();
		entries.clear();
		entries.addAll(ClientDispatcherData.getEntries());
		selectedIndex = -1;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).vehicleId() == selectedVehicleId) {
				selectedIndex = i;
				break;
			}
		}
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - (height - 34 - LIST_TOP) / ROW_HEIGHT)));
	}

	private void approveSelected() {
		final ClientDispatcherData.Entry entry = selectedEntry();
		if (entry != null) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("approve", entry.vehicleId()));
			CenterToast.add("Approve sent for " + entry.vehicleCode());
		}
	}

	private void revokeSelected() {
		final ClientDispatcherData.Entry entry = selectedEntry();
		if (entry != null) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("revoke", entry.vehicleId()));
			CenterToast.add("Revoke sent for " + entry.vehicleCode());
		}
	}

	private void overrideSelected() {
		final ClientDispatcherData.Entry entry = selectedEntry();
		if (entry != null) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("override", entry.vehicleId()));
			CenterToast.add("Override sent for " + entry.vehicleCode());
		}
	}

	private ClientDispatcherData.Entry selectedEntry() {
		return selectedIndex >= 0 && selectedIndex < entries.size() ? entries.get(selectedIndex) : null;
	}

	private void drawHeader(GuiGraphics guiGraphics) {
		for (int i = 0; i < HEADERS.length; i++) {
			guiGraphics.drawString(font, HEADERS[i], sx(COLUMN_OFFSETS[i]), LIST_TOP - 22, 0xFFFFFF00);
		}
		guiGraphics.pose().pushPose();
		guiGraphics.pose().scale(0.75F, 0.75F, 1.0F);
		for (int i = 0; i < CHINESE_HEADERS.length; i++) {
			guiGraphics.drawString(font, CHINESE_HEADERS[i], (int) (sx(COLUMN_OFFSETS[i]) / 0.75F), (int) ((LIST_TOP - 11) / 0.75F), 0xFFC0C0C0);
		}
		guiGraphics.pose().popPose();
	}

	private void drawEntries(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		final int visibleRows = (height - 34 - LIST_TOP) / ROW_HEIGHT;
		for (int i = 0; i < visibleRows; i++) {
			final int index = scrollOffset + i;
			if (index >= entries.size()) {
				break;
			}
			final ClientDispatcherData.Entry entry = entries.get(index);
			final int y = LIST_TOP + i * ROW_HEIGHT + 2;
			if (index == selectedIndex) {
				guiGraphics.fill(sx(0) - 3, y - 2, sx(CONTENT_WIDTH) + 3, y + ROW_HEIGHT - 3, 0x55FFFFFF);
			}
			drawRow(guiGraphics, entry, y);
		}
	}

	private void drawRow(GuiGraphics guiGraphics, ClientDispatcherData.Entry entry, int y) {
		final String[] values = {
				entry.vehicleCode(),
				entry.routeName(),
				entry.nextStation(),
				entry.destination(),
				String.format("%.1f", entry.control()),
				String.format("%.1f", entry.requestEnd()),
				String.format("%.1f", entry.authorizationEnd()),
				String.format("%.1f", entry.head()),
				entry.occupiedSections() + "/" + entry.reservedSections() + "/" + entry.lockedSections()
		};
		guiGraphics.drawString(font, values[0], sx(COLUMN_OFFSETS[0]), y, 0xFFFFFFFF);
		guiGraphics.drawString(font, entry.state(), sx(COLUMN_OFFSETS[1]), y, stateColor(entry.state()));
		for (int i = 1; i < values.length; i++) {
			guiGraphics.drawString(font, values[i], sx(COLUMN_OFFSETS[i + 1]), y, 0xFFFFFFFF);
		}
	}

	private static int stateColor(String state) {
		if (state == null) {
			return 0xFF55AAFF;
		}
		return switch (state.toUpperCase(java.util.Locale.ROOT)) {
			case "ACTIVE", "AUTHORIZED", "APPROACHING", "REQUESTED", "CHECKING", "PASSED" -> 0xFF55FF55;
			case "WAITING" -> 0xFFFFFF55;
			case "DENIED", "INVALID", "REVOKED", "CANCELED", "RELEASED" -> 0xFFFF5555;
			case "OVERRIDE", "NONE" -> 0xFF55AAFF;
			default -> 0xFFFFFFFF;
		};
	}

	private int sx(int baseOffset) {
		return leftX + (int) (baseOffset * scale);
	}
}
