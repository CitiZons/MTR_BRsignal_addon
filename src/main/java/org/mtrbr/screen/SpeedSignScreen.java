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
import org.mtrbr.block.TextSignPoleMount;

public final class SpeedSignScreen extends Screen {
    private final BlockPos pos;
    private EditBox upper;
    private EditBox lower;
    private SpeedSignMount mount;
    private final Button[] mountButtons = new Button[SpeedSignMount.values().length];
    private Button save;
    private boolean doubleLine;
    private TextSignPoleMount poleMount = TextSignPoleMount.CENTER;
    private final Button[] poleButtons = new Button[3];

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
        SpeedSignBlock signBlock = (SpeedSignBlock) entity.getBlockState().getBlock();
        boolean textSign = signBlock.isTextSign();
        if (textSign) poleMount = entity.getBlockState().getValue(SpeedSignBlock.TEXT_POLE);
        doubleLine = signBlock.isDoubleLine();
        String previousUpper = upper == null ? entity.text().upper() : upper.getValue();
        String previousLower = lower == null ? entity.text().lower() : lower.getValue();
        if (mount == null) mount = SpeedSignBlock.mount(entity.getBlockState());
        int w = Math.min(240, width - 32), x = (width - w) / 2, y = height / 2 - 60;
        upper = new EditBox(font, x, y, w, 20, label(textSign ? "text" : (doubleLine ? "upper" : "speed")));
        upper.setMaxLength(textSign ? 3 : SpeedSignText.MAX_LENGTH);
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
        if (textSign) for (TextSignPoleMount option : TextSignPoleMount.values()) {
            int i=option.ordinal(); poleButtons[i]=addRenderableWidget(Button.builder(Component.translatable("screen.mtr_brsignal_addon.speed_sign.pole."+option.name().toLowerCase()), b -> { poleMount=option; for(int k=0;k<3;k++) poleButtons[k].active=k!=option.ordinal(); }).bounds(x+i*w/3,y+90,w/3-2,20).build());
            poleButtons[i].active = i != poleMount.ordinal();
        }
        save = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> confirm())
                .bounds(x, y + (textSign ? 122 : 98), (w - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(x + (w + 8) / 2, y + (textSign ? 122 : 98), (w - 8) / 2, 20).build());
        upper.setResponder(value -> validate());
        if (lower != null) lower.setResponder(value -> validate());
        validate();
        setInitialFocus(upper);
    }

    private static Component label(String key) { return Component.translatable("screen.mtr_brsignal_addon.speed_sign." + key); }

    private SpeedSignText validated() {
        SpeedSignBlock block = (SpeedSignBlock) minecraft.level.getBlockState(pos).getBlock();
        return block.isTextSign() ? SpeedSignText.validateLabel(upper.getValue())
                : SpeedSignText.validate(upper.getValue(), doubleLine ? lower.getValue() : "", doubleLine);
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
        Network.CHANNEL.sendToServer(new SetSpeedSignPacket(pos, text.upper(), text.lower(), mount, poleMount));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        if (upper == null) return;
        graphics.drawCenteredString(font, title, width / 2, upper.getY() - 32, 0xFFFFFF);
        boolean textSign = minecraft.level != null && ((SpeedSignBlock) minecraft.level.getBlockState(pos).getBlock()).isTextSign();
        graphics.drawString(font, label(textSign ? "text" : (doubleLine ? "upper" : "speed")), upper.getX(), upper.getY() - 12, 0xCCCCCC);
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
