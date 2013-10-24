package com.twitter.university.webfilez;

import java.io.File;
import java.io.IOException;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.Tag;
import javax.servlet.jsp.tagext.TagSupport;

public class LastModifiedTag extends TagSupport {

	private static final long serialVersionUID = 1L;

	private String filePath;

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	@Override
	public int doStartTag() throws JspException {
		File file = new File(super.pageContext.getServletContext().getRealPath(
				this.filePath));
		String lastModified = String.valueOf(file.exists() ? file
				.lastModified() : 0);
		if (super.getId() == null) {
			try {
				super.pageContext.getOut().write(lastModified);
			} catch (IOException e) {
				throw new JspException("Failed to write lastModified=["
						+ lastModified + "] for [" + file.getAbsolutePath()
						+ "]", e);
			}
		} else {
			super.pageContext.setAttribute(getId(), lastModified);
		}
		return Tag.EVAL_PAGE;
	}
}
