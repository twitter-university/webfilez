package com.marakana.webfilez;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class Util {
	private Util() {

	}

	public static <T> Iterable<T> asIterable(
			@SuppressWarnings("unchecked") final T... ts) {
		if (ts == null) {
			return null;
		} else {
			return new Iterable<T>() {

				@Override
				public Iterator<T> iterator() {
					return new Iterator<T>() {
						private int i = 0;

						@Override
						public boolean hasNext() {
							return this.i < ts.length;
						}

						@Override
						public T next() {
							if (!this.hasNext()) {
								throw new NoSuchElementException();
							}
							return ts[this.i++];
						}

						@Override
						public void remove() {
							throw new UnsupportedOperationException();
						}
					};
				}

			};
		}
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
