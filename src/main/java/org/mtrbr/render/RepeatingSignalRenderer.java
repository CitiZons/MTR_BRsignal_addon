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
import org.mtrbr.block.RepeatingSignalBlockEntity;
import org.mtrbr.client.ServerAspectCache;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.logic.RepeatingSignalDisplay;
import org.mtrbr.logic.SignalLogic;
import java.util.*;

public final class RepeatingSignalRenderer implements BlockEntityRenderer<RepeatingSignalBlockEntity> {
    private static final Map<RepeatingSignalDisplay,List<RepeatingSignalModel.Surface>> SURFACES = new EnumMap<>(RepeatingSignalDisplay.class);
    public RepeatingSignalRenderer(BlockEntityRendererProvider.Context context) {}
    public static void clearModelCache() { SURFACES.clear(); }
    private static List<RepeatingSignalModel.Surface> surfaces(RepeatingSignalDisplay display) {
        return SURFACES.computeIfAbsent(display, key -> {
            final ResourceLocation model=new ResourceLocation(MTRBR.MOD_ID,"models/block/banner_repeating_signal_"+key.textureName()+".json");
            try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(model)) {
                return RepeatingSignalModel.parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch(Exception exception) {
                System.err.println("[MTRBR-RENDER] Could not load " + model + ": " + exception);
                return List.of();
            }
        });
    }
    @Override
    public void render(RepeatingSignalBlockEntity entity,float partialTick,PoseStack poseStack,MultiBufferSource buffers,int packedLight,int overlay) {
        if(entity.getLevel()==null) return;
        final var pos=entity.getBlockPos();
        final var state=entity.getBlockState();
        var bound=ClientIndicatorBindings.get(pos);
        if(bound==null) bound=entity.getBoundSignalPos();
        final var display=RepeatingSignalDisplay.forBinding(bound!=null, bound==null ? null : ServerAspectCache.get(bound,false));
        final float angle=SignalLogic.getIndicatorAngle(state);
        final double height=IndicatorMount.offset(state);
        for(final var surface:surfaces(display)) {
            final ResourceLocation texture=new ResourceLocation(surface.texture());
            MainRenderer.scheduleRender(new Identifier(texture.getNamespace(),"textures/"+texture.getPath()+".png"),false,QueuedRenderLayer.LIGHT,(graphics,camera)-> {
                graphics.push();
                graphics.translate(pos.getX()+0.5-camera.getXMapped(),pos.getY()+height+0.5-camera.getYMapped(),pos.getZ()+0.5-camera.getZMapped());
                graphics.rotateYDegrees(-(angle+180));
                graphics.translate(-0.5,-0.5,-0.5);
                final float[] v=surface.vertices(),uv=surface.uv();
                IDrawing.drawTexture(graphics,v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],
                    uv[2],uv[3],uv[0],uv[1],org.mtr.mapping.holder.Direction.valueOf(surface.direction().toUpperCase(Locale.ROOT)),0xFFFFFFFF,GraphicsHolder.getDefaultLight());
                graphics.pop();
            });
        }
    }
}
