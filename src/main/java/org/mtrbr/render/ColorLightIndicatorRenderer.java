package org.mtrbr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtrbr.block.ColorLightIndicatorBlock;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.logic.SignalLogic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 色灯式进路指示器渲染：直接从 indicator_1_NULL.bbmodel 运行时解析并绘制全部面。
 * 按贴图分组渲染（多贴图）；route 图层仅当绑定信号机进路开放时绘制（Java 控制 NULL/lit）。
 */
public final class ColorLightIndicatorRenderer implements BlockEntityRenderer<ColorLightIndicatorBlockEntity> {

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

		final List<ColorLightModel.Face> allFaces = ColorLightModel.getFaces();
		if (allFaces.isEmpty()) {
			return;
		}

		BlockPos boundSignalPos = ClientIndicatorBindings.get(pos);
		if (boundSignalPos == null) {
			boundSignalPos = blockEntity.getBoundSignalPos();
		}
		final boolean hasRouteBinding = boundSignalPos != null && ClientBindings.get(boundSignalPos).stream()
				.anyMatch(binding -> binding.content().toLowerCase(java.util.Locale.ROOT).startsWith("route=") && !binding.content().equalsIgnoreCase("route=NULL"));
		boolean signalRed = false;
		if (boundSignalPos != null) {
			final BlockEntity signalEntity = level.getBlockEntity(boundSignalPos);
			signalRed = signalEntity instanceof BlockSignalBase.BlockEntityBase && SignalLogic.getSignalAspect(level, boundSignalPos, signalEntity, false) == 1;
		}

		final Map<Integer, List<ColorLightModel.Face>> byTexture = new LinkedHashMap<>();
		for (final ColorLightModel.Face face : allFaces) {
			byTexture.computeIfAbsent(face.texture(), key -> new ArrayList<>()).add(face);
		}

		for (final Map.Entry<Integer, List<ColorLightModel.Face>> entry : byTexture.entrySet()) {
			final int textureId = entry.getKey();
			if (textureId == 2 && (!hasRouteBinding || signalRed)) {
				continue; // route 图层：无 route 绑定或信号红灯时不渲染
			}
			final List<ColorLightModel.Face> faces = entry.getValue();
			final ResourceLocation texture = ColorLightModel.getTexture(textureId);
			MainRenderer.scheduleRender(new org.mtr.mapping.holder.Identifier(texture.getNamespace(), texture.getPath()), false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, cameraOffset) -> {
				graphicsHolder.push();
				graphicsHolder.translate(pos.getX() + 0.5 - cameraOffset.getXMapped(), pos.getY() + 0.5 - cameraOffset.getYMapped(), pos.getZ() + 0.5 - cameraOffset.getZMapped());
				graphicsHolder.rotateYDegrees(-angle);
				graphicsHolder.translate(-0.5, -0.5, -0.5);
				for (final ColorLightModel.Face face : faces) {
					IDrawing.drawTexture(graphicsHolder,
							face.x1(), face.y1(), face.z1(),
							face.x2(), face.y2(), face.z2(),
							face.x3(), face.y3(), face.z3(),
							face.x4(), face.y4(), face.z4(),
							face.u1(), face.v1(), face.u2(), face.v2(),
							face.direction(), 0xFFFFFFFF, GraphicsHolder.getDefaultLight());
				}
				graphicsHolder.pop();
			});
		}
	}
}
