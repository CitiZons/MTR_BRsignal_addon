package org.mtrbr.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

final class SpeedSignRenderTypes extends RenderStateShard {
    private static final Map<ResourceLocation, RenderType> SMOOTH = new HashMap<>();

    private SpeedSignRenderTypes() { super("speed_sign", () -> {}, () -> {}); }

    static RenderType smooth(ResourceLocation texture) {
        // Vanilla entity layers override .mcmeta filtering. Request linear filtering here as well.
        return SMOOTH.computeIfAbsent(texture, key -> RenderType.create("mtrbr_speed_sign",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, true,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(key, true, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(CULL).setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                        .createCompositeState(true)));
    }
}
