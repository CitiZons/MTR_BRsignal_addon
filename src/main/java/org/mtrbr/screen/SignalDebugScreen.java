package org.mtrbr.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.block.RepeatingSignalBlockEntity;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.data.ClientSignalNames;
import org.mtrbr.data.NodeBinding;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.data.RouteContent;
import org.mtrbr.logic.SignalLogic;
import org.mtrbr.client.ServerAspectCache;
import org.mtrbr.network.Network;
import org.mtrbr.network.RemoveRouteBindingPacket;
import org.mtrbr.network.SetRouteBindingPacket;
import org.mtrbr.network.SetSignalNamePacket;
import org.mtrbr.network.ToggleNodeBindingDirectionPacket;
import org.mtrbr.network.UnbindIndicatorPacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 信号机调试 GUI：自适应屏幕宽度；命名、灯状态、节点、进路指示器（可解绑）、进路绑定（编辑/保存/删除）。 */
public final class SignalDebugScreen extends Screen {

	private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_\\-]{0,39}$");

	private static final int CONTENT_WIDTH = 440;
	// 相对内容区左侧的基准偏移（按内容宽度等比缩放，适配小窗口）
	private static final int NAME_EDIT = 105;
	private static final int NAME_SAVE = 218;
	private static final int UNBIND = 150;
	private static final int ROUTE_EDIT = 105;
	private static final int ROUTE_SAVE = 215;
	private static final int ROUTE_DELETE = 260;
	private static final int NAME_ROW_TOP = 40;
	private static final int NODE_ROW_TOP = 86;
	private static final int INDICATOR_HEADER_BASELINE = 110;
	private static final int INDICATOR_ROW_BASELINE = 119;
	private static final int BUTTON_TEXT_OFFSET = 5;

	private final BlockPos signalPos;
	private final List<Integer> rowYs = new ArrayList<>();
	private final List<BlockPos> boundIndicatorPositions = new ArrayList<>();
	private final Set<BlockPos> unboundIndicators = new HashSet<>();
	private List<RouteBinding> bindings = List.of();
	private EditBox nameBox;
	private int routeHeaderY = 134;
	private String error = null;
	private boolean needsRebuild = false;
	private int leftX = 0;
	private float scale = 1.0F;

	public SignalDebugScreen(BlockPos signalPos) {
		super(Component.literal("信号机调试"));
		this.signalPos = signalPos;
	}

	@Override
	protected void init() {
		leftX = Math.max(10, (width - CONTENT_WIDTH) / 2);
		scale = Math.min(1.0F, (float) (width - 20) / CONTENT_WIDTH);
		bindings = ClientBindings.get(signalPos);
		rowYs.clear();
		boundIndicatorPositions.clear();
		boundIndicatorPositions.addAll(findBoundIndicators(Minecraft.getInstance().level, signalPos));
		boundIndicatorPositions.removeIf(unboundIndicators::contains);
		addRenderableWidget(Button.builder(Component.literal("刷新"), button -> needsRebuild = true).bounds(sx(110), height - 28, 50, 18).build());
		addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose()).bounds(sx(280), height - 28, 50, 18).build());

		nameBox = new EditBox(font, sx(NAME_EDIT), NAME_ROW_TOP, 105, 16, Component.literal("命名"));
		nameBox.setMaxLength(40);
		final String currentName = ClientSignalNames.get(signalPos);
		if (currentName != null) {
			nameBox.setValue(currentName);
		}
		addRenderableWidget(nameBox);
		addRenderableWidget(Button.builder(Component.literal("保存"), button -> saveName()).bounds(sx(NAME_SAVE), NAME_ROW_TOP, 42, 16).build());
		addRenderableWidget(Button.builder(Component.literal("切换方向"), button -> toggleNodeBindingDirection()).bounds(sx(210), NODE_ROW_TOP, 66, 16).build());

		int y = 118;
		for (final BlockPos indicatorPos : boundIndicatorPositions) {
			addRenderableWidget(Button.builder(Component.literal("解绑"), button -> unbindIndicator(indicatorPos)).bounds(sx(UNBIND), y - BUTTON_TEXT_OFFSET, 42, 14).build());
			y += 18;
		}
		routeHeaderY = Math.max(134, y + 8);

		int rowY = routeHeaderY + 16;
		for (final RouteBinding binding : bindings) {
			rowYs.add(rowY);
			final EditBox editBox = new EditBox(font, sx(ROUTE_EDIT), rowY, 100, 16, Component.literal("进路"));
			editBox.setMaxLength(16);
			editBox.setValue(binding.content());
			addRenderableWidget(editBox);
			addRenderableWidget(Button.builder(Component.literal("保存"), button -> save(binding, editBox.getValue())).bounds(sx(ROUTE_SAVE), rowY, 40, 16).build());
			addRenderableWidget(Button.builder(Component.literal("删除"), button -> remove(binding)).bounds(sx(ROUTE_DELETE), rowY, 40, 16).build());
			rowY += 20;
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (needsRebuild) {
			needsRebuild = false;
			rebuild();
		}
		guiGraphics.fillGradient(0, 0, width, height, 0xB0404040, 0xB0404040);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		final Level level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}

		final String signalName = ClientSignalNames.get(signalPos);
		guiGraphics.drawCenteredString(font, "信号机 " + (signalName == null ? signalPos : signalName), width / 2, 18, 0xFFFFFFFF);
		guiGraphics.drawString(font, "命名", sx(0), 44, 0xFFFFFFFF);

		final BlockEntity blockEntity = level.getBlockEntity(signalPos);
		String status;
		if (blockEntity instanceof BlockSignalBase.BlockEntityBase entity) {
			status = "正面 " + SignalLogic.getAspectColorName(SignalLogic.getSignalAspect(level, signalPos, blockEntity, false));
			if (entity.isDoubleSided) {
				status += "  背面 " + SignalLogic.getAspectColorName(SignalLogic.getSignalAspect(level, signalPos, blockEntity, true));
			}
		} else {
			status = "非信号机";
		}
		guiGraphics.drawString(font, "状态  " + status, sx(0), 66, 0xFFFFFFFF);
		final ServerAspectCache.DisplayState displayState = ServerAspectCache.getState(signalPos, false);
		if (displayState != null) {
			final String authorization = displayState.authorizationId().isEmpty() ? "无" : displayState.authorizationId();
			guiGraphics.drawString(font, "授权  " + authorization + "  auth=" + displayState.revision(), sx(0), 75, 0xFFCCCCCC);
		}

		final ServerAspectCache.DisplayState serverState = ServerAspectCache.getState(signalPos, false);
		final BlockPos nodePos = serverState == null ? null : serverState.nodePos();
		final NodeBinding nodeBinding = ClientBindings.getNodeBinding(signalPos);
		final boolean reversed = nodeBinding != null && nodeBinding.reversed();
		guiGraphics.drawString(font, "节点  " + (nodePos == null ? "未找到" : nodePos + (reversed ? "（反向）" : "")), sx(0), 92, 0xFFFFFFFF);

		guiGraphics.drawString(font, "指示器 / 复示信号  " + (boundIndicatorPositions.isEmpty() ? "未绑定" : "已绑定"), sx(0), INDICATOR_HEADER_BASELINE, 0xFFFFFFFF);
		for (int i = 0; i < boundIndicatorPositions.size(); i++) {
			final BlockPos indicatorPos = boundIndicatorPositions.get(i);
			final String type = level.getBlockEntity(indicatorPos) instanceof LedIndicatorBlockEntity ? "LED" : level.getBlockEntity(indicatorPos) instanceof RepeatingSignalBlockEntity ? "复示" : "色灯";
			guiGraphics.drawString(font, "  " + type + " " + indicatorPos, sx(0), INDICATOR_ROW_BASELINE + i * 18, 0xFFFFFFFF);
		}

		guiGraphics.drawString(font, "进路 (" + bindings.size() + ")", sx(0), routeHeaderY, 0xFFFFFFFF);
		for (int i = 0; i < bindings.size() && i < rowYs.size(); i++) {
			final RouteBinding binding = bindings.get(i);
			guiGraphics.drawString(font, "  " + binding.node(), sx(0), rowYs.get(i) + 2, 0xFFFFFFFF);
		}
		if (bindings.isEmpty()) {
			guiGraphics.drawString(font, "  (无)", sx(0), routeHeaderY + 16, 0xFFAAAAAA);
		}

		if (error != null) {
			guiGraphics.drawCenteredString(font, error, width / 2, height - 50, 0xFFFF5555);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int sx(int baseOffset) {
		return leftX + (int) (baseOffset * scale);
	}

	private void rebuild() {
		clearWidgets();
		init();
	}

	private void saveName() {
		final String value = nameBox.getValue() == null ? "" : nameBox.getValue().trim();
		if (!value.isEmpty() && !NAME_PATTERN.matcher(value).matches()) {
			error = "命名需为字母/数字/下划线/连字符，不超过 40，且不能以 _ 或 - 开头";
			return;
		}
		error = null;
		Network.CHANNEL.sendToServer(new SetSignalNamePacket(signalPos, value));
		needsRebuild = true;
	}

	private void toggleNodeBindingDirection() {
		Network.CHANNEL.sendToServer(new ToggleNodeBindingDirectionPacket(signalPos));
		needsRebuild = true;
	}

	private void save(RouteBinding binding, String content) {
		final String validated = RouteContent.validate(content);
		if (validated == null) {
			error = "格式错误: " + content;
			return;
		}
		error = null;
		Network.CHANNEL.sendToServer(new SetRouteBindingPacket(signalPos, binding.node(), validated));
		needsRebuild = true;
	}

	private void remove(RouteBinding binding) {
		Network.CHANNEL.sendToServer(new RemoveRouteBindingPacket(signalPos, binding.node()));
		needsRebuild = true;
	}

	private void unbindIndicator(BlockPos indicatorPos) {
		Network.CHANNEL.sendToServer(new UnbindIndicatorPacket(indicatorPos));
		ClientIndicatorBindings.remove(indicatorPos);
		unboundIndicators.add(indicatorPos);
		needsRebuild = true;
	}

	private static List<BlockPos> findBoundIndicators(Level level, BlockPos signalPos) {
		final Set<BlockPos> result = new LinkedHashSet<>();
		if (level != null) {
			ClientIndicatorBindings.getAll().forEach((indicatorPos, boundSignal) -> {
				if (signalPos.equals(boundSignal)) {
					result.add(indicatorPos);
				}
			});
			final int chunkX = signalPos.getX() >> 4;
			final int chunkZ = signalPos.getZ() >> 4;
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					final net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX + x, chunkZ + z);
					if (chunk == null) {
						continue;
					}
					for (final BlockPos pos : chunk.getBlockEntitiesPos()) {
						final BlockEntity blockEntity = level.getBlockEntity(pos);
						if (blockEntity instanceof RepeatingSignalBlockEntity repeating && signalPos.equals(repeating.getBoundSignalPos())) {
							result.add(pos);
						}
						if (blockEntity instanceof LedIndicatorBlockEntity led && signalPos.equals(led.getBoundSignalPos())) {
							result.add(pos);
						}
						if (blockEntity instanceof ColorLightIndicatorBlockEntity colorLight && signalPos.equals(colorLight.getBoundSignalPos())) {
							result.add(pos);
						}
					}
				}
			}
		}
		return new ArrayList<>(result);
	}
}
