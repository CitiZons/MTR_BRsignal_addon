package org.mtrbr.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.mtr.mapping.holder.Direction;
import org.mtrbr.MTRBR;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时解析 indicator_1_NULL.bbmodel（Blockbench java_block 格式：逐面 UV + 多贴图）。
 * 直接读 .bbmodel 资源并缓存为世界坐标四边形；改 bbmodel 重进游戏即可生效，无需导出。
 */
public final class ColorLightModel {

	private static final String[] FACE_NAMES = {"north", "south", "east", "west", "up", "down"};
	private static final float UV_SPACE = 32.0F;

	private static List<Face> faces;
	private static boolean loaded;
	private static final Map<String, List<Light>> ROUTE_LIGHTS = new HashMap<>();
	private static boolean routeLightCacheLoaded;
	private static final String ROUTE_LIGHT_CACHE_FILE = "route-light-cache.json";

	private ColorLightModel() {
	}

	public static List<Face> getFaces() {
		ensureLoaded();
		return faces;
	}

	public static ResourceLocation getTexture(int textureId) {
		final String name = switch (textureId) {
			case 0 -> "indicator_1";
			case 1 -> "grey";
			case 2 -> "indicator_1_route_1";
			case 4 -> "indicator_1_back";
			default -> "black";
		};
		return ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "textures/block/" + name + ".png");
	}

	/** Reads the light rectangles from the exported route model; the null model remains the block shell. */
	public static List<Light> getRouteLights(String indicatorModel, String routeContent) {
		if (routeContent == null || !routeContent.startsWith("route=")) return List.of();
		final String route = routeContent.substring("route=".length()).trim();
		if (route.isEmpty()) return List.of();
		final String key = indicatorModel + "|" + route;
		loadRouteLightCache();
		final List<Light> cached = ROUTE_LIGHTS.get(key);
		if (cached != null) return cached;
		final List<Light> parsed = loadRouteLights(indicatorModel, route);
		ROUTE_LIGHTS.put(key, parsed);
		writeRouteLightCache();
		return parsed;
	}

	/**
	 * Route geometry is an authoring result, not per-session state. Once a model
	 * has been parsed, retain its rectangles in the local Minecraft config so a
	 * later game launch does not need to reopen the model JSON. A deliberately
	 * explicit cache clear is required after changing a route model.
	 */
	private static void loadRouteLightCache() {
		if (routeLightCacheLoaded) return;
		routeLightCacheLoaded = true;
		final Path cacheFile = routeLightCacheFile();
		if (!Files.isRegularFile(cacheFile)) return;
		try {
			final JsonObject entries = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
			for (final Map.Entry<String, JsonElement> entry : entries.entrySet()) {
				if (!entry.getValue().isJsonArray()) continue;
				final List<Light> lights = new ArrayList<>();
				for (final JsonElement value : entry.getValue().getAsJsonArray()) {
					if (!value.isJsonArray() || value.getAsJsonArray().size() != 6) continue;
					final JsonArray bounds = value.getAsJsonArray();
					lights.add(new Light(bounds.get(0).getAsFloat(), bounds.get(1).getAsFloat(), bounds.get(2).getAsFloat(),
							bounds.get(3).getAsFloat(), bounds.get(4).getAsFloat(), bounds.get(5).getAsFloat()));
				}
				ROUTE_LIGHTS.put(entry.getKey(), List.copyOf(lights));
			}
		} catch (Exception exception) {
			System.err.println("[MTRBR-RENDER] route-light-cache load-failed=" + exception.getClass().getSimpleName());
		}
	}

	private static void writeRouteLightCache() {
		final Path cacheFile = routeLightCacheFile();
		try {
			Files.createDirectories(cacheFile.getParent());
			final JsonObject entries = new JsonObject();
			for (final Map.Entry<String, List<Light>> entry : ROUTE_LIGHTS.entrySet()) {
				final JsonArray lights = new JsonArray();
				for (final Light light : entry.getValue()) {
					final JsonArray bounds = new JsonArray();
					bounds.add(light.x1()); bounds.add(light.y1()); bounds.add(light.z1());
					bounds.add(light.x2()); bounds.add(light.y2()); bounds.add(light.z2());
					lights.add(bounds);
				}
				entries.add(entry.getKey(), lights);
			}
			final Path temporary = cacheFile.resolveSibling(ROUTE_LIGHT_CACHE_FILE + ".tmp");
			Files.writeString(temporary, entries.toString(), StandardCharsets.UTF_8);
			try {
				Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception exception) {
			System.err.println("[MTRBR-RENDER] route-light-cache write-failed=" + exception.getClass().getSimpleName());
		}
	}

	private static Path routeLightCacheFile() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(MTRBR.MOD_ID).resolve(ROUTE_LIGHT_CACHE_FILE);
	}

	private static List<Light> loadRouteLights(String indicatorModel, String route) {
		final String modelName = indicatorModel + "_" + route;
		final ResourceLocation resource = ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "models/block/" + modelName + ".json");
		final List<Light> lights = new ArrayList<>();
		try (InputStream inputStream = Minecraft.getInstance().getResourceManager().open(resource)) {
			final String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			final JsonArray elements = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("elements");
			for (final JsonElement elementJson : elements) {
				final JsonObject element = elementJson.getAsJsonObject();
				final String name = element.has("name") ? element.get("name").getAsString() : "";
				if (!name.startsWith(indicatorModel + "_route_" + route + "_")) continue;
				final JsonArray from = element.getAsJsonArray("from");
				final JsonArray to = element.getAsJsonArray("to");
				lights.add(new Light(
						from.get(0).getAsFloat() / 16, from.get(1).getAsFloat() / 16, from.get(2).getAsFloat() / 16,
						to.get(0).getAsFloat() / 16, to.get(1).getAsFloat() / 16, to.get(2).getAsFloat() / 16
				));
			}
		} catch (Exception exception) {
			System.err.println("[MTRBR-RENDER] route-model=" + resource + " load-failed=" + exception.getClass().getSimpleName());
		}
		return List.copyOf(lights);
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		faces = new ArrayList<>();
		try (InputStream inputStream = Minecraft.getInstance().getResourceManager().open(ResourceLocation.fromNamespaceAndPath(MTRBR.MOD_ID, "models/block/indicator_1_NULL.bbmodel"))) {
			final String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			final JsonArray elements = root.getAsJsonArray("elements");
			for (final JsonElement elementJson : elements) {
				final JsonObject element = elementJson.getAsJsonObject();
				if (element.has("export") && !element.get("export").getAsBoolean()) {
					continue;
				}
				final float x1 = element.getAsJsonArray("from").get(0).getAsFloat();
				final float y1 = element.getAsJsonArray("from").get(1).getAsFloat();
				final float z1 = element.getAsJsonArray("from").get(2).getAsFloat();
				final float x2 = element.getAsJsonArray("to").get(0).getAsFloat();
				final float y2 = element.getAsJsonArray("to").get(1).getAsFloat();
				final float z2 = element.getAsJsonArray("to").get(2).getAsFloat();
				final float rotX = get(element, "rotation", 0);
				final float rotY = get(element, "rotation", 1);
				final float rotZ = get(element, "rotation", 2);
				final float originX = get(element, "origin", 0);
				final float originY = get(element, "origin", 1);
				final float originZ = get(element, "origin", 2);

				final JsonObject facesObject = element.getAsJsonObject("faces");
				boolean hasBoardTexture = false;
				for (final String faceName : FACE_NAMES) {
					if (facesObject.has(faceName)) {
						final JsonObject face = facesObject.getAsJsonObject(faceName);
						if (face.has("texture")) {
							final int tid = face.get("texture").getAsInt();
							if (tid == 0 || tid == 4) {
								hasBoardTexture = true;
							}
						}
					}
				}

				for (final String faceName : FACE_NAMES) {
					if (!facesObject.has(faceName)) {
						continue;
					}
					final JsonObject face = facesObject.getAsJsonObject(faceName);
					final boolean hasTexture = face.has("texture");
					final int textureId;
					if (!hasTexture) {
						if (hasBoardTexture) {
							continue; // 面板 4 侧边：透明
						}
						textureId = 3; // 其余无贴图面：black 补
					} else {
						textureId = face.get("texture").getAsInt();
					}
					final float u1 = face.getAsJsonArray("uv").get(0).getAsFloat() / UV_SPACE;
					final float v1 = face.getAsJsonArray("uv").get(1).getAsFloat() / UV_SPACE;
					final float u2 = face.getAsJsonArray("uv").get(2).getAsFloat() / UV_SPACE;
					final float v2 = face.getAsJsonArray("uv").get(3).getAsFloat() / UV_SPACE;

					final float[][] corners = corners(x1, y1, z1, x2, y2, z2, faceName);
					final float[] uv0 = {u1, v1};
					final float[] uv1 = {u2, v1};
					final float[] uv2 = {u2, v2};
					final float[] uv3 = {u1, v2};
					float[][] uvs = {uv0, uv1, uv2, uv3};
					if (faceName.equals("north")) {
						uvs = new float[][]{{u2, v1}, {u1, v1}, {u1, v2}, {u2, v2}};
					}
					for (int i = 0; i < 4; i++) {
						corners[i] = rotate(corners[i], rotX, rotY, rotZ, originX, originY, originZ);
					}
					faces.add(new Face(
							corners[0][0] / 16, corners[0][1] / 16, corners[0][2] / 16,
							corners[1][0] / 16, corners[1][1] / 16, corners[1][2] / 16,
							corners[2][0] / 16, corners[2][1] / 16, corners[2][2] / 16,
							corners[3][0] / 16, corners[3][1] / 16, corners[3][2] / 16,
							uvs[0][0], uvs[0][1], uvs[2][0], uvs[2][1], textureId, directionOf(faceName)));
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static float get(JsonObject object, String key, int index) {
		final JsonElement element = object.get(key);
		if (element != null && element.isJsonArray() && element.getAsJsonArray().size() > index) {
			return element.getAsJsonArray().get(index).getAsFloat();
		}
		return 0;
	}

	/** 与 OBJ 转换器一致的六面角点顺序（Blockbench 惯例，north 面水平镜像已由 UV 处理）。 */
	private static float[][] corners(float x1, float y1, float z1, float x2, float y2, float z2, String faceName) {
		return switch (faceName) {
			case "north" -> new float[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y1, z1}, {x1, y1, z1}};
			case "south" -> new float[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
			case "east" -> new float[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
			case "west" -> new float[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
			case "up" -> new float[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}, {x1, y2, z1}};
			default -> new float[][]{{x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}, {x1, y1, z2}};
		};
	}

	private static Direction directionOf(String faceName) {
		return switch (faceName) {
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			case "up" -> Direction.UP;
			default -> Direction.DOWN;
		};
	}

	/** 元素旋转（当前模型全为 0，保留支持）。 */
	private static float[] rotate(float[] p, float rx, float ry, float rz, float ox, float oy, float oz) {
		if (rx == 0 && ry == 0 && rz == 0) {
			return p;
		}
		float x = p[0] - ox;
		float y = p[1] - oy;
		float z = p[2] - oz;
		if (rx != 0) {
			final float c = (float) Math.cos(Math.toRadians(rx));
			final float s = (float) Math.sin(Math.toRadians(rx));
			final float ny = y * c - z * s;
			final float nz = y * s + z * c;
			y = ny;
			z = nz;
		}
		if (ry != 0) {
			final float c = (float) Math.cos(Math.toRadians(ry));
			final float s = (float) Math.sin(Math.toRadians(ry));
			final float nx = x * c + z * s;
			final float nz = -x * s + z * c;
			x = nx;
			z = nz;
		}
		if (rz != 0) {
			final float c = (float) Math.cos(Math.toRadians(rz));
			final float s = (float) Math.sin(Math.toRadians(rz));
			final float nx = x * c - y * s;
			final float ny = x * s + y * c;
			x = nx;
			y = ny;
		}
		return new float[]{x + ox, y + oy, z + oz};
	}

	public record Face(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float u1, float v1, float u2, float v2, int texture, Direction direction) {
	}

	/** Exact bounds of one route element in the exported model. */
	public record Light(float x1, float y1, float z1, float x2, float y2, float z2) {
	}
}
