package org.mtrbr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
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
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.logic.SignalLogic;

/**
 * 色灯式进路指示器渲染（与 LED 相同思路）：
 * 方块本体只提供固定外壳；路线图片通过全亮渲染层叠加，避免环境光照影响发光。
 */
public final class ColorLightIndicatorRenderer implements BlockEntityRenderer<ColorLightIndicatorBlockEntity> {

	private static final org.mtr.mapping.holder.Identifier WHITE_TEXTURE = new org.mtr.mapping.holder.Identifier(MTRBR.MOD_ID, "textures/block/white.png");
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
		if (serverState == null || serverState.authorizationId().isEmpty() || serverState.routeContent().isBlank()) {
			debugLog(level, pos + " -> " + boundSignalPos + " aspect=" + aspect + " NO-AUTHORIZED-ROUTE");
			return;
		}
		final String indicatorModel = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		final java.util.List<ColorLightModel.Light> lights = ColorLightModel.getRouteLights(indicatorModel, serverState.routeContent());
		if (lights.isEmpty()) {
			debugLog(level, pos + " -> " + boundSignalPos + " route=" + serverState.routeContent() + " NO-ROUTE-LIGHTS");
			return;
		}
		debugLog(level, pos + " -> " + boundSignalPos + " aspect=" + aspect + " route=" + serverState.routeContent() + " lights=" + lights.size());
		for (final ColorLightModel.Light light : lights) {
			drawLight(pos, angle, light);
		}
	}

	private static void drawLight(BlockPos pos, float angle, ColorLightModel.Light light) {
		MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.LIGHT, (graphicsHolder, cameraOffset) -> {
			graphicsHolder.push();
			graphicsHolder.translate(pos.getX() + 0.5 - cameraOffset.getXMapped(), pos.getY() + 0.5 - cameraOffset.getYMapped(), pos.getZ() + 0.5 - cameraOffset.getZMapped());
			graphicsHolder.rotateYDegrees(-(angle + 180));
			graphicsHolder.translate(-0.5, -0.5, -0.5);
			// The route model supplies full element bounds. Keep the authored 3-D
			// geometry and render every face through the self-lit LIGHT layer.
			drawCuboid(graphicsHolder, light);
			graphicsHolder.pop();
		});
	}

	private static void drawCuboid(GraphicsHolder graphicsHolder, ColorLightModel.Light light) {
		final float x1 = light.x1();
		final float y1 = light.y1();
		final float z1 = light.z1() - 0.0005F;
		final float x2 = light.x2();
		final float y2 = light.y2();
		final float z2 = light.z2() + 0.0005F;
		final int lightLevel = GraphicsHolder.getDefaultLight();
		drawFace(graphicsHolder, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1, org.mtr.mapping.holder.Direction.NORTH, lightLevel);
		drawFace(graphicsHolder, x2, y2, z2, x1, y2, z2, x1, y1, z2, x2, y1, z2, org.mtr.mapping.holder.Direction.SOUTH, lightLevel);
		drawFace(graphicsHolder, x1, y2, z2, x1, y2, z1, x1, y1, z1, x1, y1, z2, org.mtr.mapping.holder.Direction.WEST, lightLevel);
		drawFace(graphicsHolder, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1, org.mtr.mapping.holder.Direction.EAST, lightLevel);
		drawFace(graphicsHolder, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, org.mtr.mapping.holder.Direction.UP, lightLevel);
		drawFace(graphicsHolder, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, org.mtr.mapping.holder.Direction.DOWN, lightLevel);
	}

	private static void drawFace(GraphicsHolder graphicsHolder, float x1, float y1, float z1, float x2, float y2, float z2,
			float x3, float y3, float z3, float x4, float y4, float z4, org.mtr.mapping.holder.Direction direction, int light) {
		IDrawing.drawTexture(graphicsHolder, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4,
				0, 1, 1, 0, direction, 0xFFFFFFFF, light);
	}
}
