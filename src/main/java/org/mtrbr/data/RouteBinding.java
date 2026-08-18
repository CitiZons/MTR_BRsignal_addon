package org.mtrbr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public record RouteBinding(BlockPos node, String content) {

	public CompoundTag toTag() {
		final CompoundTag tag = new CompoundTag();
		tag.putLong("x", node.getX());
		tag.putLong("y", node.getY());
		tag.putLong("z", node.getZ());
		tag.putString("content", content);
		return tag;
	}

	public static RouteBinding fromTag(CompoundTag tag) {
		return new RouteBinding(new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), tag.getString("content"));
	}
}
