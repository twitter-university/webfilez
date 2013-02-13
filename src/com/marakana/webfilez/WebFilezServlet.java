package com.marakana.webfilez;

import static com.marakana.webfilez.FileUtil.copy;
import static com.marakana.webfilez.FileUtil.delete;
import static com.marakana.webfilez.FileUtil.getBackupFile;
import static com.marakana.webfilez.FileUtil.getUniqueFileInDirectory;
import static com.marakana.webfilez.FileUtil.move;
import static com.marakana.webfilez.FileUtil.size;
import static com.marakana.webfilez.FileUtil.unzip;
import static com.marakana.webfilez.FileUtil.zipDirectory;
import static com.marakana.webfilez.FileUtil.zipFile;
import static com.marakana.webfilez.FileUtil.zipFiles;
import static com.marakana.webfilez.WebUtil.asParams;
import static com.marakana.webfilez.WebUtil.generateETag;
import static com.marakana.webfilez.WebUtil.getFileName;
import static com.marakana.webfilez.WebUtil.getParentUriPath;
import static com.marakana.webfilez.WebUtil.ifMatch;
import static com.marakana.webfilez.WebUtil.ifModifiedSince;
import static com.marakana.webfilez.WebUtil.ifNoneMatch;
import static com.marakana.webfilez.WebUtil.ifUnmodifiedSince;
import static com.marakana.webfilez.WebUtil.isHead;
import static com.marakana.webfilez.WebUtil.isJson;
import static com.marakana.webfilez.WebUtil.isMultiPartRequest;
import static com.marakana.webfilez.WebUtil.isZip;
import static com.marakana.webfilez.WebUtil.parseRange;
import static com.marakana.webfilez.WebUtil.setContentLength;
import static com.marakana.webfilez.WebUtil.setNoCacheHeaders;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.json.JSONException;
import org.json.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.marakana.webfilez.FileUtil.FileVisitor;
import com.marakana.webfilez.WebUtil.Range;

public final class WebFilezServlet extends HttpServlet {
	private static final Pattern INVALID_SOURCE_PATH_PATTERN = Pattern
			.compile("(\\.\\.)");
	private static final Pattern INVALID_FILENAME_PATTERN = Pattern
			.compile("(\\.\\.)|/|\\\\");
	private static final long serialVersionUID = 1L;
	private static Logger logger = LoggerFactory
			.getLogger(WebFilezServlet.class);
	protected static final String MULTIPART_BOUNDARY = "webfilez_boundary";

	private int outputBufferSize;

	private int inputBufferSize;

	private String defaultMimeType;

	private String directoryMimeType;

	private String readmeFileName;

	private long readmeFileMaxLength;

	private Charset readmeFileCharset;

	private File rootDir;

	private Collection<SearchAndReplace> rewriteRules;

