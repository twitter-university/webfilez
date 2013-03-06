package com.marakana.webfilez;

import static com.marakana.webfilez.Util.contains;
import static java.lang.Long.parseLong;
import static java.lang.Math.min;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebUtil {
	private static Logger logger = LoggerFactory.getLogger(WebUtil.class);
	private static final Pattern CONTENT_DISPOSITION_FILE_NAME_EXTRACTOR_PATTERN = Pattern.compile(".*filename=['\"]?([^'\"]+)['\" ].*");
	private static final String[] READ_METHODS = { "GET", "HEAD", "OPTIONS" };
	private static final String[] WRITE_METHODS = { "POST", "PUT", "DELETE", };

	public static final String READ_ONLY_ALLOWED_METHODS_HEADER;
	public static final String WRITE_ONLY_ALLOWED_METHODS_HEADER;
	public static final String READ_WRITE_ALLOWED_METHODS_HEADER;

	static {
		StringBuilder sb = new StringBuilder();
		for (String m : READ_METHODS) {
			sb.append(m).append(",");
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
		READ_ONLY_ALLOWED_METHODS_HEADER = sb.toString();

		sb = new StringBuilder();
		for (String m : WRITE_METHODS) {
			sb.append(m).append(",");
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
		WRITE_ONLY_ALLOWED_METHODS_HEADER = sb.toString();
		READ_WRITE_ALLOWED_METHODS_HEADER = READ_ONLY_ALLOWED_METHODS_HEADER
				+ "," + WRITE_ONLY_ALLOWED_METHODS_HEADER;
	}

	private WebUtil() {

	}

	public static Params asParams(final Context ctx) {
		return new Params.Support() {
			@Override
			public Object get(String key) {
				try {
					return ctx.lookup(key);
				} catch (NameNotFoundException e) {
					return null;
				} catch (NamingException e) {
					throw new RuntimeException(
							"Failed to lookup [" + key + "]", e);
				}
			}
		};
	}

	public static Params asParams(final ServletContext ctx) {
		return new Params.Support() {
			@Override
			public Object get(String key) {
				return ctx.getInitParameter(key);
			}
		};
	}

	public static Params asParams(final ServletConfig config) {
		return new Params.Support() {
			@Override
			public Object get(String key) {
				return config.getInitParameter(key);
			}
		};
	}

	public static Params asParams(final FilterConfig config) {
		return new Params.Support() {
			@Override
			public Object get(String key) {
				return config.getInitParameter(key);
			}
		};
	}

	public static String getInitParameter(FilterConfig config, String name,
			String defaultValue) {
		String value = config.getInitParameter(name);
		return value == null ? defaultValue : value;
	}

	public static String getFileName(Part part) {
		String contentDisposition = part.getHeader("Content-Disposition");
		if (contentDisposition != null) {
			Matcher matcher = CONTENT_DISPOSITION_FILE_NAME_EXTRACTOR_PATTERN.matcher(contentDisposition);
			if (matcher.matches()) {
				return matcher.group(1);
			} else {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to extract filename from Content-Disposition=["
							+ contentDisposition
							+ "]. Returning ["
							+ part.getName() + "]");
				}
			}
		} else {
			if (logger.isWarnEnabled()) {
				logger.warn("No Content-Disposition header on part=[" + part
						+ "]. Returning [" + part.getName() + "].");
			}
		}
		return part.getName();
	}

	public static void setNoCacheHeaders(HttpServletResponse response) {
		response.setHeader("Cache-Control",
				"no-cache, no-store, must-revalidate"); // HTTP 1.1.
		response.setHeader("Pragma", "no-cache"); // HTTP 1.0.
		response.setDateHeader("Expires", 0); // Proxies
	}

	private static long getDateHeader(HttpServletRequest req, String headerName) {
		try {
			return req.getDateHeader(headerName);
		} catch (IllegalArgumentException e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Invalid date header [" + headerName + "] = ["
						+ req.getHeader(headerName) + "]", e);
			}
			return -1;
		}
	}

	/**
	 * Check if the request contains a header "If-Unmodified-Since" and compare
	 * it to lastModified value
	 * 
	 * @param req
	 *            the request
	 * @param lastModified
	 *            the value to compare to
	 * @return true if "If-Unmodified-Since" header is not present or its value
	 *         is greater than or equal to lastModified.
	 */
	public static boolean ifUnmodifiedSince(HttpServletRequest req,
			long lastModified) {
		long ifUnmodifiedSince = getDateHeader(req, "If-Unmodified-Since");
		if (ifUnmodifiedSince == -1) {
			return true;
		} else if (ifUnmodifiedSince < lastModified) {
			if (logger.isTraceEnabled()) {
				logger.trace(req.getRequestURI() + " not unmodified since "
						+ new Date(lastModified));
			}
			return false;
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace(req.getRequestURI() + " unmodified since "
						+ new Date(lastModified));
			}
			return true;
		}
	}

	/**
	 * Check if the request contains a header "If-Modified-Since" and compare it
	 * to lastModified value
	 * 
	 * @param req
	 *            the request
	 * @param lastModified
	 *            the value to compare to
	 * @return true if "If-Modified-Since" header is not present or its value is
	 *         less than lastModified.
	 */
	public static boolean ifModifiedSince(HttpServletRequest req,
			long lastModified) {
		long ifModifiedSince = getDateHeader(req, "If-Modified-Since");
		if (ifModifiedSince == -1) {
			return true;
		} else if (ifModifiedSince >= lastModified) {
			if (logger.isTraceEnabled()) {
				logger.trace(req.getRequestURI() + " not modified since "
						+ new Date(lastModified));
			}
			return false;
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace(req.getRequestURI() + " modified since "
						+ new Date(lastModified));
			}
			return true;
		}
	}

	public static boolean ifMatch(HttpServletRequest req, String eTag) {
		String ifMatch = req.getHeader("If-Match");
		if (ifMatch == null) {
			return true;
		} else if (ifMatch.equals("*")) {
			return eTag != null;
		} else {
			for (StringTokenizer st = new StringTokenizer(ifMatch, ","); st.hasMoreTokens();) {
				if (eTag.equals(st.nextToken().trim())) {
					if (logger.isTraceEnabled()) {
						logger.trace(req.getRequestURI() + " matches " + eTag);
					}
					return true;
				}
			}
			if (logger.isTraceEnabled()) {
				logger.trace(req.getRequestURI() + " does not match " + eTag);
			}
			return false;
		}
	}

	/**
	 * @return true if the request does not contain 'If-None-Match' or if its
	 *         value is '*' and the etag is null or if its value does not match
	 *         the etag; false otherwise.
	 */
	public static boolean ifNoneMatch(HttpServletRequest req, String eTag) {
		String ifNoneMatch = req.getHeader("If-None-Match");
		if (ifNoneMatch == null) {
			return true;
		} else if (ifNoneMatch.equals("*")) {
			return eTag == null;
		} else {
			for (StringTokenizer st = new StringTokenizer(ifNoneMatch, ","); st.hasMoreTokens();) {
				if (eTag.equals(st.nextToken().trim())) {
					if (logger.isTraceEnabled()) {
						logger.trace(req.getRequestURI() + " matches " + eTag);
					}
					return false;
				}
			}
			if (logger.isTraceEnabled()) {
				logger.trace(req.getRequestURI() + " does not match " + eTag);
			}
			return true;
		}
	}

	public static void setContentLength(HttpServletResponse resp, long length) {
		if (length < Integer.MAX_VALUE) {
			resp.setContentLength((int) length);
		} else {
			resp.setHeader("Content-Length", String.valueOf(length));
		}
	}

	public static String generateETag(long length, long lastModified) {
		return String.format("\"%d%d\"", length, lastModified);
	}

	public static boolean isHead(HttpServletRequest req) {
		return "HEAD".equalsIgnoreCase(req.getMethod());
	}

	public static boolean isJson(HttpServletRequest request) {
		String accept = request.getHeader("Accept");
		return accept != null && accept.startsWith("application/json");
	}

	public static boolean isZip(HttpServletRequest request) {
		String accept = request.getHeader("Accept");
		return accept != null && accept.equals("application/zip");
	}

	public static boolean isAjax(HttpServletRequest request) {
		String requestedWith = request.getHeader("X-Requested-With");
		return requestedWith != null
				&& requestedWith.equalsIgnoreCase("XMLHttpRequest");
	}

	public static boolean isRead(HttpServletRequest request) {
		return contains(request.getMethod(), READ_METHODS);
	}

	public static boolean isWrite(HttpServletRequest request) {
		return contains(request.getMethod(), WRITE_METHODS);
	}

	public static boolean isMultiPartRequest(HttpServletRequest request) {
		String method = request.getMethod();
		String contentType = request.getContentType();
		return (method != null && (method.equals("POST") || method.equals("PUT")))
				&& contentType != null && contentType.startsWith("multipart/");
	}

	public static String getParentUriPath(String s) {
		if (s != null && !s.isEmpty()) {
			int l = s.length();
			boolean endsWithSlash = s.charAt(l - 1) == '/';
			int i = s.lastIndexOf('/', l - (endsWithSlash ? 2 : 1));
			if (i > 0) {
				return s.substring(0, i + 1);
			} else if (i == 0) {
				return "/";
			} else {
				return endsWithSlash ? "" : null;
			}
		} else {
			return null;
		}
	}

	private static void setContentRangeHeader(HttpServletResponse response,
			long length) {
		response.addHeader("Content-Range", "bytes */" + length);
	}

	/**
	 * 
	 * @param request
	 * @param eTag
	 * @param lastModified
	 * @return true if there is no "If-Range" header or if its value matches the
	 *         eTag or it represents a date and its less than or equal to
	 *         lastModified
	 */
	public static boolean ifRange(HttpServletRequest request, String eTag,
			long lastModified) {
		String ifRangeHeader = request.getHeader("If-Range");
		if (ifRangeHeader == null
				|| (ifRangeHeader = ifRangeHeader.trim()).isEmpty()) {
			return true;
		} else if (ifRangeHeader.startsWith("W/")
				|| ifRangeHeader.charAt(0) == '"') {
			final boolean result = eTag.equals(ifRangeHeader);
			if (logger.isTraceEnabled()) {
				logger.trace("If-Range on " + request.getRequestURI()
						+ (result ? " matches " : " does not match ") + eTag);
			}
			return result;
		} else {
			final boolean result = getDateHeader(request, "If-Range") <= lastModified;
			if (logger.isTraceEnabled()) {
				logger.trace("If-Range on " + request.getRequestURI()
						+ (result ? " has" : " has not")
						+ " been modified since " + lastModified);
			}
			return result;
		}
	}

	public static List<Range> parseRange(HttpServletRequest request,
			HttpServletResponse response, String eTag, long lastModified,
			long length) throws IOException {
		String rangeHeader = request.getHeader("Range");
		if (length == 0 || rangeHeader == null
				|| !ifRange(request, eTag, lastModified)) {
			return Collections.emptyList();
		} else if (!rangeHeader.startsWith("bytes")) {
			setContentRangeHeader(response, length);
			response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			return null;
		} else {
			ArrayList<Range> result = new ArrayList<Range>(6);
			for (StringTokenizer st = new StringTokenizer(
					rangeHeader.substring(6), ","); st.hasMoreTokens();) {
				String range = st.nextToken().trim();
				int dashPos = range.indexOf('-');
				final long start;
				final long end;
				try {
					if (dashPos == -1) {
						setContentRangeHeader(response, length);
						response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
						return null;
					} else if (dashPos == 0) {
						long offset = parseLong(range);
						start = length + offset;
						end = length - 1;
					} else {
						start = parseLong(range.substring(0, dashPos));
						end = (dashPos < range.length() - 1) ? parseLong(range.substring(
								dashPos + 1, range.length())) : length - 1;
					}
					Range currentRange = new Range(start, end, length);
					if (!currentRange.isValid()) {
						setContentRangeHeader(response, length);
						response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
						return null;
					}
					result.add(currentRange);
				} catch (NumberFormatException e) {
					setContentRangeHeader(response, length);
					response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
					return null;
				}
			}
			return result;
		}
	}

	public static final class Range {

		private final long start;
		private final long end;
		private final long length;

		public Range(long start, long end, long length) {
			this.start = start;
			this.end = end;
			this.length = length;
		}

		public long getStart() {
			return this.start;
		}

		public long getEnd() {
			return this.end;
		}

		public long getLength() {
			return this.length;
		}

		public long getBytesToRead() {
			return this.end - this.start + 1;
		}

		public boolean isValid() {
			return (this.start >= 0) && (this.end >= 0)
					&& (this.start <= min(this.end, this.length - 1))
					&& (this.length > 0);
		}

		public String toContentRangeHeaderValue() {
			return String.format("bytes %d-%d/%d", this.start, this.end,
					this.length);
		}

		@Override
		public String toString() {
			return String.format("%d-%d/%d", this.start, this.end, this.length);
		}
	}
}
