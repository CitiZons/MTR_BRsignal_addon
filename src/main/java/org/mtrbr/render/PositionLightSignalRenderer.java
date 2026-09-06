package org.mtrbr.render;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtrbr.MTRBR;
import org.mtrbr.block.IndicatorMount;
import org.mtrbr.block.PositionLightSignalBlock;
import org.mtrbr.block.PositionLightSignalBlockEntity;
import org.mtrbr.logic.SignalLogic;
import java.util.*;

public final class PositionLightSignalRenderer implements BlockEntityRenderer<PositionLightSignalBlockEntity> {
    private static final Map<String, List<RepeatingSignalModel.Surface>> SURFACES = new HashMap<>();
    public PositionLightSignalRenderer(BlockEntityRendererProvider.Context context) {}
    public static void clearModelCache() { SURFACES.clear(); }

    private static List<RepeatingSignalModel.Surface> surfaces(String display) {
        return SURFACES.computeIfAbsent(display, key -> {
            final ResourceLocation location = new ResourceLocation(MTRBR.MOD_ID, "models/block/position_light_ground_" + key + ".json");
            try (var reader = Minecraft.getInstance().getResourceManager().openAsReader(location)) {
                return RepeatingSignalModel.parse(JsonParser.parseReader(reader).getAsJsonObject(), ":block/position_light/").stream()
                    .filter(surface -> surface.direction().equals("north"))
                    .filter(surface -> surface.texture().endsWith("/white") || surface.texture().endsWith("/red") || surface.texture().endsWith("/yellow")).toList();
            } catch (Exception error) {
                System.err.println("[MTRBR-RENDER] Could not load " + location + ": " + error);
                return List.of();
            }
        });
    }

    @Override
    public void render(PositionLightSignalBlockEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        final var state = entity.getBlockState();
        final boolean proceed = state.getValue(PositionLightSignalBlock.PROCEED);
        if (!proceed && IndicatorMount.isHanging(state)) return;
        final String display = proceed ? "proceed" : ((PositionLightSignalBlock) state.getBlock()).isYellow() ? "yellow" : "red";
        final var pos = entity.getBlockPos();
        final float angle = SignalLogic.getIndicatorAngle(state);
        for (var surface : surfaces(display)) {
            final ResourceLocation texture = new ResourceLocation(surface.texture());
            MainRenderer.scheduleRender(new Identifier(texture.getNamespace(), "textures/" + texture.getPath() + ".png"), false, QueuedRenderLayer.LIGHT, (graphics, camera) -> {
                graphics.push();
                graphics.translate(pos.getX()+0.5-camera.getXMapped(), pos.getY()+0.5-camera.getYMapped(), pos.getZ()+0.5-camera.getZMapped());
                graphics.rotateYDegrees(-angle);
                graphics.translate(-0.5, -0.5, -0.5);
                final float[] v = surface.vertices(), uv = surface.uv();
                IDrawing.drawTexture(graphics, v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],
                    uv[2],uv[3],uv[0],uv[1],org.mtr.mapping.holder.Direction.NORTH,0xFFFFFFFF,GraphicsHolder.getDefaultLight());
                graphics.pop();
            });
        }
    }
}
