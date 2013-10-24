package com.twitter.university.webfilez;

import static com.twitter.university.webauthz.Access.READ;
import static com.twitter.university.webauthz.Access.WRITE;
import static com.twitter.university.webfilez.WebUtil.READ_WRITE_ALLOWED_METHODS_HEADER;
import static com.twitter.university.webfilez.WebUtil.asParams;
import static com.twitter.university.webfilez.WebUtil.isAjax;
import static com.twitter.university.webfilez.WebUtil.isRead;
import static com.twitter.university.webfilez.WebUtil.isWrite;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.Key;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.university.webauthz.Access;
import com.twitter.university.webauthz.WebAuthz;

public class AuthFilter implements Filter {

	private static Logger logger = LoggerFactory.getLogger(AuthFilter.class);
	private String tokenName;
	private String authorizationHeaderType;
	private Key key;
	private String authUrl;

	public void init(FilterConfig filterConfig) throws ServletException {
		try {
			Context ctx = new InitialContext();
			try {
				Params params = asParams((Context) ctx.lookup("java:comp/env/"));
				this.tokenName = params.getString("auth-token-name", "auth");
				this.authorizationHeaderType = params.getString(
						"authorization-header-type", "mrknauth");
				String key = params.getString("auth-key", null);
				if (key == null) {
					throw new ServletException(
							"Missing required parameter [auth-key]");
				}
				this.key = WebAuthz.generateKey(key);
				this.authUrl = params.getString("auth-url");
				if (this.authUrl == null) {
					throw new ServletException(
							"Missing required parameter [auth-url]");
				}
				if (logger.isInfoEnabled()) {
					logger.info("Initialized with auth-token-name=["
							+ tokenName + "], authorization-header-type=["
							+ authorizationHeaderType + "], auth-key["
							+ key.replaceAll(".", "*") + "], auth-url=["
							+ authUrl + "]");
				}
			} finally {
				ctx.close();
			}
		} catch (Exception e) {
			throw new ServletException("Failed to init", e);
		}
	}

	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		String authToken = request.getParameter(this.tokenName);
		boolean sendCookie;
		if (authToken == null) {
			Cookie cookie = getCookie(httpRequest, this.tokenName);
			if (cookie == null) {
				String authorizationHeader = httpRequest.getHeader("Authorization");
				if (authorizationHeader != null
						&& authorizationHeader.startsWith(this.authorizationHeaderType)
						&& authorizationHeader.length() > authorizationHeaderType.length() + 1) {
					authToken = authorizationHeader.substring(authorizationHeaderType.length() + 1);
					if (logger.isTraceEnabled()) {
						logger.trace("Got [Authorization "
								+ this.authorizationHeaderType + "]=["
								+ authToken + "] from request header header");
					}
					sendCookie = false;
				} else {
					redirectToAuthUrl(httpRequest, httpResponse,
							"no auth token");
					return;
				}
			} else {
				authToken = cookie.getValue();
				sendCookie = false;
				if (logger.isTraceEnabled()) {
					logger.trace("Got [" + this.tokenName + "]=[" + authToken
							+ "] from cookie");
				}
			}
		} else {
			sendCookie = true;
			if (logger.isTraceEnabled()) {
				logger.trace("Got [" + this.tokenName + "]=[" + authToken
						+ "] from request params");
			}
		}

