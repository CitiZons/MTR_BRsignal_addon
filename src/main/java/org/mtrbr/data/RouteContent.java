package org.mtrbr.data;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 进路绑定内容校验。
 * 允许格式：
 * route=X，X 为 1、2、4、5 之一；
 * path=Y，Y 为 0-20 的数字、1-4 个大写字母，或 UF/US/DF/DS 组合（UP/DW 已弃用）。
 */
public final class RouteContent {

	private static final Pattern ROUTE_PATTERN = Pattern.compile("^route=([1245])$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PATH_NUMBER_PATTERN = Pattern.compile("^path=(\\d{1,2})$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PATH_LETTER_PATTERN = Pattern.compile("^path=([A-Z]{1,4})$");
	private static final Set<String> SPECIAL_COMBOS = Set.of("UF", "US", "DF", "DS");

	private RouteContent() {
	}

	/** 校验并规范化输入；非法时返回 null。 */
	public static String validate(String rawInput) {
		if (rawInput == null) {
			return null;
		}
		final String input = rawInput.trim();
		if (input.isEmpty()) {
			return null;
		}
		final String lower = input.toLowerCase(Locale.ROOT);
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
		final String upper = value.toUpperCase(Locale.ROOT);
		if (upper.equals("UP") || upper.equals("DW")) {
			return null; // UP/DW 已弃用
		}
		if (SPECIAL_COMBOS.contains(upper)) {
			return "path=" + upper;
		}
		return PATH_LETTER_PATTERN.matcher(input).matches() ? "path=" + upper : null;
	}
}
