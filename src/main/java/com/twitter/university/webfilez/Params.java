package com.twitter.university.webfilez;

public interface Params {
	public Object get(String key);

	public Object get(String key, Object defaultValue);

	public String getString(String key);

	public String getString(String key, String defaultValue);

	public Integer getInteger(String key);

	public Integer getInteger(String key, Integer defaultValue);

	public Boolean getBoolean(String key);

	public Boolean getBoolean(String key, Boolean defaultValue);

	public static abstract class Support implements Params {
		@Override
		public Object get(String key, Object defaultValue) {
			Object value = this.get(key);
			return value == null ? defaultValue : value;
		}

		@Override
		public String getString(String key) {
			return (String) this.get(key);
		}

		@Override
		public String getString(String key, String defaultValue) {
			return (String) this.get(key, defaultValue);
		}

		@Override
		public Integer getInteger(String key) {
			Object value = this.get(key);
			return value == null ? null
					: value instanceof Integer ? (Integer) value : Integer
							.parseInt(value.toString());
		}

		@Override
		public Integer getInteger(String key, Integer defaultValue) {
			Object value = this.getInteger(key);
			return value == null ? defaultValue : (Integer) value;
		}

		@Override
		public Boolean getBoolean(String key) {
			Object value = this.get(key);
			return value == null ? null
					: value instanceof Boolean ? (Boolean) value : Boolean
							.valueOf(value.toString());
		}

		@Override
		public Boolean getBoolean(String key, Boolean defaultValue) {
			Boolean value = this.getBoolean(key);
			return value == null ? defaultValue : (Boolean) value;
		}
	}
}