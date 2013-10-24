package com.twitter.university.webfilez;


public class Util {
	private Util() {

	}

	public static boolean contains(String searchFor, String... searchIn) {
		if (searchFor != null && searchIn != null && searchIn.length > 0) {
			for (String s : searchIn) {
				if (searchFor.equals(s)) {
					return true;
				}
			}
		}
		return false;
	}
}
