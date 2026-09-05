package org.mtrbr.api;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 父子信号机绑定预留接口（尚未实现）。
 *
 * 计划功能：手动把一个信号机与若干“子信号机”绑定，闭塞链按绑定的父子关系传递 aspect，
 * 而不是仅靠列车进路自动识别。当前版本（0.1.2）仍使用自动识别逻辑，本接口只留扩展点。
 */
public interface ParentChildSignalBinding {

	/** 手动绑定一个子信号机（某信号机的下一个信号）。 */
	void bindChild(BlockPos parentSignalPos, BlockPos childSignalPos);

	/** 解除某个子信号机绑定。 */
	void unbindChild(BlockPos parentSignalPos, BlockPos childSignalPos);

	/** 查询某信号机已手动绑定的子信号机列表；未配置时返回空列表。 */
	List<BlockPos> getChildren(BlockPos parentSignalPos);

	/** 查询某信号机手动绑定的父信号机；未配置时返回 null。 */
	@Nullable
	BlockPos getParent(BlockPos childSignalPos);
}
