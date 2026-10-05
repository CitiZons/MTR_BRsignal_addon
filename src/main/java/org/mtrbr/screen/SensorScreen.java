package org.mtrbr.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrbr.data.SensorConfig;
import org.mtrbr.network.*;

public final class SensorScreen extends Screen {
    private final SensorViewPacket view;
    private final SensorConfig config;
    private EditBox name, ticks;
    private boolean enabled, pulse;
    private String error = "";
    public SensorScreen(SensorViewPacket view) {
        super(label("title")); this.view = view;
        config = SensorConfig.parse(view.settings()); enabled = config.enabled(); pulse = config.pulse();
    }
    @Override protected void init() {
        int w = Math.min(280, width - 24), x = (width - w) / 2, y = Math.max(30, height / 2 - 100);
        String oldName = name == null ? config.name() : name.getValue(), oldTicks = ticks == null ? "" + config.pulseTicks() : ticks.getValue();
        name = new EditBox(font, x, y + 14, w, 20, label("name")); name.setMaxLength(40); name.setValue(oldName); name.setEditable(view.editable()); addRenderableWidget(name);
        Button mode = addRenderableWidget(Button.builder(modeLabel(), b -> { enabled = !enabled; b.setMessage(modeLabel()); }).bounds(x, y + 40, w, 20).build()); mode.active = view.editable();
        Button output = addRenderableWidget(Button.builder(outputLabel(), b -> { pulse = !pulse; b.setMessage(outputLabel()); }).bounds(x, y + 66, w, 20).build()); output.active = view.editable();
        ticks = new EditBox(font, x, y + 105, w, 20, label("ticks")); ticks.setMaxLength(4); ticks.setValue(oldTicks); ticks.setEditable(view.editable()); addRenderableWidget(ticks);
        Button bind = addRenderableWidget(Button.builder(label("bind"), b -> {
            var dim = minecraft.level.dimension().location();
            org.mtrbr.client.ClientWebTokenActions.generateAndOpenSensor(dim + "@" + view.pos().asLong());
        }).bounds(x, y + 149, w, 20).build()); bind.active = view.editable();
        Button save = addRenderableWidget(Button.builder(label("save"), b -> {
            try {
                var c = new SensorConfig(name.getValue(), enabled, pulse, Integer.parseInt(ticks.getValue()), config.approach(), config.targets());
                Network.CHANNEL.sendToServer(new SensorPacket(view.pos(), c.json()));
            } catch (RuntimeException ex) { error = label("invalid").getString(); }
        }).bounds(x, y + 175, w / 2 - 3, 20).build()); save.active = view.editable();
        addRenderableWidget(Button.builder(label("close"), b -> onClose()).bounds(x + w / 2 + 3, y + 175, w / 2 - 3, 20).build());
    }
    private static Component label(String key, Object... args) { return Component.translatable("screen.mtr_brsignal_addon.sensor." + key, args); }
    private Component modeLabel() { return label(enabled ? "advanced" : "native"); }
    private Component outputLabel() { return label(pulse ? "pulse" : "continuous"); }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        if (name != null) {
            g.drawCenteredString(font, title, width / 2, name.getY() - 30, 0xFFFFFF);
            g.drawString(font, label(view.editable() ? "name" : "readonly"), name.getX(), name.getY() - 12, 0xCCCCCC);
            g.drawString(font, label("ticks"), ticks.getX(), ticks.getY() - 12, 0xCCCCCC);
            g.drawString(font, label("summary", config.approach().size(), config.targets().size()), ticks.getX(), ticks.getY() + 27, 0xCCCCCC);
            g.drawCenteredString(font, error, width / 2, height - 15, 0xFF7777);
        }
        super.render(g, mx, my, delta);
    }
    @Override public boolean isPauseScreen() { return false; }
}
