package org.mtrbr.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.mtr.core.data.Position;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.model.ModelSmallCube;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtrbr.MTRBR;
import org.mtrbr.client.SignalCache;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.logic.SignalLogic;

/**
 * 世界可视化渲染：
 * - 手持调试/进路工具时显示轨道节点标记；
 * - 调试工具：红色 60% 透明粗线连接信号机与作用节点；
 * - 进路工具：绿色 60% 透明粗线 + 沿直线偏移 1 格的绑定内容标签；
 */
public final class SignalOverlayRenderer {

	private static final int DEBUG_LINE_COLOR = 0x99FF0000;
	private static final int ROUTE_LINE_COLOR = 0x9900FF00;
	private static final int LABEL_BOX_COLOR = 0x99000000;
	private static final int NODE_RENDER_RADIUS = 48;

	private static final org.mtr.mapping.holder.Identifier WHITE_TEXTURE = new org.mtr.mapping.holder.Identifier(MTRBR.MOD_ID, "textures/block/white.png");
	private static final ModelSmallCube MODEL_SMALL_CUBE = new ModelSmallCube(new org.mtr.mapping.holder.Identifier(MTRBR.MOD_ID, "textures/block/white.png"));

	private SignalOverlayRenderer() {
	}

	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
			return;
		}
		final Minecraft minecraft = Minecraft.getInstance();
		final Player player = minecraft.player;
		final Level level = minecraft.level;
		if (player == null || level == null) {
			return;
		}

		final boolean debugHeld = isHolding(minecraft, MTRBR.DEBUG_TOOL.get());
		final boolean routeHeld = isHolding(minecraft, MTRBR.ROUTE_TOOL.get());

		SignalCache.tick(level, player.blockPosition());
		if (debugHeld || routeHeld) {
			renderNodes(level, player.blockPosition());
		}

		for (final SignalCache.Entry entry : SignalCache.getEntries(level, player.blockPosition())) {
			if (debugHeld && entry.nodePos() != null) {
				drawThickLine(entry.signalPos(), entry.nodePos(), DEBUG_LINE_COLOR);
			}
			if (routeHeld) {
				for (final RouteBinding binding : ClientBindings.get(entry.signalPos())) {
					drawThickLine(entry.signalPos(), binding.node(), ROUTE_LINE_COLOR);
					drawLabel(entry.signalPos(), binding.node(), binding.content());
				}
			}
		}
	}

	private static boolean isHolding(Minecraft minecraft, Item item) {
		final Player player = minecraft.player;
		return player != null && (player.getMainHandItem().is(item) || player.getOffhandItem().is(item));
	}

	/** 轨道节点标记：类似 MTR 手持轨道连接器时显示已连接节点的方块。 */
	private static void renderNodes(Level level, BlockPos center) {
		final MinecraftClientData clientData = MinecraftClientData.getInstance();
		for (final Position position : clientData.positionsToRail.keySet()) {
			final double dx = position.getX() - center.getX();
			final double dy = position.getY() - center.getY();
			final double dz = position.getZ() - center.getZ();
			if (dx * dx + dy * dy + dz * dz > (double) NODE_RENDER_RADIUS * NODE_RENDER_RADIUS) {
				continue;
			}
			final BlockPos blockPos = new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
			final BlockState state = level.getBlockState(blockPos);
			if (state.getBlock() instanceof BlockNode) {
				final float angle = (state.getValue(BlockNode.FACING.data) ? -90 : 0)
						+ (state.getValue(BlockNode.IS_45.data) ? -45 : 0)
						+ (state.getValue(BlockNode.IS_22_5.data) ? -22.5F : 0);
				final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
				storedMatrixTransformations.add(graphicsHolder -> {
					graphicsHolder.rotateYDegrees(angle);
					graphicsHolder.scale(4, 0.5F, 0.5F);
					graphicsHolder.translate(-0.5, 0, -0.5);
				});
				MODEL_SMALL_CUBE.render(storedMatrixTransformations, GraphicsHolder.getDefaultLight());
			}
		}
	}

	/** 60% 透明粗线：用多条平行线段近似（细于最初的版本）。 */
	private static void drawThickLine(BlockPos from, BlockPos to, int color) {
		final double x1 = from.getX() + 0.5;
		final double y1 = from.getY() + 0.62;
		final double z1 = from.getZ() + 0.5;
		final double x2 = to.getX() + 0.5;
		final double y2 = to.getY() + 0.5;
		final double z2 = to.getZ() + 0.5;
		double dx = z2 - z1;
		double dz = x1 - x2;
		final double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0E-4) {
			return;
		}
		dx /= length;
		dz /= length;
		final double offset = 0.05;
		final double dxFinal = dx;
		final double dzFinal = dz;

		MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, cameraOffset) -> {
			drawLine(graphicsHolder, cameraOffset, x1, y1, z1, x2, y2, z2, color);
			drawLine(graphicsHolder, cameraOffset, x1 + dxFinal * offset, y1, z1 + dzFinal * offset, x2 + dxFinal * offset, y2, z2 + dzFinal * offset, color);
			drawLine(graphicsHolder, cameraOffset, x1 - dxFinal * offset, y1, z1 - dzFinal * offset, x2 - dxFinal * offset, y2, z2 - dzFinal * offset, color);
		});
	}

	private static void drawLine(GraphicsHolder graphicsHolder, org.mtr.mapping.holder.Vector3d cameraOffset, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		graphicsHolder.drawLineInWorld(
				(float) (x1 - cameraOffset.getXMapped()), (float) (y1 - cameraOffset.getYMapped()), (float) (z1 - cameraOffset.getZMapped()),
				(float) (x2 - cameraOffset.getXMapped()), (float) (y2 - cameraOffset.getYMapped()), (float) (z2 - cameraOffset.getZMapped()),
				color
		);
	}

	/** 绑定内容标签：显示在信号机 -> 节点直线上、距信号机约 1 格的位置。 */
	private static void drawLabel(BlockPos signalPos, BlockPos nodePos, String content) {
		final double sx = signalPos.getX() + 0.5;
		final double sy = signalPos.getY() + 0.6;
		final double sz = signalPos.getZ() + 0.5;
		final double dx = nodePos.getX() + 0.5 - sx;
		final double dz = nodePos.getZ() + 0.5 - sz;
		final double length = Math.sqrt(dx * dx + dz * dz);
		double ax = sx;
		double az = sz;
		if (length > 1.0E-4) {
			final double distance = Math.min(length, 1.0);
			ax = sx + dx / length * distance;
			az = sz + dz / length * distance;
		}
		final double anchorX = ax;
		final double anchorZ = az;
		final int textWidth = GraphicsHolder.getTextWidth(content);
		final float halfWidth = (textWidth / 2F + 4) / 32F;
		final float halfHeight = 5 / 32F;

		MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolder, cameraOffset) -> {
			graphicsHolder.push();
			graphicsHolder.translate(anchorX - cameraOffset.getXMapped(), sy - cameraOffset.getYMapped(), anchorZ - cameraOffset.getZMapped());
			InitClient.transformToFacePlayer(graphicsHolder, anchorX, sy, anchorZ);
			graphicsHolder.rotateZDegrees(180);
			IDrawing.drawTexture(graphicsHolder, -halfWidth, -halfHeight, -0.02F, halfWidth, halfHeight, -0.02F, 0, 0, 1, 1, Direction.UP, LABEL_BOX_COLOR, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();
		});

		MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, cameraOffset) -> {
			graphicsHolder.push();
			graphicsHolder.translate(anchorX - cameraOffset.getXMapped(), sy - cameraOffset.getYMapped(), anchorZ - cameraOffset.getZMapped());
			InitClient.transformToFacePlayer(graphicsHolder, anchorX, sy, anchorZ);
			graphicsHolder.rotateZDegrees(180);
			graphicsHolder.scale(1 / 32F, 1 / 32F, -1 / 32F);
			graphicsHolder.drawText(content, -textWidth / 2, -4, 0xFFFFFFFF, true, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();
		});
	}

}
