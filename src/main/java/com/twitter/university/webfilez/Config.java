package com.twitter.university.webfilez;

import static com.twitter.university.webfilez.WebUtil.BINARY_CONTENT_TYPE;
import static com.twitter.university.webfilez.WebUtil.DIRECTORY_CONTENT_TYPE;
import static com.twitter.university.webfilez.WebUtil.asParams;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;

public final class Config {
    private int bufferSize;

    private String defaultMimeType;

    private String directoryMimeType;

    private Path readmeFileName;

    private String rootDir;

    private Collection<SearchAndReplace> rewriteRules;

    private Pattern basePathPattern;

    private boolean alwaysAllowCreationOfBasePathOnRead = false;

    private boolean appendAuthToUrls = false;

    private String tokenName;

    private String authorizationHeaderType;

    private String key;

    private String authUrl;

    private boolean supressRedirectOnNewAuth = false;

    private boolean suppressCookieOnNewAuth = false;

    private boolean alwaysAllowReadRequests = false;

    public static Config getConfig(ServletContext context) {
        return (Config) context.getAttribute(Config.class.getName());
    }

    public static Config buildAndRegisterConfig(ServletContext context) {
        final Config config = new Config();
        context.setAttribute(Config.class.getName(), config);
        return config;
    }

    private Config() {
        try {
            final Context ctx = new InitialContext();
            try {
                final Params params = asParams((Context) ctx
                        .lookup("java:comp/env/"));
                this.bufferSize = params.getInteger("buffer-size", 4096);
                this.directoryMimeType = params.getString(
                        "directory-mime-type", DIRECTORY_CONTENT_TYPE);
                this.defaultMimeType = params.getString("default-mime-type",
                        BINARY_CONTENT_TYPE);
                this.readmeFileName = FileSystems.getDefault().getPath(
                        params.getString("readme-file-name", "README.html"));
                final String rootDir = params.getString("root-dir");
                this.rootDir = rootDir == null ? "." : rootDir;
                this.rewriteRules = SearchAndReplace.parse(params
                        .getString("rewrite-rules"));
                this.basePathPattern = Pattern.compile(params.getString(
                        "base-path-pattern", "^(/[^/]+/[0-9]+/files/).*"));
                this.alwaysAllowCreationOfBasePathOnRead = params.getBoolean(
                        "always-allow-creation-of-base-path-on-read",
                        Boolean.FALSE);
                this.appendAuthToUrls = params.getBoolean(
                        "append-auth-to-urls", Boolean.FALSE);
                this.tokenName = params.getString("auth-token-name", "auth");
                this.authorizationHeaderType = params.getString(
                        "authorization-header-type", "mrknauth");
                String key = params.getString("auth-key", null);
                if (key == null) {
                    throw new RuntimeException(
                            "Missing required parameter [auth-key]");
                }
                this.key = key;
                this.authUrl = params.getString("auth-url");
                if (this.authUrl == null) {
                    throw new RuntimeException(
                            "Missing required parameter [auth-url]");
                }
                this.supressRedirectOnNewAuth = params.getBoolean(
                        "supress-redirect-on-new-auth", Boolean.FALSE);
                this.suppressCookieOnNewAuth = params.getBoolean(
                        "supress-cookie-on-new-auth", Boolean.FALSE);
                this.alwaysAllowReadRequests = params.getBoolean(
                        "always-allow-read-requests", Boolean.FALSE);
            } finally {
                ctx.close();
            }
        } catch (NamingException e) {
            throw new RuntimeException("Failed to init", e);
        }
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public String getDefaultMimeType() {
        return defaultMimeType;
    }

    public String getDirectoryMimeType() {
        return directoryMimeType;
    }

    public Path getReadmeFileName() {
        return readmeFileName;
    }

    public String getRootDir() {
        return rootDir;
    }

    public Collection<SearchAndReplace> getRewriteRules() {
        return rewriteRules;
    }

    public Pattern getBasePathPattern() {
        return basePathPattern;
    }

    public boolean isAlwaysAllowCreationOfBasePathOnRead() {
        return alwaysAllowCreationOfBasePathOnRead;
    }

    public boolean isAppendAuthToUrls() {
        return appendAuthToUrls;
    }

    public String getTokenName() {
        return tokenName;
    }

    public String getAuthorizationHeaderType() {
        return authorizationHeaderType;
    }

    public String getKey() {
        return key;
    }

    public String getAuthUrl() {
        return authUrl;
    }

    public boolean isSuppressCookieOnNewAuth() {
        return suppressCookieOnNewAuth;
    }

    public boolean isSupressRedirectOnNewAuth() {
        return supressRedirectOnNewAuth;
    }

    public boolean isAlwaysAllowReadRequests() {
        return alwaysAllowReadRequests;
    }

    @Override
    public String toString() {
        return "Config [bufferSize=" + bufferSize + ", defaultMimeType="
                + defaultMimeType + ", directoryMimeType=" + directoryMimeType
                + ", readmeFileName=" + readmeFileName + ", rootDir=" + rootDir
                + ", rewriteRules=" + rewriteRules + ", basePathPattern="
                + basePathPattern + ", alwaysAllowCreationOfBasePathOnRead="
                + alwaysAllowCreationOfBasePathOnRead + ", appendAuthToUrls="
                + appendAuthToUrls + ", tokenName=" + tokenName
                + ", authorizationHeaderType=" + authorizationHeaderType
                + ", key=*****" + ", authUrl=" + authUrl
                + ", suppressCookieOnNewAuth=" + suppressCookieOnNewAuth
                + ", supressRedirectOnNewAuth=" + supressRedirectOnNewAuth
                + ", alwaysAllowReadRequests=" + alwaysAllowReadRequests + "]";
    }
}