	private Pattern basePathPattern;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		try {
			final Context ctx = new InitialContext();
			try {
				final Params params = asParams((Context) ctx
						.lookup("java:comp/env/"));
				this.inputBufferSize = params.getInteger("input-buffer-size",
						2048);
				this.outputBufferSize = params.getInteger("output-buffer-size",
						2048);
				this.directoryMimeType = params.getString(
						"directory-mime-type", "x-directory/normal");
				this.defaultMimeType = params.getString("default-mime-type",
						"application/octet-stream");
				this.readmeFileName = params.getString("readme-file-name",
						"README.html");
				this.readmeFileMaxLength = params.getInteger(
						"readme-file-max-length", 5 * 1024 * 1024);
				this.readmeFileCharset = Charset.forName(params.getString(
						"readme-file-charset", "UTF-8"));
				final String rootDir = params.getString("root-dir");
				this.rootDir = rootDir == null ? new File(super
						.getServletContext().getRealPath(".")) : new File(
						rootDir);
				this.rewriteRules = SearchAndReplace.parse(params
						.getString("rewrite-rules"));
				this.basePathPattern = Pattern.compile(params.getString(
						"base-path-pattern", "^(/[^/]+/[0-9]+/files/).*"));

			} finally {
				ctx.close();
			}
		} catch (NamingException e) {
			throw new ServletException("Failed to init", e);
		}
	}

	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		final String basePath = this.getBasePath(request, true);
		String uri = request.getRequestURI();
		File file = this.getRequestFile(request);
		if (fileExistsOrItIsBasePathAndIsCreated(file, uri, basePath)) {
			if (file.isDirectory()) {
				if (uri.endsWith("/")) {
					if (isZip(request)
							|| "zip_download".equals(request
									.getParameter("_action"))) {
						try {
							this.handleZipDownloadRequest(request, response,
									file);
						} catch (IOException e) {
							this.sendServerFailure(
									request,
									response,
									"Failed to send ZIP of files in ["
											+ file.getAbsolutePath() + "]", e);
						}
					} else {
						try {
							this.handleList(request, response, file, basePath);
						} catch (JSONException e) {
							this.sendServerFailure(request, response,
									"Failed to send listing as JSON for ["
											+ file.getAbsolutePath() + "]", e);
						}
					}
				} else {
					if (logger.isDebugEnabled()) {
						logger.debug("Adding trailing slash to [" + uri + "]");
					}
					response.sendRedirect(uri + "/");
				}
			} else if (file.isFile()) {
				this.handleDownload(request, response, file);
			} else {
				this.sendServerFailure(request, response,
						"Not a file or a directory [" + file.getAbsolutePath()
								+ "] in response to [" + uri + "]");
			}
		} else {
			if (!isHead(request)) {
				// search for the closest folder that does exist
				// until we get to directory root
				for (; !file.equals(this.rootDir) && !file.exists()
						&& uri != null && !uri.equals(basePath); file = file
						.getParentFile(), uri = getParentUriPath(uri)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Trying to see if [" + uri + "] exists.");
					}
				}
				if (uri != null) {
					if (uri.equals(basePath)) {
						this.sendServerFailure(
								request,
								response,
								"Failed to access/create "
										+ file.getAbsolutePath());
					} else {
						if (logger.isDebugEnabled()) {
							logger.debug("Attempting to recover. Redirecting to: "
									+ uri);
						}
						response.sendRedirect(uri);
					}
					return;
				}
			}
			this.refuseRequest(request, response,
					HttpServletResponse.SC_NOT_FOUND,
					"No such file [" + file.getAbsolutePath()
							+ "] in response to [" + uri + "]");
		}
	}

	@Override
	protected void doDelete(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		final File file = getRequestFile(request);
		if (logger.isDebugEnabled()) {
			logger.debug("Processing request to delete ["
					+ file.getAbsolutePath() + "]");
		}
		if (file.exists()) {
			final long lastModified = file.lastModified();
			final String etag = generateETag(file.length(), lastModified);
			if (!file.isFile()
					|| (ifMatch(request, etag) && ifUnmodifiedSince(request,
							lastModified))) {
				if (delete(file)) {
					response.setStatus(HttpServletResponse.SC_NO_CONTENT);
				} else {
					this.sendServerFailure(request, response,
							"Failed to delete [" + file.getAbsolutePath() + "]");
				}
			} else {
				this.refuseRequest(request, response,
						HttpServletResponse.SC_PRECONDITION_FAILED,
						"Refusing to delete file for which precondition failed: ["
								+ file.getAbsolutePath() + "]");
			}
		} else {
			this.refuseRequest(
					request,
					response,
					HttpServletResponse.SC_NOT_FOUND,
					"Cannot delete a file that does not exist ["
							+ file.getAbsolutePath() + "]");
		}
	}

	@Override
	protected void doPut(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		final File file = this.getRequestFile(request);
		final String type = request.getContentType();
		final boolean makeDirRequest = this.directoryMimeType.equals(type);
		final int responseCode;
		final String uri = request.getRequestURI();
		final String basePath = this.getBasePath(request, true);
		if (fileExistsOrItIsBasePathAndIsCreated(file, uri, basePath)) {
			if (makeDirRequest) {
				if (file.isDirectory()) {
					if (logger.isDebugEnabled()) {
						logger.debug("Directory already exists ["
								+ file.getAbsolutePath() + "]. Ignoring.");
					}
				} else {
					this.refuseRequest(request, response,
							HttpServletResponse.SC_CONFLICT,
							"Cannot create directory since a file with the same name already exists ["
									+ file.getAbsolutePath() + "]");
					return;
				}
			} else {
				if (file.isDirectory()) {
					this.refuseRequest(request, response,
							HttpServletResponse.SC_CONFLICT,
							"Cannot create file since a directory with the same name already exists ["
									+ file.getAbsolutePath() + "]");
					return;
				} else {
					final long lastModified = file.lastModified();
					final String etag = generateETag(file.length(),
							lastModified);
					if (ifMatch(request, etag)
							&& ifUnmodifiedSince(request, lastModified)) {
						this.handleUploadToFile(file, request, response);
					} else {
						this.refuseRequest(request, response,
								HttpServletResponse.SC_PRECONDITION_FAILED,
								"Cannot modify file for which precondition failed: ["
										+ file.getAbsolutePath() + "]");
						return;
					}
				}
			}
			responseCode = HttpServletResponse.SC_OK;
		} else if (!fileExistsOrItIsBasePathAndIsCreated(file.getParentFile(),
				getParentUriPath(uri), basePath)) {
			this.refuseBadRequest(request, response,
					"The parent directory for [" + file.getAbsolutePath()
							+ "] does not exist");
			return;
		} else {
			if (makeDirRequest) {
				if (file.mkdir()) {
					if (logger.isDebugEnabled()) {
						logger.debug("Created directory ["
								+ file.getAbsolutePath() + "]");
					}
				} else {
					this.sendServerFailure(
							request,
							response,
							"Failed to create directory ["
									+ file.getAbsolutePath() + "]");
					return;
				}
			} else {
				if (file.createNewFile()) {
					if (logger.isDebugEnabled()) {
						logger.debug("Created file [" + file.getAbsolutePath()
								+ "]");
					}
					this.handleUploadToFile(file, request, response);
				} else {
					this.sendServerFailure(request, response,
							"Failed to create file [" + file.getAbsolutePath()
									+ "]");
					return;
				}
			}
			responseCode = HttpServletResponse.SC_CREATED;
		}
		response.setStatus(responseCode);
		try {
			this.sendFileInfoResponse(file, response);
		} catch (JSONException e) {
			if (logger.isErrorEnabled()) {
				logger.error(
						"Failed to generate JSON response to PUT request file ["
								+ file.getAbsolutePath() + "]", e);
			}
			response.resetBuffer();
		}
	}

	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		final String action = request.getParameter("_action");
		final String uri = request.getRequestURI();
		final String basePath = this.getBasePath(request, true);
		if (action == null) {
			refuseBadRequest(
					request,
					response,
					"No action specified for POST to "
							+ request.getRequestURI());
		} else {
			final File file = getRequestFile(request);
			if (fileExistsOrItIsBasePathAndIsCreated(file, uri, basePath)) {
				try {
					if (file.isDirectory()) {
						switch (action) {
						case "upload":
							if (isMultiPartRequest(request)) {
								this.handleUpload(file, request, response);
							} else {
								refuseBadRequest(request, response,
										"Not a valid POST upload request to "
												+ request.getRequestURI());
							}
							break;
						case "zip":
							this.handleZip(file, request, response);
							break;
						case "rename":
							this.handleRename(file, request, response);
							break;
						case "copy":
							this.handleCopy(file, request, response);
							break;
						case "move":
						case "cut":
							this.handleMove(file, request, response);
							break;
						default:
							refuseBadRequest(
									request,
									response,
									"Unsupported POST action [" + action
											+ "] to directory "
											+ request.getRequestURI());
						}
					} else if (file.isFile()) {
						switch (action) {
						case "unzip":
							this.handleUnzip(file, request, response);
							break;
						case "rename":
							this.handleRename(file, request, response);
							break;
						default:
							refuseBadRequest(
									request,
									response,
									"Unsupported POST action [" + action
											+ "] to directory "
											+ request.getRequestURI());
						}
					} else {
						this.refuseBadRequest(
								request,
								response,
								"Don't know how to handle POST to file "
										+ file.getAbsolutePath());
					}
				} catch (Exception e) {
					this.sendServerFailure(request, response,
							"Failed to handle POST " + request.getRequestURI()
									+ " for [" + action + "]", e);
				}
			} else {
				this.refuseRequest(request, response,
						HttpServletResponse.SC_NOT_FOUND,
						"Cannot handle a POST request for file that does not exist: "
								+ file.getAbsolutePath());
			}
		}
	}

	private void handleZipDownloadRequest(HttpServletRequest request,
			HttpServletResponse response, File dir) throws ServletException,
			IOException {
		if (logger.isTraceEnabled()) {
			logger.trace("ZIP-downloading files in [" + dir.getAbsolutePath()
					+ "]");
		}
		final List<File> files = this.getFilesFromRequest(request, dir);
		String filename = request.getParameter("filename");
		if (files.isEmpty()) {
			this.refuseBadRequest(request, response,
					"Select at least one file to zip-download");
		} else {
			if (filename == null) {
				filename = (files.size() == 1 ? files.get(0) : dir).getName()
						+ ".zip";
			}
			response.setContentType("application/zip");
			response.setHeader("Content-Disposition",
					String.format("attachment; filename=\"%s\"", filename));
			response.setHeader("Accept-Ranges", "none");
			FileUtil.zipFiles(dir, files, response.getOutputStream());
		}
	}

	private void handleList(HttpServletRequest request,
			HttpServletResponse response, File dir, String basePath)
			throws IOException, ServletException, JSONException {
		final String uri = request.getRequestURI();
		final long lastModified = dir.lastModified();
		// TODO, we should probably find the most up-to-date file and use it for
		// last-modified???
		if (lastModified >= 0) {
			response.setDateHeader("Last-Modified", lastModified);
		}
		setNoCacheHeaders(response);
		if (isHead(request)) {
			response.setHeader("ETag", generateETag(size(dir), lastModified));
		} else if (isJson(request)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Listing files in [" + dir.getAbsolutePath() + "]");
			}
			File[] files = dir.listFiles();
			if (files == null) {
				this.sendServerFailure(
						request,
						response,
						"Failed to list files in directory: "
								+ dir.getAbsolutePath());
			} else {
				response.setContentType("application/json");
				response.setHeader("Accept-Ranges", "none");
				final int bufferSize = 1024 + files.length * 110;
				response.setBufferSize(bufferSize);
				long totalSize = 0;
				File readmeFile = null;
				final JSONWriter jsonWriter = new JSONWriter(
						response.getWriter());
				jsonWriter.object();
				jsonWriter.key("files").array();

				for (File f : files) {
					totalSize += writeFileInfoToJson(f, jsonWriter);
					if (f.getName().equals(this.readmeFileName)) {
						readmeFile = f;
					}
				}
				jsonWriter.endArray();
				if (response.isCommitted()) {
					if (logger.isWarnEnabled()) {
						logger.warn("Cannot send ETag, because the response with buffer size ["
								+ bufferSize + "] has already been committed ");
					}
				} else {
					response.setHeader("ETag",
							generateETag(totalSize, lastModified));
				}
				jsonWriter.key("uri").value(uri);
				jsonWriter.key("name").value(dir.getName());
				jsonWriter.key("type").value(this.directoryMimeType);
				jsonWriter.key("size").value(totalSize);
				jsonWriter.key("lastModified").value(dir.lastModified());
				jsonWriter.key("writeAllowed").value(
						this.getWriteAllowed(request));
				if (uri.length() > basePath.length()
						&& uri.startsWith(basePath)) {
					jsonWriter.key("parent").value(getParentUriPath(uri));
				} else {
					if (logger.isTraceEnabled()) {
						logger.trace("No parent present for uri [" + uri
								+ "] and basePath [" + basePath + "]");
					}
				}
				if (readmeFile != null) {
					if (readmeFile.length() > this.readmeFileMaxLength) {
						if (logger.isWarnEnabled()) {
							logger.warn("README file ["
									+ readmeFile.getAbsolutePath() + "] size ["
									+ readmeFile.length()
									+ "] exceeds max size ["
									+ this.readmeFileMaxLength + "]. Ignoring.");
						}
					} else {
						try {
							final String readme = FileUtil.readFileToString(
									readmeFile, this.readmeFileCharset);
							jsonWriter.key("readme").value(readme);
						} catch (IOException e) {
							if (logger.isWarnEnabled()) {
								logger.warn("Failed to read the README file ["
										+ readmeFile.getAbsolutePath()
										+ "]. Ignoring.", e);
							}
						}
					}
				} else {
					if (logger.isTraceEnabled()) {
						logger.trace("No README file present for uri [" + uri
								+ "] and dir [" + dir.getAbsolutePath() + "]");
					}
				}
				jsonWriter.endObject();
			}
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace("Sending HTML for listing ["
						+ request.getRequestURI() + "]");
			}
			request.setAttribute("uri", uri);
			request.setAttribute("dir", dir);
			request.setAttribute("writeAllowed", this.getWriteAllowed(request));
			request.getRequestDispatcher("/WEB-INF/jsp/listing.jsp").forward(
					request, response);
		}
	}

	private String getContentType(File file) {
		final String mimeType = super.getServletContext().getMimeType(
				file.getName());
		return mimeType == null ? this.defaultMimeType : mimeType;
	}

	private boolean isClientAbortException(IOException e) {
		final Throwable cause = e.getCause();
		return cause instanceof SocketException
				&& "Broken pipe".equals(cause.getMessage());
	}

	private void sendFile(File file, OutputStream out, Range range)
			throws FileNotFoundException, IOException {
		final long length = file.length();
		if (logger.isTraceEnabled()) {
			logger.trace(String.format("Sending bytes %d-%d/%d of file %s",
					range == null ? 0 : range.getStart(),
					range == null ? max(length - 1, 0) : range.getEnd(),
					length, file.getAbsolutePath()));
		}
		try (final InputStream in = new BufferedInputStream(
				new FileInputStream(file), this.inputBufferSize)) {
			if (range != null) {
				final long skipped = in.skip(range.getStart());
				if (skipped < range.getStart()) {
					throw new IOException("Tried to skip [" + range.getStart()
							+ "] but actually skipped [" + skipped + "] on ["
							+ file.getAbsolutePath() + "]");
				}
			}
			long bytesToRead = range == null ? length : range.getBytesToRead();
			final byte[] buffer = new byte[this.inputBufferSize];
			for (int bytesRead; bytesToRead > 0
					&& (bytesRead = in.read(buffer, 0,
							(int) min(buffer.length, bytesToRead))) > 0;) {
				try {
					out.write(buffer, 0, bytesRead);
					bytesToRead -= bytesRead;
				} catch (IOException e) {
					if (isClientAbortException(e)) {
						if (logger.isDebugEnabled()) {
							logger.debug("Client aborted the connection while sending file ["
									+ file.getAbsolutePath() + "]. Bailing out");
						}
						return;
					} else {
						throw e;
					}
				}
			}
			try {
				out.flush();
				if (logger.isTraceEnabled()) {
					logger.trace("Sent " + file.getAbsolutePath());
				}
			} catch (IOException e) {
				if (isClientAbortException(e)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Client aborted the connection while flushing file ["
								+ file.getAbsolutePath() + "]. Bailing out");
					}
					return;
				} else {
					throw e;
				}
			}
		}
	}

	private void handleDownload(HttpServletRequest request,
			HttpServletResponse response, File file) throws IOException,
			ServletException {
		if (!file.canRead()) {
			this.refuseRequest(request, response,
					HttpServletResponse.SC_FORBIDDEN, "Cannot send file ["
							+ file.getAbsolutePath() + "] for request URI ["
							+ request.getRequestURI()
							+ "]; file cannot be read");
		} else {
			final long length = file.length();
			final long lastModified = file.lastModified();
			final String eTag = generateETag(length, lastModified);
			final String contentType = getContentType(file);
			if (lastModified >= 0) {
				response.setDateHeader("Last-Modified", lastModified);
			}
			if (eTag != null) {
				response.setHeader("ETag", eTag);
			}
			response.setHeader("Accept-Ranges", "bytes");
			final List<Range> ranges = parseRange(request, response, eTag,
					lastModified, length);
			if (ranges == null) {
				if (logger.isWarnEnabled()) {
					logger.warn("Cannot handle download of ["
							+ file.getAbsolutePath()
							+ "]. Problem with ranges.");
				}
				return;
			} else if (ifNoneMatch(request, eTag)
					|| ifModifiedSince(request, lastModified)) {
				if (ranges.isEmpty()) {
					if (logger.isTraceEnabled()) {
						logger.trace(request.getMethod()
								+ " request for the entire "
								+ request.getRequestURI());
					}
					response.setStatus(HttpServletResponse.SC_OK);
					response.setContentType(contentType);
					setContentLength(response, length);
					if (!isHead(request)) {
						response.setBufferSize(this.outputBufferSize);
						sendFile(file, response.getOutputStream(), null);
					}
				} else if (ranges.size() == 1) {
					final Range range = ranges.get(0);
					if (logger.isTraceEnabled()) {
						logger.trace(request.getMethod() + " request for "
								+ range.toContentRangeHeaderValue() + " of "
								+ request.getRequestURI());
					}
					response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
					response.addHeader("Content-Range",
							range.toContentRangeHeaderValue());
					setContentLength(response, range.getBytesToRead());
					response.setContentType(contentType);
					if (!isHead(request)) {
						response.setBufferSize(this.outputBufferSize);
						sendFile(file, response.getOutputStream(), range);
					}
				} else if (ranges.size() > 1) {
					if (logger.isTraceEnabled()) {
						logger.trace(request.getMethod() + " request for "
								+ ranges + " of " + request.getRequestURI());
					}
					response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
					response.setContentType("multipart/byteranges; boundary="
							+ MULTIPART_BOUNDARY);
					if (!isHead(request)) {
						final ServletOutputStream out = response
								.getOutputStream();
						for (Range range : ranges) {
							// Writing MIME header.
							out.println();
							out.println("--" + MULTIPART_BOUNDARY);
							out.println("Content-Type: " + contentType);
							out.println("Content-Range: "
									+ range.toContentRangeHeaderValue());
							out.println();
							sendFile(file, out, range);
						}
					}
				}
			} else {
				response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			}
		}
	}

	private void handleUpload(File dir, HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException,
			JSONException {
		final Collection<Part> parts = request.getParts();
		if (logger.isTraceEnabled()) {
			logger.trace("Uploading " + parts.size() + " file(s)");
		}
		response.setContentType("text/plain");
		response.setCharacterEncoding("UTF-8");
		final Collection<File> uploadedFiles = new LinkedList<>();
		for (Part part : parts) {
			uploadedFiles.add(handleUpload(part, dir));
		}
		sendFileInfoResponse(uploadedFiles, response);
	}

	private void handleUploadToFile(File file, HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		InputStream in = null;
		long contentLength = 0;
		if (isMultiPartRequest(request)) {
			final Collection<Part> parts = request.getParts();
			if (parts.size() == 1) {
				final Part part = parts.iterator().next();
				final String partName = getFileName(part);
				if (!file.getName().equals(partName)) {
					if (logger.isWarnEnabled()) {
						logger.warn("Expecting filename [" + file.getName()
								+ "] but got [" + partName + "]. Ignoring.");
					}
				}
				contentLength = part.getSize();
				in = part.getInputStream();
				if (logger.isDebugEnabled()) {
					logger.debug("Uploading [" + contentLength
							+ "] bytes from part to [" + file.getAbsolutePath()
							+ "]");
				}
			} else {
				this.refuseBadRequest(request, response,
						"Expecting to upload one part to  [" + file.getName()
								+ "] but got [" + parts.size() + "]. Aborting.");
				return;
			}
		}
		if (in == null) {
			contentLength = request.getContentLength();
			if (contentLength > 0) {
				if (logger.isDebugEnabled()) {
					logger.debug("Uploading [" + contentLength
							+ "] bytes from requst to ["
							+ file.getAbsolutePath() + "]");
				}
				in = request.getInputStream();
			} else {
				if (logger.isTraceEnabled()) {
					logger.trace("Nothing to upload to ["
							+ file.getAbsolutePath() + "]");
				}
			}
		}
		if (in != null) {
			try {
				try (final FileOutputStream out = new FileOutputStream(file)) {
					int contentUploaded = FileUtil.copy(in, out);
					if (contentUploaded != contentLength) {
						if (logger.isWarnEnabled()) {
							logger.warn("Uploaded [" + contentUploaded
									+ "] bytes to [" + file.getAbsolutePath()
									+ "] but expected [" + contentLength
									+ "]. Ignoring.");
						}
					} else {
						if (logger.isDebugEnabled()) {
							logger.debug("Uploaded [" + contentUploaded
									+ "] bytes to [" + file.getAbsolutePath()
									+ "]");
						}
					}
				}
			} finally {
				in.close();
			}
		}
	}

	private void handleZip(File dir, HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException,
			JSONException {
		if (logger.isDebugEnabled()) {
			logger.debug("Handling request to zip files in directory ["
					+ dir.getAbsolutePath() + "]");
		}
		final File zipFile;
		final List<File> files = this.getFilesFromRequest(request, dir);
		if (files.size() == 1) {
			final File file = files.get(0);
			zipFile = getUniqueFileInDirectory(dir, file.getName(), ".zip");
			if (file.isDirectory()) {
				if (logger.isTraceEnabled()) {
					logger.trace("Zipping directory [" + file.getAbsolutePath()
							+ "] to [" + zipFile.getAbsolutePath() + "]");
				}
				zipDirectory(file, zipFile);
			} else {
				if (logger.isTraceEnabled()) {
					logger.trace("Zipping file [" + file.getAbsolutePath()
							+ "] to [" + zipFile.getAbsolutePath() + "]");
				}
				zipFile(file, zipFile);
			}
		} else {
			zipFile = getUniqueFileInDirectory(dir, "Archive", ".zip");
			if (logger.isTraceEnabled()) {
				logger.trace("Zipping files [" + files + "] to ["
						+ zipFile.getAbsolutePath() + "]");
			}
			zipFiles(dir, files, zipFile);
		}
		this.sendFileInfoResponse(zipFile, response);
	}

	private void handleUnzip(File file, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (logger.isDebugEnabled()) {
			logger.debug("Handling request to unzip file ["
					+ file.getAbsolutePath() + "]");
		}
		final File dir = file.getParentFile();
		final Collection<File> immediateCreatedFiles = new LinkedList<>();
		unzip(file, dir, new FileVisitor() {
			@Override
			public void visit(File createdFile) throws IOException {
				if (createdFile.getParentFile().equals(dir)) {
					immediateCreatedFiles.add(createdFile);
				}
			}
		});
		this.sendFileInfoResponse(immediateCreatedFiles, response);
	}

	private void handleRename(File file, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (logger.isDebugEnabled()) {
			logger.debug("Handling request to rename file ["
					+ file.getAbsolutePath() + "]");
		}
		final String newName = request.getParameter("newName");
		final File newFile = this.getFile(file.getParentFile(), newName);
		if (newFile == null) {
			refuseBadRequest(request, response,
					"Cannot rename [" + file.getAbsolutePath()
							+ "] because the newName=[" + newName
							+ "] is unspecified or invalid");
		} else if (newFile.exists()) {
			refuseBadRequest(request, response,
					"Cannot rename [" + file.getAbsolutePath()
							+ "] because newFile=[" + newFile.getAbsolutePath()
							+ "] already exists");
		} else if (file.renameTo(newFile)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Renamed [" + file.getAbsolutePath() + "] to ["
						+ newFile + "]");
			}
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
		} else {
			this.sendServerFailure(request, response, "Failed to rename ["
					+ file.getAbsolutePath() + "] to [" + newFile + "]");
		}
	}

	private void handleCopy(File dir, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (logger.isDebugEnabled()) {
			logger.debug("Handling request to copy file to directory ["
					+ dir.getAbsolutePath() + "]");
		}
		try {
			final File sourceFile = this.getSourceFile(request);
			if (sourceFile.exists()) {
				final File destinationFile = sourceFile.getParentFile().equals(
						dir) ? getUniqueFileInDirectory(dir,
						sourceFile.getName()) : new File(dir,
						sourceFile.getName());
				copy(sourceFile, destinationFile);
				if (logger.isDebugEnabled()) {
					logger.debug("Copied [" + sourceFile.getAbsolutePath()
							+ "] to [" + destinationFile.getAbsoluteFile()
							+ "]");
				}
				this.sendFileInfoResponse(destinationFile, response);
			} else {
				this.refuseRequest(request, response,
						HttpServletResponse.SC_NOT_FOUND, "Cannot copy ["
								+ sourceFile.getAbsolutePath()
								+ "] to directory [" + dir.getAbsolutePath()
								+ "] because the source file does not exist.");
			}
		} catch (IllegalArgumentException e) {
			this.refuseBadRequest(request, response, e.getMessage());
		}
	}

	private void handleMove(File dir, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (logger.isDebugEnabled()) {
			logger.debug("Handling request to move file to directory ["
					+ dir.getAbsolutePath() + "]");
		}
		try {
			final File sourceFile = this.getSourceFile(request);
			final File destinationFile = new File(dir, sourceFile.getName());
			if (sourceFile.equals(destinationFile)) {
				this.refuseRequest(request, response,
						HttpServletResponse.SC_NOT_FOUND, "Cannot move ["
								+ sourceFile.getAbsolutePath()
								+ "] over itself.");
			} else if (sourceFile.exists()) {
				if (sourceFile.renameTo(destinationFile)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Moved [" + sourceFile.getAbsolutePath()
								+ "] to [" + destinationFile.getAbsoluteFile()
								+ "]");
					}
					this.sendFileInfoResponse(destinationFile, response);
				} else {
					this.sendServerFailure(
							request,
							response,
							"Failed to move [" + sourceFile.getAbsolutePath()
									+ "] to ["
									+ destinationFile.getAbsoluteFile() + "]");
				}
			} else {
				this.refuseRequest(request, response,
						HttpServletResponse.SC_NOT_FOUND, "Cannot move ["
								+ sourceFile.getAbsolutePath()
								+ "] to directory [" + dir.getAbsolutePath()
								+ "] because the source file does not exist.");
			}
		} catch (IllegalArgumentException e) {
			this.refuseBadRequest(request, response, e.getMessage());
		}
	}

	private File handleUpload(Part part, File dir) throws IOException {
		final String filename = getFileName(part);
		if (filename == null) {
			throw new IOException("Failed to get filename from part.");
		} else if (filename.indexOf("..") != -1 || filename.indexOf('/') != -1) {
			throw new IOException("Detected illegal/invalid filename ["
					+ filename + "]");
		} else {
			final File file = new File(dir, filename);
			if (logger.isTraceEnabled()) {
				logger.trace("Uploading file name=[" + filename + "] of size=["
						+ part.getSize() + "] of type=["
						+ part.getContentType() + "] to ["
						+ file.getAbsolutePath() + "]");
			}

			File backupFile = null;
			if (file.exists()) {
				if (!move(file, backupFile = getBackupFile(file, ".backup"))) {
					backupFile = null;
				}
			}
			try (final InputStream in = new BufferedInputStream(
					part.getInputStream(), this.inputBufferSize);
					OutputStream out = new BufferedOutputStream(
							new FileOutputStream(file), this.outputBufferSize)) {
				byte[] buffer = new byte[this.inputBufferSize];
				int nread;
				while ((nread = in.read(buffer)) > 0) {
					out.write(buffer, 0, nread);
				}
				out.flush();
				if (logger.isDebugEnabled()) {
					logger.debug("Uploaded [" + filename + "] to ["
							+ file.getAbsolutePath() + "]");
				}
				delete(backupFile);
				return file;
			} catch (IOException e) {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to upload [" + filename + "] to ["
							+ file.getAbsolutePath() + "]", e);
				}
				delete(file);
				move(backupFile, file);
				throw e;
			}
		}
	}

	private List<File> getFilesFromRequest(HttpServletRequest req, File dir) {
		final String[] filenames = req.getParameterValues("file");
		final List<File> files;
		if (filenames == null || filenames.length == 0) {
			files = Collections.emptyList();
		} else {
			files = new ArrayList<File>(filenames.length);
			for (String filename : filenames) {
				File file = this.getFile(dir, filename);
				if (file != null) {
					if (file.exists()) {
						files.add(file);
					} else {
						if (logger.isDebugEnabled()) {
							logger.debug("Request-specified file ["
									+ file.getAbsolutePath()
									+ "] does not exist. Skipping.");
						}
					}
				}
			}
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Got [" + files.size() + "] file(s) from the request");
		}
		return files;
	}

	private File getRequestFile(HttpServletRequest request)
			throws UnsupportedEncodingException {
		return this.getRequestFile(request.getRequestURI());
	}

	private File getRequestFile(final String path)
			throws UnsupportedEncodingException {
		final File file = new File(this.rootDir,
				SearchAndReplace.searchAndReplace(
						URLDecoder.decode(path, "UTF-8"), this.rewriteRules));
		if (logger.isTraceEnabled()) {
			logger.trace("Resolved path [" + path + "] to ["
					+ file.getAbsolutePath() + "]");
		}
		return file;
	}

	private File getSourceFile(HttpServletRequest request)
			throws UnsupportedEncodingException {
		final String basePath = this.getBasePath(request, false);
		final String targetPath = request.getRequestURI();
		final String sourcePath = request.getParameter("source");

		if (sourcePath == null) {
			throw new IllegalArgumentException(
					"No [source] parameter specified");
		} else if (!sourcePath.startsWith(basePath)) {
			// refuse requests such as /WEB-INF/web.xml
			throw new IllegalArgumentException("SourcePath [" + sourcePath
					+ "] is outside basePath [" + basePath + "]");
		} else if (INVALID_SOURCE_PATH_PATTERN.matcher(sourcePath).find()) {
			// refuse requests such as /class/123/files/../../../WEB-INF/web.xml
			throw new IllegalArgumentException("SourcePath [" + sourcePath
					+ "] is not legal");
		} else if (targetPath.startsWith(sourcePath)) {
			// refuse requests such as copying /class/123/files/foo/ to
			// /class/123/files/foo/ or /class/123/files/foo/bar/
			throw new IllegalArgumentException("TargetPath [" + targetPath
					+ "] starts with sourcePath [" + sourcePath + "]");
		} else {
			return this.getRequestFile(sourcePath);
		}
	}

	private File getFile(File dir, String filename) {
		if (dir == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("No dir. Skipping.");
			}
		} else if (filename == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("No file. Skipping.");
			}
		} else if (INVALID_FILENAME_PATTERN.matcher(filename).find()) {
			if (logger.isWarnEnabled()) {
				logger.warn("Invalid filename detected [" + filename
						+ "]. Skipping.");
			}
		} else {
			return new File(dir, filename);
		}
		return null;
	}

	private String getFileType(File file) {
		if (file.isDirectory()) {
			return this.directoryMimeType;
		} else {
			String mimeType = super.getServletContext().getMimeType(
					file.getName());
			return mimeType == null ? this.defaultMimeType : mimeType;
		}
	}

	private long writeFileInfoToJson(Iterable<File> files, JSONWriter jsonWriter)
			throws JSONException {
		long size = 0;
		jsonWriter.array();
		for (File createdFile : files) {
			size += writeFileInfoToJson(createdFile, jsonWriter);
		}
		jsonWriter.endArray();
		return size;
	}

	private long writeFileInfoToJson(File file, JSONWriter jsonWriter)
			throws JSONException {
		final long size = size(file);
		final long lastModified = file.lastModified();
		jsonWriter.object();
		jsonWriter.key("name").value(file.getName());
		jsonWriter.key("type").value(getFileType(file));
		jsonWriter.key("size").value(size);
		jsonWriter.key("lastModified").value(lastModified);
		if (file.isFile()) {
			jsonWriter.key("eTag").value(generateETag(size, lastModified));
		}
		jsonWriter.endObject();
		return size;
	}

	private void sendFileInfoResponse(File file, HttpServletResponse response)
			throws IOException, JSONException {
		response.setContentType("application/json");
		JSONWriter jsonWriter = new JSONWriter(response.getWriter());
		writeFileInfoToJson(file, jsonWriter);
		response.flushBuffer();
	}

	private void sendFileInfoResponse(Collection<File> files,
			HttpServletResponse response) throws IOException, JSONException {
		response.setContentType("application/json");
		writeFileInfoToJson(files, new JSONWriter(response.getWriter()));
		response.flushBuffer();
	}

	private void refuseRequest(HttpServletRequest request,
			HttpServletResponse response, int responseCode, String msg)
			throws ServletException, IOException {
		if (logger.isWarnEnabled()) {
			logger.warn("Refusing request with [" + responseCode + "]. " + msg);
		}
		response.setStatus(responseCode);
	}

	private void refuseBadRequest(HttpServletRequest request,
			HttpServletResponse response, String msg) throws ServletException,
			IOException {
		refuseRequest(request, response, HttpServletResponse.SC_BAD_REQUEST,
				msg);
	}

	private void sendServerFailure(HttpServletRequest request,
			HttpServletResponse response, String msg, Throwable cause)
			throws ServletException, IOException {
		logger.error(msg, cause);
		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
	}

	private void sendServerFailure(HttpServletRequest request,
			HttpServletResponse response, String msg) throws ServletException,
			IOException {
		this.sendServerFailure(request, response, msg, null);
	}

	private String getBasePath(HttpServletRequest request, boolean strict) {
		String basePath = (String) request
				.getAttribute(Constants.BASE_PATH_ATTR_NAME);
		if (basePath == null) {
			basePath = "/";
		}
		if (strict) {
			Matcher matcher = this.basePathPattern.matcher(basePath);
			if (!matcher.matches()) {
				if (matcher.reset(request.getRequestURI()).matches()) {
					basePath = matcher.group(1);
				} else {
					if (logger.isDebugEnabled()) {
						logger.debug("Cannot extract strict base-path where ["
								+ request.getRequestURI()
								+ "] does not match ["
								+ this.basePathPattern.pattern() + "]");
					}
				}
			}
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Got base-path [" + basePath + "]");
		}
		return basePath;
	}

	private Boolean getWriteAllowed(HttpServletRequest request) {
		return (Boolean) request.getAttribute(Constants.WRITE_ALLOWED);
	}

	private boolean fileExistsOrItIsBasePathAndIsCreated(File file, String uri,
			String basePath) {
		if (file.exists()) {
			if (logger.isTraceEnabled()) {
				logger.trace("File/directory [" + file.getAbsolutePath()
						+ "] already exists");
			}
			return true;
		} else if (uri != null && uri.equals(basePath)) {
			if (file.mkdirs()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Created the base dir ["
							+ file.getAbsolutePath() + "]");
				}
				return true;
			} else {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to create base dir ["
							+ file.getAbsolutePath() + "]");
				}
				return false;
			}
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace("File/directory [" + file.getAbsolutePath()
						+ "] does not exist and uri=[" + uri
						+ "] is not the same as the base path=[" + basePath
						+ "]");
			}
			return false;
		}
	}
}