		try {
			WebAuthz auth = WebAuthz.decode(authToken, this.key);
			String actualPath = httpRequest.getRequestURI();
			boolean isRead = isRead(httpRequest);
			boolean isWrite = isWrite(httpRequest);
			if (!isRead && !isWrite) {
				if (logger.isWarnEnabled()) {
					logger.warn(String.format(
							"Unsupported %s request from user #%d (%s) to access %s",
							httpRequest.getMethod(), auth.getUserId(),
							auth.getUserDescription(),
							httpRequest.getRemoteAddr(),
							httpRequest.getRequestURI()));
				}
				httpResponse.setHeader("Allow",
						READ_WRITE_ALLOWED_METHODS_HEADER);
				httpResponse.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			} else if (!actualPath.startsWith(auth.getBasePath())
					|| actualPath.contains("..")) {
				redirectToAuthUrl(httpRequest, httpResponse, "unsupported-path");
			} else if (auth.isExpired()) {
				redirectToAuthUrl(httpRequest, httpResponse, "expired token");
			} else if ((isRead && !auth.getAccess().contains(READ))
					|| (isWrite && !auth.getAccess().contains(WRITE))) {
				if (logger.isWarnEnabled()) {
					logger.warn(String.format(
							"Unauthorized %s request from user #%d (%s) to access %s. Removing cookie and refusing request.",
							httpRequest.getMethod(), auth.getUserId(),
							auth.getUserDescription(),
							httpRequest.getRemoteAddr(),
							httpRequest.getRequestURI()));
				}
				// remove cookie by setting its TTL to 1 second?
				Cookie cookie = buildCookie(this.tokenName, authToken,
						auth.getBasePath(), 1);
				httpResponse.addCookie(cookie);

				if (isAjax(httpRequest)
						|| !"GET".equals(httpRequest.getMethod())) {
					httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
				} else {
					httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
				}
			} else {
				if (sendCookie) {
					// TODO: check for cookie support, to prevent infinite
					// recursion
					if (logger.isDebugEnabled()) {
						logger.debug("Adding cookie [" + this.tokenName + "="
								+ authToken + "] for path ["
								+ auth.getBasePath() + "] expiring in ["
								+ auth.getMaxAgeInSeconds()
								+ "] seconds. Redirecting to [" + actualPath
								+ "]");
					}
					httpResponse.addCookie(buildCookie(this.tokenName,
							authToken, auth.getBasePath(),
							auth.getMaxAgeInSeconds()));
					httpResponse.sendRedirect(actualPath);
				} else {
					httpRequest.setAttribute(Constants.BASE_PATH_ATTR_NAME,
							auth.getBasePath());
					httpRequest.setAttribute(Constants.DESCRIPTION_ATTR_NAME,
							auth.getDescription());
					httpRequest.setAttribute(Constants.QUOTA, auth.getQuota());
					httpRequest.setAttribute(Constants.EXPIRY, auth.getExpiry());
					httpRequest.setAttribute(Constants.READ_ALLOWED,
							auth.getAccess().contains(Access.READ));
					httpRequest.setAttribute(Constants.WRITE_ALLOWED,
							auth.getAccess().contains(Access.WRITE));
					if (logger.isTraceEnabled()) {
						logger.trace(String.format(
								"Authorized user #%d (%s) from %s to %s %s for %s",
								auth.getUserId(), auth.getUserDescription(),
								httpRequest.getRemoteAddr(),
								httpRequest.getMethod(), actualPath,
								httpRequest.getHeader("Accept")));
					}
					chain.doFilter(request, response);
				}
			}
		} catch (RuntimeException e) {
			if (logger.isWarnEnabled()) {
				logger.warn(
						"Error while parsing request from "
								+ httpRequest.getRemoteAddr() + " for "
								+ httpRequest.getRequestURI() + " with auth=["
								+ authToken + "]", e);
			}
			// impose a mandatory delay to prevent brute-force?
			redirectToAuthUrl(httpRequest, httpResponse,
					"error while handling request");
		}
	}

	private static Cookie buildCookie(String tokenName, String authToken,
			String basePath, int maxAge) {
		Cookie cookie = new Cookie(tokenName, authToken);
		cookie.setHttpOnly(true);
		cookie.setPath(basePath);
		cookie.setMaxAge(maxAge);
		return cookie;
	}

	public void destroy() {
		if (logger.isInfoEnabled()) {
			logger.info("Destroyed");
		}
	}

	private static final String URL_CHARSET = "UTF-8";

	private void redirectToAuthUrl(HttpServletRequest request,
			HttpServletResponse response, String reason) throws IOException {
		StringBuffer requestUrlBuffer = request.getRequestURL();
		Map<String, String[]> params = request.getParameterMap();
		if (!params.isEmpty()) {
			requestUrlBuffer.append('?');
			for (Map.Entry<String, String[]> e : params.entrySet()) {
				String name = URLEncoder.encode(e.getKey(), URL_CHARSET);
				if (!name.equals(this.tokenName)) {
					for (String value : e.getValue()) {
						requestUrlBuffer.append(name).append('=').append(
								URLEncoder.encode(value, URL_CHARSET)).append(
								'&');
					}
				}
			}
		}
		String requestUrl = requestUrlBuffer.toString();
		String authUrl = this.authUrl
				+ URLEncoder.encode(requestUrlBuffer.toString(), "UTF-8");

		if (isAjax(request) || !"GET".equals(request.getMethod())) {
			if (logger.isDebugEnabled()) {
				logger.debug("Refusing [" + request.getMethod()
						+ "] request from [" + request.getRemoteAddr()
						+ "] to [" + requestUrl + "]: " + reason);
			}
			response.setHeader("Location", authUrl);
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		} else {
			if (logger.isDebugEnabled()) {
				logger.debug("Refusing [" + request.getMethod()
						+ "] request from [" + request.getRemoteAddr()
						+ "] to [" + requestUrl + "]: " + reason
						+ ". Redirecting to " + authUrl);
			}
			response.sendRedirect(authUrl);
		}
	}

	private static Cookie getCookie(HttpServletRequest httpRequest,
			String cookieName) {
		Cookie[] cookies = httpRequest.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (cookie.getName().equals(cookieName)) {
					return cookie;
				}
			}
		}
		return null;
	}
}
