package org.mtrbr.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrbr.client.ClientDispatcherData;
import org.mtrbr.client.ClientWebTokenActions;
import org.mtrbr.network.DispatcherActionPacket;
import org.mtrbr.network.Network;
import org.mtrbr.network.RequestDispatcherDataPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;

/** 调度面板：模仿 SignalDebugScreen 的自适应、透明背景和手动行绘制。 */
public final class DispatcherScreen extends Screen {

	private static final int CONTENT_WIDTH = 700;
	private static final int LIST_TOP = 80;
	private static final int ROW_HEIGHT = 18;
	private static final int FOOTER_HEIGHT = 58;
	private static final int[] COLUMN_OFFSETS = {5, 65, 130, 210, 285, 360, 420, 480, 540, 610};
	private static final String[] HEADERS = {"Code", "State", "Route", "Next", "Dest", "Ctrl", "Req", "Auth", "Head", "Occ/Auth/Lock"};
	private static final String[] CHINESE_HEADERS = {"编号", "状态", "路线", "下一站", "终点", "控制", "请求", "授权", "车头", "区间占用/授权/锁闭"};

	private final List<ClientDispatcherData.Entry> entries = new ArrayList<>();
	private int selectedIndex = -1;
	private int scrollOffset;
	private int leftX;
	private float scale;
	private int refreshTicks;
	private String query = "", stateFilter = "all";
	private int sortColumn;
	private boolean descending;
	private Component hoveredText;
	private EditBox searchBox;
	private List<ClientDispatcherData.Entry> lastSource;
	private String lastQuery, lastFilter;
	private int lastSort = -1;
	private boolean lastDescending;

	public DispatcherScreen() {
		super(Component.literal("Dispatcher Console 调度台"));
	}

	@Override
	protected void init() {
		leftX = Math.max(10, (width - CONTENT_WIDTH) / 2);
		scale = Math.min(1.0F, (float) (width - 20) / CONTENT_WIDTH);
		final int contentWidth = Math.min(CONTENT_WIDTH, width - 20), filterWidth = Math.min(125, contentWidth / 3);
		searchBox = new EditBox(font, leftX, 30, contentWidth - filterWidth - 6, 18, label("search"));
		searchBox.setHint(label("search"));
		searchBox.setMaxLength(100);
		searchBox.setValue(query);
		searchBox.setResponder(value -> { query = value; scrollOffset = 0; syncEntries(); });
		addRenderableWidget(searchBox);
		addRenderableWidget(CycleButton.<String>builder(value -> label("filter." + value))
				.withValues("all", "waiting", "authorized", "problem").withInitialValue(stateFilter).displayOnlyValue()
				.create(leftX + contentWidth - filterWidth, 30, filterWidth, 18, label("filter"),
				(button, value) -> { stateFilter = value; scrollOffset = 0; syncEntries(); }));
		addButtonRow(height - 52, 70,
				List.of(Component.literal("Refresh"), Component.literal("Approve"), Component.literal("Revoke"), Component.literal("Override"), Component.literal("Close")),
				List.of(this::requestRefresh, this::approveSelected, this::revokeSelected, this::overrideSelected, this::onClose));
		addButtonRow(height - 28, 110,
				List.of(Component.translatable("gui.mtr_brsignal_addon.web_token.generate"), Component.translatable("gui.mtr_brsignal_addon.web_token.list"), Component.translatable("gui.mtr_brsignal_addon.web_token.revoke")),
				List.of(ClientWebTokenActions::generateAndOpen, () -> ClientWebTokenActions.command("list"), () -> ClientWebTokenActions.command("revocation")));
		requestRefresh();
		refreshTicks = 20;
		setInitialFocus(searchBox);
	}

