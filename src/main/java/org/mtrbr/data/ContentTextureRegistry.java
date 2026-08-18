package org.mtrbr.data;

import net.minecraft.resources.ResourceLocation;
import org.mtrbr.MTRBR;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 进路内容 -> LED 显示图层贴图的映射约定。
 * 例如 route=1 -> textures/block/led_layer_route_1.png；path=UF -> textures/block/led_layer_path_uf.png。
 */
public final class ContentTextureRegistry {

	private ContentTextureRegistry() {
	}

	@Nullable
	public static ResourceLocation getTexture(String content) {
		if (content == null) {
			return null;
		}
		final String lower = content.trim().toLowerCase(Locale.ROOT);
		if (!lower.startsWith("path=")) {
			return null;
		}
		final String value = content.trim().substring("path=".length());
		if (value.isEmpty()) {
			return null;
		}
		return ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "textures/block/path/path_" + value + ".png");
	}

	/** 色灯式进路指示器的图层贴图：route=X -> indicator_1_route_X.png。 */
	@Nullable
	public static ResourceLocation getColorLightTexture(String content) {
		if (content == null) {
			return null;
		}
		final String lower = content.trim().toLowerCase(Locale.ROOT);
		if (!lower.startsWith("route=")) {
			return null;
		}
		final String value = lower.substring("route=".length());
		if (value.isEmpty() || !value.chars().allMatch(Character::isLetterOrDigit)) {
			return null;
		}
		return ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "textures/block/indicator_1_route_" + value + ".png");
	}
}
