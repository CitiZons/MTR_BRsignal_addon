package org.mtrbr.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mtrbr.data.RouteContent;
import org.mtrbr.network.Network;
import org.mtrbr.network.SetRouteBindingPacket;

/**
 * 进路绑定内容输入对话框。
 * 格式：route=X（X=1–6）或 path=Y（Y=0-20 数字 / 大写字母 / UF,US,DF,DS）。
 */
public final class RouteTextInputScreen extends Screen {

	private final BlockPos signalPos;
	private final BlockPos nodePos;
	private EditBox editBox;
	private String error = null;

	public RouteTextInputScreen(BlockPos signalPos, BlockPos nodePos) {
		super(Component.literal("进路绑定"));
		this.signalPos = signalPos;
		this.nodePos = nodePos;
	}

	@Override
	protected void init() {
		editBox = new EditBox(font, width / 2 - 100, height / 2 - 30, 200, 20, Component.literal("进路内容"));
		editBox.setMaxLength(16);
		editBox.setValue("route=");
		addRenderableWidget(editBox);
		setInitialFocus(editBox);

		addRenderableWidget(Button.builder(Component.literal("确定"), button -> confirm()).bounds(width / 2 - 110, height / 2 + 10, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose()).bounds(width / 2 + 10, height / 2 + 10, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		guiGraphics.fillGradient(0, 0, width, height, 0xB0404040, 0xB0404040);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.drawCenteredString(font, "信号机 " + signalPos + " -> 节点 " + nodePos, width / 2, height / 2 - 60, 0xFFFFFFFF);
		guiGraphics.drawCenteredString(font, "格式: route=1–6  或  path=0-20/大写字母/UF,US,DF,DS,DN,DR,UP,UR/adl,adr,arl,arr,atl,atm,atr", width / 2, height / 2 - 45, 0xFFAAAAAA);
		if (error != null) {
			guiGraphics.drawCenteredString(font, error, width / 2, height / 2 + 38, 0xFFFF5555);
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (editBox.keyPressed(keyCode, scanCode, modifiers)) {
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (editBox.charTyped(codePoint, modifiers)) {
			return true;
		}
		return super.charTyped(codePoint, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void confirm() {
		final String validated = RouteContent.validate(editBox.getValue());
		if (validated == null) {
			error = "格式错误，请检查输入";
			return;
		}
		Network.CHANNEL.sendToServer(new SetRouteBindingPacket(signalPos, nodePos, validated));
		onClose();
	}
}