	private void addButtonRow(int y, int preferredWidth, List<Component> labels, List<Runnable> actions) {
		final int gap = Math.min(10, Math.max(2, width / 100));
		final int count = labels.size();
		final int buttonWidth = Math.max(1, Math.min(preferredWidth, (width - 20 - gap * (count - 1)) / count));
		final int startX = (width - (buttonWidth * count + gap * (count - 1))) / 2;
		for (int index = 0; index < count; index++) {
			final Runnable action = actions.get(index);
			addRenderableWidget(Button.builder(labels.get(index), button -> action.run())
					.bounds(startX + (buttonWidth + gap) * index, y, buttonWidth, 18).tooltip(Tooltip.create(labels.get(index))).build());
		}
	}

	private int visibleRows() {
		return Math.max(0, (height - FOOTER_HEIGHT - LIST_TOP) / ROW_HEIGHT);
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
		hoveredText = null;
		drawHeader(guiGraphics);
		drawEntries(guiGraphics, mouseX, mouseY);
		if (hoveredText != null) guiGraphics.renderTooltip(font, font.split(hoveredText, Math.max(80, width - 30)), mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && mouseX >= leftX && mouseX < sx(CONTENT_WIDTH) && mouseY >= LIST_TOP - 24 && mouseY < LIST_TOP) {
			for (int col = HEADERS.length - 1; col >= 0; col--) if (mouseX >= sx(COLUMN_OFFSETS[col])) {
				descending = sortColumn == col && !descending;
				sortColumn = col;
				syncEntries();
				return true;
			}
		}
		if (button == 0 && mouseX >= leftX && mouseX < sx(CONTENT_WIDTH) && mouseY >= LIST_TOP && mouseY < LIST_TOP + visibleRows() * ROW_HEIGHT) {
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
		if (mouseY < LIST_TOP || mouseY >= height - FOOTER_HEIGHT) return super.mouseScrolled(mouseX, mouseY, delta);
		final int visibleRows = visibleRows();
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
		final var source = ClientDispatcherData.getEntries();
		if (source == lastSource && query.equals(lastQuery) && stateFilter.equals(lastFilter)
				&& sortColumn == lastSort && descending == lastDescending) {
			clampScroll();
			return;
		}
		lastSource = source;
		lastQuery = query;
		lastFilter = stateFilter;
		lastSort = sortColumn;
		lastDescending = descending;
		final ClientDispatcherData.Entry selectedEntry = selectedEntry();
		final long selectedVehicleId = selectedEntry == null ? Long.MIN_VALUE : selectedEntry.vehicleId();
		entries.clear();
		final String needle = query.toLowerCase(Locale.ROOT).trim();
		for (final var entry : source) {
			final String text = String.join(" ", entry.vehicleCode(), entry.routeName(), entry.nextStation(), entry.destination(), entry.state()).toLowerCase(Locale.ROOT);
			final boolean matchesState = switch (stateFilter) {
				case "waiting" -> List.of("WAITING", "REQUESTED", "CHECKING", "APPROACHING").contains(entry.state());
				case "authorized" -> entry.authorized();
				case "problem" -> List.of("DENIED", "INVALID", "REVOKED", "CANCELED").contains(entry.state());
				default -> true;
			};
			if (matchesState && text.contains(needle)) entries.add(entry);
		}
		Comparator<ClientDispatcherData.Entry> comparator = switch (sortColumn) {
			case 5 -> Comparator.comparingDouble(ClientDispatcherData.Entry::control);
			case 6 -> Comparator.comparingDouble(ClientDispatcherData.Entry::requestEnd);
			case 7 -> Comparator.comparingDouble(ClientDispatcherData.Entry::authorizationEnd);
			case 8 -> Comparator.comparingDouble(ClientDispatcherData.Entry::head);
			case 9 -> Comparator.comparingInt(ClientDispatcherData.Entry::occupiedBlocks);
			default -> Comparator.comparing(entry -> rowValues(entry)[sortColumn], String.CASE_INSENSITIVE_ORDER);
		};
		entries.sort((descending ? comparator.reversed() : comparator).thenComparingLong(ClientDispatcherData.Entry::vehicleId));
		selectedIndex = -1;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).vehicleId() == selectedVehicleId) {
				selectedIndex = i;
				break;
			}
		}
		clampScroll();
	}

	private void clampScroll() { scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - visibleRows()))); }

	private void approveSelected() {
		final ClientDispatcherData.Entry entry = selectedEntry();
		if (entry != null) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("approve", entry.vehicleId()));
		}
	}

	private void revokeSelected() {
		final ClientDispatcherData.Entry entry = selectedEntry();
		if (entry != null) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("revoke", entry.vehicleId()));
		}
	}

	private void overrideSelected() {
		final ClientDispatcherData.Entry entry = selectedEntry();
		if (entry != null) {
			Network.CHANNEL.sendToServer(new DispatcherActionPacket("override", entry.vehicleId()));
		}
	}

	private ClientDispatcherData.Entry selectedEntry() {
		return selectedIndex >= 0 && selectedIndex < entries.size() ? entries.get(selectedIndex) : null;
	}

	private void drawHeader(GuiGraphics guiGraphics) {
		for (int i = 0; i < HEADERS.length; i++) {
			guiGraphics.drawString(font, fit(HEADERS[i] + (sortColumn == i ? descending ? " v" : " ^" : ""), columnWidth(i)), sx(COLUMN_OFFSETS[i]), LIST_TOP - 22, 0xFFFFFF00);
		}
		guiGraphics.pose().pushPose();
		guiGraphics.pose().scale(0.75F, 0.75F, 1.0F);
		for (int i = 0; i < CHINESE_HEADERS.length; i++) {
			guiGraphics.drawString(font, fit(CHINESE_HEADERS[i], (int)(columnWidth(i) / 0.75F)), (int) (sx(COLUMN_OFFSETS[i]) / 0.75F), (int) ((LIST_TOP - 11) / 0.75F), 0xFFC0C0C0);
		}
		guiGraphics.pose().popPose();
	}

	private void drawEntries(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (entries.isEmpty()) guiGraphics.drawCenteredString(font, label("empty"), width / 2, LIST_TOP + 4, 0xFFCCCCCC);
		final int visibleRows = visibleRows();
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
			drawRow(guiGraphics, entry, y, mouseX, mouseY);
		}
	}

	private static String[] rowValues(ClientDispatcherData.Entry entry) {
		return new String[] {
				entry.vehicleCode(),
				entry.oneShotOverride() ? "OVERRIDE" : entry.state(),
				entry.routeName(),
				entry.nextStation(),
				entry.destination(),
				String.format("%.1f", entry.control()),
				String.format("%.1f", entry.requestEnd()),
				String.format("%.1f", entry.authorizationEnd()),
				String.format("%.1f", entry.head()),
				entry.occupiedBlocks() + "/" + entry.authorizedBlocks() + "/" + entry.lockedBlocks()
		};
	}

	private void drawRow(GuiGraphics guiGraphics, ClientDispatcherData.Entry entry, int y, int mouseX, int mouseY) {
		final String[] values = rowValues(entry);
		for (int i = 0; i < values.length; i++) {
			final int x = sx(COLUMN_OFFSETS[i]), w = columnWidth(i);
			guiGraphics.drawString(font, fit(values[i], w), x, y, i == 1 ? stateColor(values[i]) : 0xFFFFFFFF);
			if (mouseX >= x && mouseX < x + w && mouseY >= y - 2 && mouseY < y + ROW_HEIGHT - 2)
				hoveredText = Component.literal(HEADERS[i] + ": " + values[i]);
		}
	}

	private int columnWidth(int column) {
		return Math.max(0, sx(column + 1 < COLUMN_OFFSETS.length ? COLUMN_OFFSETS[column + 1] : CONTENT_WIDTH) - sx(COLUMN_OFFSETS[column]) - 4);
	}
	private String fit(String text, int available) {
		if (font.width(text) <= available) return text;
		return available < font.width("...") ? "" : font.plainSubstrByWidth(text, available - font.width("...")) + "...";
	}
	private static Component label(String key) { return Component.translatable("screen.mtr_brsignal_addon.dispatch." + key); }

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
