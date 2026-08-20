package org.mtrbr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtrbr.MTRBR;
import org.mtrbr.block.ColorLightIndicatorBlock;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.client.ServerAspectCache;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.logic.SignalLogic;

import java.util.List;
import java.util.Locale;

/**
 * 色灯式进路指示器渲染（与 LED 相同思路）：
 * 方块本体用 JSON 模型（indicator_1.json），本渲染器只画“点亮”的 5 个白色色灯，
 * 位置来自 indicator_1_1.bbmodel 的白色灯块（平移 +3.5/+3 后 /16），
 * 用全亮光照（getDefaultLight）实现发光效果。
 */
public final class ColorLightIndicatorRenderer implements BlockEntityRenderer<ColorLightIndicatorBlockEntity> {

	private static final float Z = 0.42F; // 灯块北面 z=6.8，/16 再向内一点（与 LED 同侧显示）
	private static final org.mtr.mapping.holder.Identifier WHITE_TEXTURE = new org.mtr.mapping.holder.Identifier(MTRBR.MOD_ID, "textures/block/white.png");

	private static final float[][] LIGHTS = {
			{0.375F, 0.125F, 0.4375F, 0.1875F},
			{0.484375F, 0.234375F, 0.546875F, 0.296875F},
			{0.59375F, 0.34375F, 0.65625F, 0.40625F},
			{0.703125F, 0.453125F, 0.765625F, 0.515625F},
			{0.8125F, 0.5625F, 0.875F, 0.625F},
	};


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
	public ColorLightIndicatorRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(ColorLightIndicatorBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		final Level level = blockEntity.getLevel();
		if (level == null) {
			return;
		}
		final BlockPos pos = blockEntity.getBlockPos();
		final BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ColorLightIndicatorBlock)) {
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
			return;
		}
		final ServerAspectCache.DisplayState serverState = ServerAspectCache.getState(boundSignalPos, false);
		final List<RouteBinding> bindings;
		if (serverState != null && !serverState.authorizationId().isEmpty()) {
			final String authorizedContent = serverState.routeContent();
			bindings = ClientBindings.get(boundSignalPos).stream()
					.filter(binding -> binding.content().equalsIgnoreCase(authorizedContent))
					.toList();
		} else {
			bindings = List.of();
		}
		if (bindings.isEmpty()) {
			debugLog(level, pos + " -> " + boundSignalPos + " aspect=" + aspect + " NO-AUTHORIZED-ROUTE");
			return;
		}
		debugLog(level, pos + " -> " + boundSignalPos + " aspect=" + aspect + " open=" + bindings);

		for (final float[] light : LIGHTS) {
			drawLight(pos, angle, light[0], light[1], light[2], light[3]);
		}
	}

	private static void drawLight(BlockPos pos, float angle, float x1, float y1, float x2, float y2) {
		MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, cameraOffset) -> {
			graphicsHolder.push();
			graphicsHolder.translate(pos.getX() + 0.5 - cameraOffset.getXMapped(), pos.getY() + 0.5 - cameraOffset.getYMapped(), pos.getZ() + 0.5 - cameraOffset.getZMapped());
			graphicsHolder.rotateYDegrees(-(angle + 180));
			graphicsHolder.translate(-0.5, -0.5, -0.5);
			IDrawing.drawTexture(graphicsHolder,
					x1, y2, Z,
					x2, y2, Z,
					x2, y1, Z,
					x1, y1, Z,
					0, 1, 1, 0, org.mtr.mapping.holder.Direction.UP, 0xFFFFFFFF, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();
		});
	}
}
