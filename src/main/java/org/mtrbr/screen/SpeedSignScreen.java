package org.mtrbr.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mtrbr.block.SpeedSignBlock;
import org.mtrbr.block.SpeedSignBlockEntity;
import org.mtrbr.block.SpeedSignMount;
import org.mtrbr.data.SpeedSignText;
import org.mtrbr.network.Network;
import org.mtrbr.network.SetSpeedSignPacket;

public final class SpeedSignScreen extends Screen {
    private final BlockPos pos;
    private EditBox upper;
    private EditBox lower;
    private SpeedSignMount mount;
    private final Button[] mountButtons = new Button[SpeedSignMount.values().length];
    private Button save;
    private boolean doubleLine;

    public SpeedSignScreen(BlockPos pos) {
        super(Component.translatable("screen.mtr_brsignal_addon.speed_sign.title"));
        this.pos = pos.immutable();
    }

    @Override
    protected void init() {
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(pos) instanceof SpeedSignBlockEntity entity)) {
            onClose();
            return;
        }
        doubleLine = ((SpeedSignBlock) entity.getBlockState().getBlock()).isDoubleLine();
        String previousUpper = upper == null ? entity.text().upper() : upper.getValue();
        String previousLower = lower == null ? entity.text().lower() : lower.getValue();
        if (mount == null) mount = SpeedSignBlock.mount(entity.getBlockState());
        int w = Math.min(240, width - 32), x = (width - w) / 2, y = height / 2 - 60;
        upper = new EditBox(font, x, y, w, 20, label(doubleLine ? "upper" : "speed"));
        upper.setMaxLength(SpeedSignText.MAX_LENGTH);
        upper.setValue(previousUpper);
        addRenderableWidget(upper);
        if (doubleLine) {
            lower = new EditBox(font, x, y + 38, w, 20, label("lower"));
            lower.setMaxLength(3);
            lower.setValue(previousLower);
            addRenderableWidget(lower);
        }
        for (SpeedSignMount option : SpeedSignMount.values()) {
            int index = option.ordinal();
            int left = x + index * w / 3;
            int right = x + (index + 1) * w / 3;
            mountButtons[index] = addRenderableWidget(Button.builder(label("mount." + option.name().toLowerCase(java.util.Locale.ROOT)),
                    button -> selectMount(option)).bounds(left, y + 66, right - left - 2, 20).build());
        }
        selectMount(mount);
        save = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> confirm())
                .bounds(x, y + 98, (w - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(x + (w + 8) / 2, y + 98, (w - 8) / 2, 20).build());
        upper.setResponder(value -> validate());
        if (lower != null) lower.setResponder(value -> validate());
        validate();
        setInitialFocus(upper);
    }

    private static Component label(String key) { return Component.translatable("screen.mtr_brsignal_addon.speed_sign." + key); }

    private SpeedSignText validated() {
        return SpeedSignText.validate(upper.getValue(), doubleLine ? lower.getValue() : "", doubleLine);
    }

    private void validate() { save.active = validated() != null; }

    private void selectMount(SpeedSignMount selected) {
        mount = selected;
        for (SpeedSignMount option : SpeedSignMount.values()) {
            mountButtons[option.ordinal()].active = option != selected;
        }
    }

    private void confirm() {
        SpeedSignText text = validated();
        if (text == null) return;
        Network.CHANNEL.sendToServer(new SetSpeedSignPacket(pos, text.upper(), text.lower(), mount));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        if (upper == null) return;
        graphics.drawCenteredString(font, title, width / 2, upper.getY() - 32, 0xFFFFFF);
        graphics.drawString(font, label(doubleLine ? "upper" : "speed"), upper.getX(), upper.getY() - 12, 0xCCCCCC);
        if (lower != null) graphics.drawString(font, label("lower"), lower.getX(), lower.getY() - 12, 0xCCCCCC);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!save.active) {
            graphics.drawWordWrap(font, label("invalid"), Math.max(12, (width - 280) / 2), save.getY() + 27,
                    Math.min(280, width - 24), 0xFF7777);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
