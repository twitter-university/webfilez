package com.twitter.university.webfilez;

import static com.twitter.university.webauthz.Access.READ;
import static com.twitter.university.webauthz.Access.WRITE;
import static com.twitter.university.webfilez.Constants.AUTH_TOKEN_ATTR_NAME;
import static com.twitter.university.webfilez.Constants.BASE_PATH_ATTR_NAME;
import static com.twitter.university.webfilez.Constants.DESCRIPTION_ATTR_NAME;
import static com.twitter.university.webfilez.Constants.EXPIRY;
import static com.twitter.university.webfilez.Constants.QUOTA;
import static com.twitter.university.webfilez.Constants.READ_ALLOWED;
import static com.twitter.university.webfilez.Constants.WRITE_ALLOWED;
import static com.twitter.university.webfilez.WebUtil.READ_ONLY_ALLOWED_METHODS_HEADER;
import static com.twitter.university.webfilez.WebUtil.READ_WRITE_ALLOWED_METHODS_HEADER;
import static com.twitter.university.webfilez.WebUtil.WRITE_ONLY_ALLOWED_METHODS_HEADER;
import static com.twitter.university.webfilez.WebUtil.isAjax;
import static com.twitter.university.webfilez.WebUtil.isRead;
import static com.twitter.university.webfilez.WebUtil.isWrite;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.Key;
import java.util.Map;
import java.util.regex.Matcher;

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
    private Config config;
    private Key key;

    public void init(FilterConfig filterConfig) throws ServletException {
        this.config = Config.getConfig(filterConfig.getServletContext());
        this.key = WebAuthz.generateKey(this.config.getKey());
    }

    private String getAuthTokenFromCookie(HttpServletRequest httpRequest) {
        Cookie cookie = getAuthCookie(httpRequest);
        if (cookie != null) {
            String authToken = cookie.getValue();
            if (logger.isTraceEnabled()) {
                logger.trace("Got auth token [" + authToken + "] from cookie");
            }
            return authToken;

        } else {
            return null;
        }
    }

    private String getAuthTokenFromHeader(HttpServletRequest httpRequest) {
        String authorizationHeader = httpRequest.getHeader("Authorization");
        String type = config.getAuthorizationHeaderType();
        if (authorizationHeader != null && authorizationHeader.startsWith(type)
                && authorizationHeader.length() > type.length() + 1) {
            String authToken = authorizationHeader.substring(type.length() + 1);
            if (logger.isTraceEnabled()) {
                logger.trace("Got [Authorization " + type + "]=[" + authToken
                        + "] from request header");
            }
            return authToken;
        } else {
            return null;
        }
    }

    private String getAuthTokenFromParameter(HttpServletRequest httpRequest) {
        String authToken = httpRequest.getParameter(config.getTokenName());
        if (authToken != null) {
            if (logger.isTraceEnabled()) {
                logger.trace("Got auth token [" + authToken
                        + "] from parameter");
            }
            return authToken;
        } else {
            return null;
        }
    }

    private boolean isHuman(HttpServletRequest httpRequest) {
        return !isAjax(httpRequest) && isRead(httpRequest);
    }

    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;
        final String actualPath = httpRequest.getRequestURI();
        final boolean isRead = isRead(httpRequest);
        final boolean isWrite = isWrite(httpRequest);
        final boolean sendCookie;
        WebAuthz auth = null;
        String authToken = this.getAuthTokenFromParameter(httpRequest);
        if (authToken == null) {
            authToken = this.getAuthTokenFromCookie(httpRequest);
            if (authToken == null) {
                authToken = this.getAuthTokenFromHeader(httpRequest);
                if (authToken == null) {
                    if (config.isAlwaysAllowReadRequests() && isRead) {
                        final Matcher matcher = config.getBasePathPattern()
                                .matcher(actualPath);
                        if (matcher.matches()) {
                            String basePath = matcher.group(1);
                            auth = new WebAuthz(basePath, basePath, 0,
                                    Access.READ_ONLY, 0, 0, null);
                            sendCookie = false;
                        } else {
                            refuseOrRedirectToAuthUrl(httpRequest,
                                    httpResponse,
                                    "no auth token on unsupported read request");
                            return; // bail out!
                        }
                    } else {
                        refuseOrRedirectToAuthUrl(httpRequest, httpResponse,
                                "no auth token");
                        return; // bail out!
                    }
                } else {
                    sendCookie = false;
                }
            } else {
                sendCookie = false;
            }
        } else {
            sendCookie = true;
        }

        try {
            if (auth == null) {
                auth = WebAuthz.decode(authToken, this.key);
            }
            setAllowHeaders(httpResponse, auth);
            if (!isRead && !isWrite) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String
                            .format("Unsupported %s request from user #%d (%s) to access %s",
                                    httpRequest.getMethod(), auth.getUserId(),
                                    auth.getUserDescription(),
                                    httpRequest.getRemoteAddr(),
                                    httpRequest.getRequestURI()));
                }
                httpResponse.setStatus(SC_METHOD_NOT_ALLOWED);
            } else if (!actualPath.startsWith(auth.getBasePath())
                    || actualPath.contains("..")) {
                refuseOrRedirectToAuthUrl(httpRequest, httpResponse,
                        "unsupported-path");
            } else if (auth.isExpired()) {
                refuseOrRedirectToAuthUrl(httpRequest, httpResponse,
                        "expired token");
            } else if ((isRead && !auth.getAccess().contains(READ))
                    || (isWrite && !auth.getAccess().contains(WRITE))) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String
                            .format("Unauthorized %s request from user #%d (%s) to access %s. Removing cookie and refusing request.",
                                    httpRequest.getMethod(), auth.getUserId(),
                                    auth.getUserDescription(),
                                    httpRequest.getRemoteAddr(),
                                    httpRequest.getRequestURI()));
                }
                // remove cookie by setting its TTL to 1 second?
                httpResponse.addCookie(buildAuthCookie(authToken,
                        auth.getBasePath(), 1));
                if (isHuman(httpRequest)) {
                    httpResponse.sendError(SC_FORBIDDEN);
                } else {
                    httpResponse.setStatus(SC_FORBIDDEN);
                }
            } else {
                if (sendCookie && authToken != null
                        && !this.config.isSuppressCookieOnNewAuth()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Adding auth cookie for token ["
                                + authToken + "] for path ["
                                + auth.getBasePath() + "] expiring in ["
                                + auth.getMaxAgeInSeconds()
                                + "] seconds. Redirecting to [" + actualPath
                                + "]");
                    }
                    httpResponse.addCookie(buildAuthCookie(authToken,
                            auth.getBasePath(), auth.getMaxAgeInSeconds()));
                    if (!config.isSupressRedirectOnNewAuth()) {
                        // TODO: check for cookie support, to prevent infinite
                        // recursion
                        httpResponse.sendRedirect(actualPath);
                        return;
                    }
                }
                httpRequest.setAttribute(BASE_PATH_ATTR_NAME,
                        auth.getBasePath());
                httpRequest.setAttribute(DESCRIPTION_ATTR_NAME,
                        auth.getDescription());
                httpRequest.setAttribute(QUOTA, auth.getQuota());
                httpRequest.setAttribute(EXPIRY, auth.getExpiry());
                httpRequest.setAttribute(READ_ALLOWED, auth.getAccess()
                        .contains(Access.READ));
                httpRequest.setAttribute(WRITE_ALLOWED, auth.getAccess()
                        .contains(Access.WRITE));
                httpRequest.setAttribute(AUTH_TOKEN_ATTR_NAME, authToken);
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
        } catch (RuntimeException e) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "Error while parsing request from "
                                + httpRequest.getRemoteAddr() + " for "
                                + httpRequest.getRequestURI() + " with auth=["
                                + authToken + "]", e);
            }
            // impose a mandatory delay to prevent brute-force?
            refuseOrRedirectToAuthUrl(httpRequest, httpResponse,
                    "error while handling request");
        }
    }

    private static void setAllowHeaders(HttpServletResponse httpResponse,
            WebAuthz auth) {
        final boolean read = auth.getAccess().contains(READ);
        final boolean write = auth.getAccess().contains(WRITE);
        if (read && !write) {
            httpResponse.setHeader("Allow", READ_ONLY_ALLOWED_METHODS_HEADER);
        } else if (read && write) {
            httpResponse.setHeader("Allow", READ_WRITE_ALLOWED_METHODS_HEADER);
        } else if (!read && write) {
            httpResponse.setHeader("Allow", WRITE_ONLY_ALLOWED_METHODS_HEADER);
        }
    }

    private Cookie buildAuthCookie(String authToken, String basePath, int maxAge) {
        Cookie cookie = new Cookie(config.getTokenName(), authToken);
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

    private void refuseOrRedirectToAuthUrl(HttpServletRequest request,
            HttpServletResponse response, String reason) throws IOException {
        StringBuffer requestUrlBuffer = request.getRequestURL();
        Map<String, String[]> params = request.getParameterMap();
        if (!params.isEmpty()) {
            requestUrlBuffer.append('?');
            for (Map.Entry<String, String[]> e : params.entrySet()) {
                String name = URLEncoder.encode(e.getKey(), URL_CHARSET);
                if (!name.equals(config.getTokenName())) {
                    for (String value : e.getValue()) {
                        requestUrlBuffer.append(name).append('=')
                                .append(URLEncoder.encode(value, URL_CHARSET))
                                .append('&');
                    }
                }
            }
        }
        String requestUrl = requestUrlBuffer.toString();
        String authUrl = config.getAuthUrl()
                + URLEncoder.encode(requestUrlBuffer.toString(), "UTF-8");

        if (isHuman(request)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Refusing [" + request.getMethod()
                        + "] request from [" + request.getRemoteAddr()
                        + "] to [" + requestUrl + "]: " + reason
                        + ". Redirecting to " + authUrl);
            }
            response.sendRedirect(authUrl);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Refusing [" + request.getMethod()
                        + "] request from [" + request.getRemoteAddr()
                        + "] to [" + requestUrl + "]: " + reason);
            }
            response.setHeader("Location", authUrl);
            response.setStatus(SC_UNAUTHORIZED);
        }
    }

    private Cookie getAuthCookie(HttpServletRequest httpRequest) {
        Cookie[] cookies = httpRequest.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(config.getTokenName())) {
                    return cookie;
                }
            }
        }
        return null;
    }
}
