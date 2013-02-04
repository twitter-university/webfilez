package com.marakana.webfilez;

import static com.marakana.webfilez.Util.contains;

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
	private static final Pattern CONTENT_DISPOSITION_FILE_NAME_EXTRACTOR_PATTERN = Pattern
			.compile(".*filename=['\"]?([^'\"]+)['\" ].*");
	private static final String[] READ_METHODS = { "GET", "HEAD", "OPTIONS" };
	private static final String[] WRITE_METHODS = { "POST", "PUT", "DELETE", };
	public static final String ALLOWED_METHODS_HEADER;
	static {
		StringBuilder sb = new StringBuilder();
		for (String m : READ_METHODS) {
			sb.append(m).append(",");
		}
		for (String m : WRITE_METHODS) {
			sb.append(m).append(",");
		}
		if (sb.length() > 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
		ALLOWED_METHODS_HEADER = sb.toString();
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
			Matcher matcher = CONTENT_DISPOSITION_FILE_NAME_EXTRACTOR_PATTERN
					.matcher(contentDisposition);
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
				logger.trace("Modified: " + req.getRequestURI());
			}
			return false;
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace("Not modified: " + req.getRequestURI());
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
				logger.trace("Not modified: " + req.getRequestURI());
			}
			return false;
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace("Modified: " + req.getRequestURI());
			}
			return true;
		}
	}

	public static void setContentHeaders(HttpServletResponse resp, int length,
			long lastModified, String mimeType) {
		if (mimeType != null) {
			resp.setContentType(mimeType);
		}
		if (length >= 0) {
			resp.setContentLength(length);
		}
		if (lastModified >= 0) {
			resp.setDateHeader("Last-Modified", lastModified);
			resp.setHeader("ETag", generateETag(length, lastModified));
		}
	}

	public static String generateETag(int length, long lastModified) {
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
		return (method != null && (method.equals("POST") || method
				.equals("PUT")))
				&& contentType != null
				&& contentType.startsWith("multipart/");
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
}
