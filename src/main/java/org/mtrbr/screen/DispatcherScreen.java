package org.mtrbr.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrbr.client.ClientDispatcherData;
import org.mtrbr.client.CenterToast;
import org.mtrbr.network.DispatcherActionPacket;
import org.mtrbr.network.Network;
import org.mtrbr.network.RequestDispatcherDataPacket;

import java.util.List;

/** 调度面板：从服务端快照加载可滚动 Request 列表，选中后批准或撤销。 */
public final class DispatcherScreen extends Screen {
	private DispatcherList dispatcherList;
	private int lastEntryCount = -1;

	public DispatcherScreen() {
		super(Component.literal("调度面板"));
	}

	@Override
	protected void init() {
		dispatcherList = new DispatcherList(minecraft, Math.max(220, width - 30), height - 62, 30, height - 34, 22);
		addRenderableWidget(dispatcherList);
		addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> refresh()).bounds(width / 2 - 120, height - 27, 48, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Approve"), button -> approveSelected()).bounds(width / 2 - 66, height - 27, 48, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Revoke"), button -> revokeSelected()).bounds(width / 2 - 12, height - 27, 48, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose()).bounds(width / 2 + 42, height - 27, 48, 18).build());
		refresh();
	}

	private void refresh() {
		Network.CHANNEL.sendToServer(new RequestDispatcherDataPacket());
	}

	private void refreshEntries() {
		final List<ClientDispatcherData.Entry> entries = ClientDispatcherData.getEntries();
		if (entries.size() != lastEntryCount) {
			lastEntryCount = entries.size();
			dispatcherList.setEntries(entries);
		}
	}

	private void approveSelected() {
		final long vehicleId = dispatcherList.selectedVehicleId();
		if (vehicleId >= 0) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("approve", vehicleId));
			CenterToast.add("Approve sent for " + vehicleId);
		}
	}

	private void revokeSelected() {
		final long vehicleId = dispatcherList.selectedVehicleId();
		if (vehicleId >= 0) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("revoke", vehicleId));
			CenterToast.add("Revoke sent for " + vehicleId);
		}
	}

	@Override
	public void tick() {
		refreshEntries();
		super.tick();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		guiGraphics.fillGradient(0, 0, width, height, 0x60404040, 0x60404040);
		guiGraphics.drawCenteredString(font, "Dispatcher Console / 调度台", width / 2, 8, 0xFFFFFFFF);
		if (dispatcherList != null) {
			drawHeader(guiGraphics);
		}
		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	private void drawHeader(GuiGraphics guiGraphics) {
		final int left = dispatcherList.getLeft();
		final int rowWidth = dispatcherList.getRowWidth();
		final String[] headers = {"ID", "State / 状态", "Route / 线路", "Destination / 目的地", "Ctrl / 控制点", "Req / 请求终点", "Auth / 授权终点"};
		for (int i = 0; i < headers.length; i++) {
			guiGraphics.drawString(font, headers[i], left + 3 + columnX(i, rowWidth), 26, 0xFFFFFF00);
		}
	}

	private int columnX(int index, int rowWidth) {
		final float[] widths = {0.16F, 0.10F, 0.10F, 0.10F, 0.12F, 0.12F, 0.12F};
		int x = 12;
		for (int i = 0; i < index; i++) {
			x += (int) (rowWidth * widths[i]);
		}
		return x;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private final class DispatcherList extends ObjectSelectionList<DispatcherList.Entry> {
		private DispatcherList(Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
			super(minecraft, width, height, y0, y1, itemHeight);
		}

		private long selectedVehicleId() {
			final Entry entry = getSelected();
			return entry == null ? -1 : entry.data.vehicleId();
		}

		private void setEntries(List<ClientDispatcherData.Entry> entries) {
			clearEntries();
			for (final ClientDispatcherData.Entry entry : entries) {
				addEntry(new Entry(entry));
			}
		}

		@Override
		protected int getScrollbarPosition() {
			return getLeft() + width - 6;
		}

		@Override
		public int getRowWidth() {
			return width - 12;
		}

		private final class Entry extends ObjectSelectionList.Entry<Entry> {
			private final ClientDispatcherData.Entry data;

			private Entry(ClientDispatcherData.Entry data) {
				this.data = data;
			}

			@Override
			public Component getNarration() {
				return Component.literal(format(data));
			}

			@Override
			public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
				final String[] columns = {
						String.valueOf(data.vehicleId()),
						data.state(),
						data.routeName(),
						data.destination(),
						String.format("%.1f", data.control()),
						String.format("%.1f", data.requestEnd()),
						String.format("%.1f", data.authorizationEnd())
				};
				for (int i = 0; i < columns.length; i++) {
					guiGraphics.drawString(minecraft.font, columns[i], left + 3 + columnX(i, width), top + 1, 0xFFFFFFFF);
				}
			}

		}

		private String format(ClientDispatcherData.Entry data) {
			return data.vehicleId() + " " + data.state() + " " + data.routeName() + " " + data.destination();
		}
	}
}
