package org.mtrbr.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.mtrbr.MTRBR;
import org.mtrbr.render.ColorLightIndicatorRenderer;
import org.mtrbr.render.LedIndicatorRenderer;
import org.mtrbr.render.RepeatingSignalRenderer;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** 客户端设置：注册表填充完成后注册方块实体渲染器。 */
@Mod.EventBusSubscriber(modid = MTRBR.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

	private ClientSetup() {
	}

	@SubscribeEvent
	public static void onReloadListeners(RegisterClientReloadListenersEvent event) {
		event.registerReloadListener((ResourceManagerReloadListener) manager -> RepeatingSignalRenderer.clearModelCache());
	}

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		ItemBlockRenderTypes.setRenderLayer(MTRBR.REPEATING_SIGNAL_BLOCK.get(), RenderType.cutout());
		BlockEntityRenderers.register(MTRBR.REPEATING_SIGNAL_BLOCK_ENTITY.get(), RepeatingSignalRenderer::new);
		ItemBlockRenderTypes.setRenderLayer(MTRBR.LED_INDICATOR_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_4_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_4_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_4_5_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_4_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_4_5_6_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_4_5_6_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_4_5_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_4_5_6_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_4_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_4_5_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_4_5_6_BLOCK.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(MTRBR.COLOR_LIGHT_INDICATOR_1_4_5_BLOCK.get(), RenderType.cutout());
		BlockEntityRenderers.register(MTRBR.LED_INDICATOR_BLOCK_ENTITY.get(), LedIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_4_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_4_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_4_5_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_4_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_4_5_6_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_4_5_6_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_4_5_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_4_5_6_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_4_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_4_5_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_2_3_4_5_6_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
		BlockEntityRenderers.register(MTRBR.COLOR_LIGHT_INDICATOR_1_4_5_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new);
	}
}
