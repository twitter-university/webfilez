package com.marakana.webfilez;

import junit.framework.Assert;

import org.junit.Test;

public class WebUtilTest {
	@Test
	public void testGetParentUriPath() {
		Assert.assertNull(WebUtil.getParentUriPath(null));
		Assert.assertNull(WebUtil.getParentUriPath(""));
		Assert.assertNull(WebUtil.getParentUriPath("a"));
		Assert.assertNull(WebUtil.getParentUriPath("foo"));
		Assert.assertEquals("", WebUtil.getParentUriPath("foo/"));
		Assert.assertEquals("/", WebUtil.getParentUriPath("/foo/"));
		Assert.assertEquals("/foo/", WebUtil.getParentUriPath("/foo/bar"));
		Assert.assertEquals("/foo/", WebUtil.getParentUriPath("/foo/bar/"));
		Assert.assertEquals("/foo/bar/", WebUtil.getParentUriPath("/foo/bar/x"));
		Assert.assertEquals("/foo/bar/",
				WebUtil.getParentUriPath("/foo/bar/x/"));
		Assert.assertEquals("/foo/bar/x/",
				WebUtil.getParentUriPath("/foo/bar/x/y"));
		Assert.assertEquals("/foo/bar/x/y/",
				WebUtil.getParentUriPath("/foo/bar/x/y/z"));
	}
}
