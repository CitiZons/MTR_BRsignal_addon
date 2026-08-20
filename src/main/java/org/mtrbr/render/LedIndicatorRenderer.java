package org.mtrbr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.mod.block.BlockSignalBase;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtrbr.MTRBR;
import org.mtrbr.block.LedIndicatorBlock;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.data.ContentTextureRegistry;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.logic.SignalLogic;

/**
 * LED 进路显示器渲染：基础黑色屏幕 + 绑定信号机已开放进路对应的显示图层。
 * 图层贴图按 ContentTextureRegistry 约定命名，贴图缺失时自动跳过。
 */
public final class LedIndicatorRenderer implements BlockEntityRenderer<LedIndicatorBlockEntity> {


	/** 节流调试日志：内容变化或每 100 tick 输出一次，用于定位指示器不显示的原因。 */
	private static String lastDebugLine = "";
	private static long lastDebugTick = -1;

	private static void debugLog(Level level, String line) {
		final long tick = level == null ? 0 : level.getGameTime();
		if (!line.equals(lastDebugLine) || tick - lastDebugTick >= 100) {
			lastDebugLine = line;
			lastDebugTick = tick;
			System.out.println("[MTRBR-RENDER] " + line);
		}
	}
	public LedIndicatorRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(LedIndicatorBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		final Level level = blockEntity.getLevel();
		if (level == null) {
			return;
		}
		final BlockPos pos = blockEntity.getBlockPos();
		final BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof LedIndicatorBlock)) {
			return;
		}
		final float angle = SignalLogic.getIndicatorAngle(state);

		BlockPos boundSignalPos = ClientIndicatorBindings.get(pos);
		if (boundSignalPos == null) {
			boundSignalPos = blockEntity.getBoundSignalPos();
		}
		if (boundSignalPos == null) {
			debugLog(level, pos + " NO-BINDING");
			return;
		}
		final BlockEntity signalEntity = level.getBlockEntity(boundSignalPos);
		if (!(signalEntity instanceof BlockSignalBase.BlockEntityBase)) {
			debugLog(level, pos + " -> " + boundSignalPos + " SIGNAL-MISSING " + (signalEntity == null ? "null" : signalEntity.getClass().getSimpleName()));
			return;
		}
		final int aspect = SignalLogic.getSignalAspect(level, boundSignalPos, signalEntity, false);
		if (aspect == 1) {
			debugLog(level, pos + " -> " + boundSignalPos + " ASPECT-RED");
			return; // 信号红灯：进路显示立即熄灭
		}
		final org.mtrbr.client.ServerAspectCache.DisplayState serverState = org.mtrbr.client.ServerAspectCache.getState(boundSignalPos, false);
		final java.util.List<RouteBinding> bindings;
		if (serverState != null && !serverState.authorizationId().isEmpty()) {
			// 服务端已授权：只显示该授权实际开放的进路内容，不再扫描客户端车辆 Path。
			final String authorizedContent = serverState.routeContent();
			bindings = ClientBindings.get(boundSignalPos).stream()
					.filter(binding -> binding.content().equalsIgnoreCase(authorizedContent))
					.toList();
		} else {
			// 服务端未授权：进路指示器不显示。
			bindings = java.util.List.of();
		}
		debugLog(level, pos + " -> " + boundSignalPos + " aspect=" + aspect + " open=" + bindings);
		for (final RouteBinding binding : bindings) {
			if (binding.content().equalsIgnoreCase("path=NULL")) {
				continue; // path=NULL：不显示
			}
			if (!binding.content().toLowerCase(java.util.Locale.ROOT).startsWith("path=")) {
				continue;
			}
			final ResourceLocation texture = ContentTextureRegistry.getTexture(binding.content());
			if (texture != null && Minecraft.getInstance().getResourceManager().getResource(texture).isPresent()) {
				drawScreen(pos, angle, new org.mtr.mapping.holder.Identifier(texture.getNamespace(), texture.getPath()));
			}
		}
	}

	private static void drawScreen(BlockPos pos, float angle, org.mtr.mapping.holder.Identifier texture) {
		// 模型空间：屏幕面在 z=5/16；渲染层按 1 - 5/16 + 0.005 = 0.6925 绘制，经 -(angle+180) 旋转后落在北侧显示面
		MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, cameraOffset) -> {
			graphicsHolder.push();
			graphicsHolder.translate(pos.getX() + 0.5 - cameraOffset.getXMapped(), pos.getY() + 0.5 - cameraOffset.getYMapped(), pos.getZ() + 0.5 - cameraOffset.getZMapped());
			graphicsHolder.rotateYDegrees(-(angle + 180));
			graphicsHolder.translate(-0.5, -0.5, -0.5);
			IDrawing.drawTexture(graphicsHolder,
					0.3125F, 0.4375F, 0.3F,
					0.6875F, 0.4375F, 0.3F,
					0.6875F, 0.0625F, 0.3F,
					0.3125F, 0.0625F, 0.3F,
					1, 1, 0, 0, org.mtr.mapping.holder.Direction.UP, 0xFFFFFFFF, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();
		});
	}
}
