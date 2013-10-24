package com.twitter.university.webfilez;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public class SearchAndReplace {
	private final Pattern search;
	private final String replace;

	public SearchAndReplace(Pattern search, String replace) {
		this.search = search;
		this.replace = replace;
	}

	public Pattern getSearch() {
		return search;
	}

	public String getReplace() {
		return replace;
	}

	@Override
	public String toString() {
		return "SearchAndReplace [search=" + search + ", replace=" + replace
				+ "]";
	}

	public static String searchAndReplace(final String in,
			Collection<SearchAndReplace> rules) {
		for (SearchAndReplace searchAndReplace : rules) {
			String out = searchAndReplace.getSearch().matcher(in)
					.replaceAll(searchAndReplace.getReplace());
			if (!in.equals(out)) {
				return out;
			}
		}
		return in;
	}

	public static Collection<SearchAndReplace> parse(String in) {
		if (in == null || in.isEmpty()) {
			return Collections.emptyList();
		}
		Collection<SearchAndReplace> result = new LinkedList<>();
		StringTokenizer st = new StringTokenizer(in, " ");
		Pattern pattern = null;
		while (st.hasMoreTokens()) {
			String token = st.nextToken().trim();
			if (token.isEmpty()) {
				continue;
			}
			if (pattern == null) {
				pattern = Pattern.compile(token);
			} else {
				result.add(new SearchAndReplace(pattern, token));
				pattern = null;
			}
		}
		if (pattern != null) {
			throw new IllegalArgumentException(
					"Not every search pattern was matched with a corresponding replace");
		}
		return result;
	}
}