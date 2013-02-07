package com.marakana.webfilez;

import static com.marakana.webauthz.Access.READ;
import static com.marakana.webauthz.Access.WRITE;
import static com.marakana.webfilez.WebUtil.ALLOWED_METHODS_HEADER;
import static com.marakana.webfilez.WebUtil.asParams;
import static com.marakana.webfilez.WebUtil.isAjax;
import static com.marakana.webfilez.WebUtil.isRead;
import static com.marakana.webfilez.WebUtil.isWrite;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.Key;

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

import com.marakana.webauthz.Access;
import com.marakana.webauthz.WebAuthz;

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
						"authorization-header-type", "MrknAuth");
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
		boolean cookieSet = false;
		if (authToken == null) {
			Cookie cookie = getCookie(httpRequest, this.tokenName);
			if (cookie == null) {
				String authorizationHeader = httpRequest
						.getHeader("Authorization");
				if (authorizationHeader != null
						&& authorizationHeader
								.startsWith(this.authorizationHeaderType)
						&& authorizationHeader.length() > authorizationHeaderType
								.length() + 1) {
					authToken = authorizationHeader
							.substring(authorizationHeaderType.length() + 1);
					if (logger.isTraceEnabled()) {
						logger.trace("Got [Authorization "
								+ this.authorizationHeaderType + "]=["
								+ authToken + "] from request header header");
					}
				}
			} else {
				authToken = cookie.getValue();
				cookieSet = true;
				if (logger.isTraceEnabled()) {
					logger.trace("Got [" + this.tokenName + "]=[" + authToken
							+ "] from cookie");
				}
			}
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace("Got [" + this.tokenName + "]=[" + authToken
						+ "] from request params");
			}
		}
		if (authToken == null) {
			redirectToAuthUrl(httpRequest, httpResponse, "no auth token");
		} else {
			try {
				WebAuthz auth = WebAuthz.decode(authToken, this.key);
				String actualPath = httpRequest.getRequestURI();
				boolean isRead = isRead(httpRequest);
				boolean isWrite = isWrite(httpRequest);
				if (!isRead && !isWrite) {
					if (logger.isWarnEnabled()) {
						logger.warn(String
								.format("Unsupported %s request from %s %s <%s> (%s) to access %s",
										httpRequest.getMethod(),
										auth.getFirstName(),
										auth.getLastName(), auth.getEmail(),
										httpRequest.getRemoteAddr(),
										httpRequest.getRequestURI()));
					}
					httpResponse.setHeader("Allow", ALLOWED_METHODS_HEADER);
					httpResponse
							.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
				} else if (!actualPath.startsWith(auth.getBasePath())
						|| actualPath.contains("..")) {
					redirectToAuthUrl(httpRequest, httpResponse,
							"unsupported-path");
				} else if (auth.isExpired()) {
					redirectToAuthUrl(httpRequest, httpResponse,
							"expired token");
				} else if ((isRead && !auth.getAccess().contains(READ))
						|| (isWrite && !auth.getAccess().contains(WRITE))) {
					if (logger.isWarnEnabled()) {
						logger.warn(String
								.format("Unauthorized %s request from %s %s <%s> (%s) to access %s. Removing cookie and refusing request.",
										httpRequest.getMethod(),
										auth.getFirstName(),
										auth.getLastName(), auth.getEmail(),
										httpRequest.getRemoteAddr(),
										httpRequest.getRequestURI()));
					}
					// remove cookie by setting its TTL to 1 second?
					Cookie cookie = buildCookie(this.tokenName, authToken,
							auth.getBasePath(), 1);
					httpResponse.addCookie(cookie);
					httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
				} else {
					if (cookieSet) {
						httpRequest.setAttribute(Constants.BASE_DIR_ATTR_NAME,
								auth.getBasePath());
						httpRequest.setAttribute(
								Constants.DESCRIPTION_ATTR_NAME,
								auth.getDescription());
						httpRequest.setAttribute(Constants.READ_ALLOWED, auth
								.getAccess().contains(Access.READ));
						httpRequest.setAttribute(Constants.WRITE_ALLOWED, auth
								.getAccess().contains(Access.WRITE));
						if (cookieSet ? logger.isTraceEnabled() : logger
								.isDebugEnabled()) {
							String msg = String
									.format("Authorized [%s %s] request for [%s] from [%s %s] <%s> at [%s]",
											httpRequest.getMethod(),
											actualPath,
											httpRequest.getHeader("Accept"),
											auth.getFirstName(),
											auth.getLastName(),
											auth.getEmail(),
											httpRequest.getRemoteAddr());
							if (cookieSet) {
								logger.trace(msg);
							} else {
								logger.debug(msg);
							}
						}
						chain.doFilter(request, response);
					} else {
						// TODO: check for cookie support, to prevent infinite
						// recursion
						if (logger.isDebugEnabled()) {
							logger.debug("Adding cookie [" + this.tokenName
									+ "=" + authToken + "] for path ["
									+ auth.getBasePath() + "] expiring in ["
									+ auth.getMaxAgeInSeconds()
									+ "] seconds. Redirecting to ["
									+ actualPath + "]");
						}
						Cookie cookie = buildCookie(this.tokenName, authToken,
								auth.getBasePath(), auth.getMaxAgeInSeconds());
						httpResponse.addCookie(cookie);
						httpResponse.sendRedirect(actualPath);
					}
				}
			} catch (RuntimeException e) {
				if (logger.isWarnEnabled()) {
					logger.warn("Error while parsing request from "
							+ httpRequest.getRemoteAddr() + " for "
							+ httpRequest.getRequestURI() + " with auth=["
							+ authToken + "]", e);
				}
				// impose a mandatory delay to prevent brute-force?
				redirectToAuthUrl(httpRequest, httpResponse,
						"error while handling request");
			}
		}
	}

	private static Cookie buildCookie(String tokenName, String authToken,
			String basePath, int maxAge) {
		Cookie cookie = new Cookie(tokenName, authToken);
		cookie.setPath(basePath);
		cookie.setMaxAge(maxAge);
		return cookie;
	}

	public void destroy() {
		if (logger.isInfoEnabled()) {
			logger.info("Destroyed");
		}
	}

	private void redirectToAuthUrl(HttpServletRequest request,
			HttpServletResponse response, String reason) throws IOException {
		StringBuffer requestUrlBuffer = request.getRequestURL();
		String queryString = request.getQueryString();
		if (queryString != null && !queryString.contains(this.tokenName)) {
			requestUrlBuffer.append('?').append(queryString);
		}
		String requestUrl = requestUrlBuffer.toString();
		String authUrl = this.authUrl
				+ URLEncoder.encode(requestUrlBuffer.toString(), "UTF-8");

		if (isAjax(request)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Refusing JavaScript request from ["
						+ request.getRemoteAddr() + "] to [" + requestUrl
						+ "]: " + reason + ". Redirecting to " + authUrl);
			}
			response.setHeader("Location", authUrl);
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		} else {
			if (logger.isDebugEnabled()) {
				logger.debug("Refusing request from ["
						+ request.getRemoteAddr() + "] to [" + requestUrl
						+ "]: " + reason + ". Redirecting to " + authUrl);
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
