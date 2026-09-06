package org.mtrbr.data;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 进路绑定内容校验。
 * 允许格式：
 * route=X，X 为 1–6 之一；
 * path=Y，Y 为 0-20 的数字、1-4 个大写字母、指定双字母组合，或指定小写箭头组合。
 * shunt=名称；不同类型可用 || 组合，每种类型最多一项。
 */
public final class RouteContent {
	public static final int MAX_LENGTH = 64;
	private static final Pattern SHUNT_PATTERN = Pattern.compile("^shunt=[a-z0-9_-]{1,32}$", Pattern.CASE_INSENSITIVE);

	private static final Pattern ROUTE_PATTERN = Pattern.compile("^route=([1-6])$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PATH_NUMBER_PATTERN = Pattern.compile("^path=(\\d{1,2})$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PATH_LETTER_PATTERN = Pattern.compile("^path=([A-Z]{1,4})$");
	private static final Set<String> SPECIAL_COMBOS = Set.of("UF", "US", "DF", "DS", "DN", "DR", "UP", "UR");
	private static final Set<String> ARROW_COMBOS = Set.of("adl", "adr", "arl", "arr", "atl", "atm", "atr");

	private RouteContent() {
	}

	public static boolean isShunt(String content) {
		return !part(content, "shunt").isEmpty();
	}

	/** A node may carry one value of each type, joined with || in stable order. */
	public static String validate(String rawInput) {
		if (rawInput == null) return null;
		final java.util.Map<String, String> parts = new java.util.HashMap<>();
		for (final String rawPart : rawInput.split("\\|\\|", -1)) {
			final String value = validateSingle(rawPart);
			if (value == null) return null;
			final String type = value.substring(0, value.indexOf('='));
			if (parts.putIfAbsent(type, value) != null) return null;
		}
		final String result = java.util.List.of("route", "path", "shunt").stream()
				.filter(parts::containsKey).map(parts::get).collect(java.util.stream.Collectors.joining(" || "));
		return result.length() <= MAX_LENGTH ? result : null;
	}

	/** Returns the validated prefix=value component, or empty for missing/invalid content. */
	public static String part(String content, String type) {
		final String normalized = validate(content);
		if (normalized == null) return "";
		for (final String part : normalized.split(" \\|\\| ")) {
			if (part.startsWith(type + "=")) return part;
		}
		return "";
	}

	/** 校验并规范化输入；非法时返回 null。 */
	private static String validateSingle(String rawInput) {
		if (rawInput == null) {
			return null;
		}
		final String input = rawInput.trim();
		if (input.isEmpty()) {
			return null;
		}
		final String lower = input.toLowerCase(Locale.ROOT);
		if (SHUNT_PATTERN.matcher(lower).matches()) return lower;
		if (ROUTE_PATTERN.matcher(lower).matches()) {
			return "route=" + lower.substring("route=".length());
		}
		if (!lower.startsWith("path=")) {
			return null;
		}
		final String value = input.substring("path=".length());
		if (value.isEmpty()) {
			return null;
		}
		final java.util.regex.Matcher numberMatcher = PATH_NUMBER_PATTERN.matcher(lower);
		if (numberMatcher.matches()) {
			final int number = Integer.parseInt(numberMatcher.group(1));
			return number >= 0 && number <= 20 ? "path=" + number : null;
		}
		if (ARROW_COMBOS.contains(value.toLowerCase(Locale.ROOT))) {
			return "path=" + value.toLowerCase(Locale.ROOT);
		}
		final String upper = value.toUpperCase(Locale.ROOT);
		if (SPECIAL_COMBOS.contains(upper)) {
			return "path=" + upper;
		}
		return PATH_LETTER_PATTERN.matcher(input).matches() ? "path=" + upper : null;
	}
}
