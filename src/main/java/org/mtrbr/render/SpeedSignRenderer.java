package org.mtrbr.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.mtrbr.MTRBR;
import org.mtrbr.block.SpeedSignBlock;
import org.mtrbr.block.SpeedSignBlockEntity;

import java.util.HashMap;
import java.util.Map;

/** Smooth, normally lit decoration. Uses a shared atlas, never a per-sign GPU texture. */
public final class SpeedSignRenderer implements BlockEntityRenderer<SpeedSignBlockEntity> {
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static JsonObject artwork;
    private static boolean loadFailed;
    private static final float FRONT = -0.14583333F;
    private static final float BACK = -0.125F;

    public SpeedSignRenderer(BlockEntityRendererProvider.Context context) {}

    public static void clearCache() { artwork = null; loadFailed = false; }

    private static ResourceLocation texture(String name) {
        return TEXTURES.computeIfAbsent(name, key -> ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID,
                "textures/block/speed_sign/" + key + ".png"));
    }

    private static JsonObject artwork() {
        if (artwork == null && !loadFailed) {
            var location = ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "models/speed_sign_artwork.json");
            try (var reader = Minecraft.getInstance().getResourceManager().openAsReader(location)) {
                artwork = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception exception) {
                loadFailed = true;
                System.err.println("[MTRBR] Cannot load speed sign artwork: " + exception);
            }
        }
        return artwork;
    }

    @Override
    public void render(SpeedSignBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffers, int light, int overlay) {
        JsonObject art = artwork();
        if (art == null) return;
        var state = entity.getBlockState();
        var block = (SpeedSignBlock) state.getBlock();
        var text = entity.text();
        var mount = SpeedSignBlock.mount(state);
        String shape = block.isArrow() ? "arrow" : block.isWarning() ? "triangle" : "circle";
        String color = block.isWarning() ? "yellow" : "red";
        float offset = mount.offset(block.isArrow());
        stack.pushPose();
        stack.translate(0.5, 0, 0.5);
        stack.mulPose(Axis.YP.rotationDegrees(-SpeedSignBlock.angle(state)));

        float poleHeight = mount.poleHeight(block.isArrow());
        pole(stack.last(), buffers.getBuffer(RenderType.entitySolid(texture("mtr_signal_pole"))), poleHeight, light, overlay);
        stack.translate(0, offset, 0);
        String front = shape + "_" + color + (block.isArrow() ? "_" + block.arrow() : "");
        face(stack.last(), buffers.getBuffer(SpeedSignRenderTypes.smooth(texture(front))), 0, 0, 1, 1, FRONT,
                0, 0, 1, 1, 0xFFFFFF, light, overlay, false);
        face(stack.last(), buffers.getBuffer(SpeedSignRenderTypes.smooth(texture(shape + "_" + color + "_back"))), 0, 0, 1, 1, BACK,
                0, 0, 1, 1, 0xFFFFFF, light, overlay, true);
        var contour = art.getAsJsonObject("contours").getAsJsonArray(shape);
        var edges = buffers.getBuffer(RenderType.entitySolid(texture("white")));
        for (int i = 0; i < contour.size(); i++) {
            var a = contour.get(i).getAsJsonArray();
            var b = contour.get((i + 1) % contour.size()).getAsJsonArray();
            float x0 = 0.5F - a.get(0).getAsFloat(), y0 = a.get(1).getAsFloat();
            float x1 = 0.5F - b.get(0).getAsFloat(), y1 = b.get(1).getAsFloat();
            float length = (float) Math.hypot(x1 - x0, y1 - y0);
            float nx = (y0 - y1) / length, ny = (x1 - x0) / length;
            vertex(stack.last(), edges, x1, y1, FRONT, 1, 0, 0x9C9FA0, light, overlay, nx, ny, 0);
            vertex(stack.last(), edges, x0, y0, FRONT, 0, 0, 0x9C9FA0, light, overlay, nx, ny, 0);
            vertex(stack.last(), edges, x0, y0, BACK, 0, 1, 0x9C9FA0, light, overlay, nx, ny, 0);
            vertex(stack.last(), edges, x1, y1, BACK, 1, 1, 0x9C9FA0, light, overlay, nx, ny, 0);
        }
        if (!block.isArrow()) {
            if (!block.isDoubleLine()) {
                text(stack.last(), buffers, art, text.upper(), block.isWarning() ? .805F : .625F,
                        block.isWarning() ? .26F : .36F, block.isWarning() ? .48F : .55F, light, overlay);
            } else if (text.isTrainClass()) {
                text(stack.last(), buffers, art, text.upper(), block.isWarning() ? .850625F : .774375F,
                        block.isWarning() ? .165F : .18F, block.isWarning() ? .40F : .57F, light, overlay);
                text(stack.last(), buffers, art, text.lower(), block.isWarning() ? .655F : .48F,
                        block.isWarning() ? .165F : .26F, block.isWarning() ? .29F : .48F, light, overlay);
            } else {
                text(stack.last(), buffers, art, text.upper(), block.isWarning() ? .850625F : .785F,
                        block.isWarning() ? .165F : .25F, block.isWarning() ? .40F : .48F, light, overlay);
                text(stack.last(), buffers, art, text.lower(), block.isWarning() ? .655F : .465F,
                        block.isWarning() ? .165F : .25F, block.isWarning() ? .29F : .48F, light, overlay);
                float y = block.isWarning() ? .762625F : .625F, w = block.isWarning() ? .32F : .5F;
                face(stack.last(), buffers.getBuffer(RenderType.entitySolid(texture("white"))), .5F-w/2, y-.006F, w, .012F,
                        FRONT-.001F, 0, 0, 1, 1, 0x080808, light, overlay, false);
            }
        }
        stack.popPose();
    }

    private static void text(PoseStack.Pose pose, MultiBufferSource buffers, JsonObject art, String value, float centerY,
                             float height, float maxWidth, int light, int overlay) {
        var glyphs = art.getAsJsonObject("glyphs");
        float width = 0, inkHeight = 0;
        for (int i = 0; i < value.length(); i++) {
            var glyph = glyphs.getAsJsonObject(value.substring(i, i + 1));
            width += glyph.get(i == value.length()-1 ? "w" : "advance").getAsFloat();
            inkHeight = Math.max(inkHeight, glyph.get("h").getAsFloat());
        }
        float scale = Math.min(height / (inkHeight + 4), maxWidth / (width + 4));
        float cursor = .5F - width * scale / 2;
        var buffer = buffers.getBuffer(SpeedSignRenderTypes.smooth(texture("glyphs")));
        for (int i = 0; i < value.length(); i++) {
            var glyph = glyphs.getAsJsonObject(value.substring(i, i + 1));
            float w = glyph.get("w").getAsFloat(), h = glyph.get("h").getAsFloat();
            float u = glyph.get("u").getAsFloat(), v = glyph.get("v").getAsFloat();
            face(pose, buffer, cursor, centerY - h*scale/2, w*scale, h*scale, FRONT-.0015F,
                    u, v, w/1024, h/1024, 0xFFFFFF, light, overlay, false);
            cursor += glyph.get("advance").getAsFloat()*scale;
        }
    }

    private static void face(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float w, float h, float z,
                             float u, float v, float uw, float vh, int color, int light, int overlay, boolean back) {
        float left = .5F-x, right = .5F-x-w;
        if (back) {
            vertex(pose, buffer, right, y+h, z, u+uw, v, color, light, overlay, 0, 0, 1);
            vertex(pose, buffer, right, y, z, u+uw, v+vh, color, light, overlay, 0, 0, 1);
            vertex(pose, buffer, left, y, z, u, v+vh, color, light, overlay, 0, 0, 1);
            vertex(pose, buffer, left, y+h, z, u, v, color, light, overlay, 0, 0, 1);
        } else {
            vertex(pose, buffer, left, y+h, z, u, v, color, light, overlay, 0, 0, -1);
            vertex(pose, buffer, left, y, z, u, v+vh, color, light, overlay, 0, 0, -1);
            vertex(pose, buffer, right, y, z, u+uw, v+vh, color, light, overlay, 0, 0, -1);
            vertex(pose, buffer, right, y+h, z, u+uw, v, color, light, overlay, 0, 0, -1);
        }
    }

    private static void pole(PoseStack.Pose pose, VertexConsumer buffer, float height, int light, int overlay) {
        float a = -.125F, b = .125F;
        float[][][] faces = {
                {{b,height,a},{b,0,a},{a,0,a},{a,height,a}}, {{a,height,b},{a,0,b},{b,0,b},{b,height,b}},
                {{a,height,a},{a,0,a},{a,0,b},{a,height,b}}, {{b,height,b},{b,0,b},{b,0,a},{b,height,a}},
                {{a,height,a},{a,height,b},{b,height,b},{b,height,a}}, {{a,0,b},{a,0,a},{b,0,a},{b,0,b}}
        };
        float[][] normals = {{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}};
        for (int f=0; f<faces.length; f++) for (int i=0; i<4; i++) {
            var p = faces[f][i]; var n = normals[f];
            vertex(pose, buffer, p[0],p[1],p[2], i<2 ? .375F : .625F, i==0 || i==3 ? 0 : height,
                    0xFFFFFF,light,overlay,n[0],n[1],n[2]);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, float u, float v,
                               int color, int light, int overlay, float nx, float ny, float nz) {
        buffer.vertex(pose.pose(), x, y, z).color((color >> 16)&255, (color >> 8)&255, color&255, 255)
                .uv(u, v).overlayCoords(overlay).uv2(light).normal(pose.normal(), nx, ny, nz).endVertex();
    }
}
