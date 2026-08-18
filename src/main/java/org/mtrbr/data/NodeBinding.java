package org.mtrbr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/** 信号机 -> 轨道节点的手动绑定；node 为 null 表示使用自动找节点但方向反向。 */
public record NodeBinding(BlockPos node, boolean reversed) {

	public CompoundTag toTag() {
		final CompoundTag tag = new CompoundTag();
		tag.putBoolean("has_node", node != null);
		if (node != null) {
			tag.putInt("nx", node.getX());
			tag.putInt("ny", node.getY());
			tag.putInt("nz", node.getZ());
		}
		tag.putBoolean("reversed", reversed);
		return tag;
	}

	public static NodeBinding fromTag(CompoundTag tag) {
		final BlockPos node = tag.getBoolean("has_node") ? new BlockPos(tag.getInt("nx"), tag.getInt("ny"), tag.getInt("nz")) : null;
		return new NodeBinding(node, tag.getBoolean("reversed"));
	}
}
