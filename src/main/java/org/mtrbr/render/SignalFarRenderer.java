package org.mtrbr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;

/**
 * 远距离信号灯渲染：MTR 信号灯是方块实体，受普通方块实体渲染距离/视距限制，
 * 远处不显示；车辆则走 MTR 全量渲染。这里在关卡渲染阶段手动调用远处已加载
 * 区块中信号灯的标准方块实体渲染器，让信号灯与车辆一样在远处可见。
 */
public final class SignalFarRenderer {

	/** 超过该距离才由本渲染器接管（近处仍由原版方块实体渲染，避免重影）。 */
	private static final double NEAR_RADIUS_SQUARED = 48 * 48;

	private SignalFarRenderer() {
	}

	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
			return;
		}
		final Minecraft minecraft = Minecraft.getInstance();
		final Level level = minecraft.level;
		final Player player = minecraft.player;
		if (level == null || player == null) {
			return;
		}
		final BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
		final PoseStack poseStack = event.getPoseStack();
		final MultiBufferSource bufferSource = minecraft.renderBuffers().bufferSource();
		final float partialTick = event.getPartialTick();
		final BlockPos center = player.blockPosition();
		final int centerChunkX = center.getX() >> 4;
		final int centerChunkZ = center.getZ() >> 4;
		final int chunkRadius = 20; // 320 格；实际受已加载区块范围限制

		for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
			for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
				final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					// Route indicators are dynamic light-only block entities, so they need
					// the same far-render handoff as the MTR signal they are bound to.
					if (!(blockEntity instanceof BlockSignalBase.BlockEntityBase)
							&& !(blockEntity instanceof ColorLightIndicatorBlockEntity)) {
						continue;
					}
					if (blockEntity.getBlockPos().distSqr(center) <= NEAR_RADIUS_SQUARED) {
						continue;
					}
					final BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);
					if (renderer == null) {
						continue;
					}
					renderer.render(blockEntity, partialTick, poseStack, bufferSource, 0xF000F0, OverlayTexture.NO_OVERLAY);
				}
			}
		}
	}
}
