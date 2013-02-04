package com.marakana.webfilez;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

public class HttpMethodSupportFilter implements Filter {

	private String methodParam = "_method";

	@Override
	public void init(FilterConfig config) throws ServletException {

	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest httpReq = (HttpServletRequest) req;
		String method = httpReq.getParameter(this.methodParam);
		if (method != null && !method.isEmpty()
				&& "POST".equals(httpReq.getMethod())) {
			req = new HttpMethodRequestWrapper(httpReq, method.toUpperCase());
		}
		chain.doFilter(req, resp);
	}

	@Override
	public void destroy() {

	}

	private static class HttpMethodRequestWrapper extends
			HttpServletRequestWrapper {

		private final String method;

		public HttpMethodRequestWrapper(HttpServletRequest request,
				String method) {
			super(request);
			this.method = method;
		}

		@Override
		public String getMethod() {
			return this.method;
		}
	}
}
