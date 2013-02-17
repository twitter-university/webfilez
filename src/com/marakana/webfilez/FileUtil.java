package com.marakana.webfilez;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FileUtil {

	private FileUtil() {

	}

	public static void move(File from, File to) throws IOException {
		if (from == null) {
			throw new NullPointerException("Cannot move from null file");
		} else if (to == null) {
			throw new NullPointerException("Cannot move to null file");
		} else if (!from.exists()) {
			throw new FileNotFoundException("No such file to move: "
					+ from.getAbsolutePath());
		} else if (!from.renameTo(to)) {
			throw new IOException("Failed to move [" + from.getAbsolutePath()
					+ "] to [" + to.getAbsolutePath() + "]");

		}
	}

	public static void delete(File file) throws IOException {
		if (file == null) {
			throw new NullPointerException("Cannot delete null file");
		} else if (!file.exists()) {
			throw new FileNotFoundException("No such file to delete: "
					+ file.getAbsolutePath());
		} else if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files != null && files.length > 0) {
				for (File f : files) {
					delete(f);
				}
			}
			if (!file.delete()) {
				throw new IOException("Failed to delete directory ["
						+ file.getAbsolutePath() + "]");
			}
		} else if (!file.delete()) {
			throw new IOException("Failed to delete file ["
					+ file.getAbsolutePath() + "]");
		}
	}

	public static void copy(File source, File destination) throws IOException {
		if (source.equals(destination)) {
			return;
		} else if (!source.exists()) {
			return;
		} else if (source.isDirectory()) {
			if (!destination.exists() && !destination.mkdirs()) {
				throw new IOException("Failed to copy [" + source + "] to ["
						+ destination
						+ "]. Cannot create destination directory.");
			} else {
				for (File file : source.listFiles()) {
					copy(file, new File(destination, file.getName()));
				}
				destination.setLastModified(source.lastModified());
			}
		} else {
			try (FileInputStream in = new FileInputStream(source);
					FileOutputStream out = new FileOutputStream(destination);) {
				copy(in, out);
				destination.setLastModified(source.lastModified());
			}
		}
	}

	public static int copy(InputStream in, OutputStream out) throws IOException {
		int total = 0;
		byte[] b = new byte[2048];
		for (int len = 0; (len = in.read(b)) > 0;) {
			out.write(b, 0, len);
			total += len;
		}
		return total;
	}

	public static long size(File file) {
		if (file == null) {
			return 0;
		} else if (file.isFile()) {
			return file.length();
		} else {
			long size = 0;
			File[] files = file.listFiles();
			if (files != null && files.length > 0) {
				for (File f : files) {
					size += size(f);
				}
			}
			return size;
		}
	}

	public static File getUniqueFileInDirectory(File dir, String filename) {
		int extension = filename.lastIndexOf('.');
		return extension <= 0 || extension >= filename.length() - 1 ? getUniqueFileInDirectory(
				dir, filename, "")
				: getUniqueFileInDirectory(dir,
						filename.substring(0, extension),
						filename.substring(extension));
	}

	public static File getUniqueFileInDirectory(File dir, String name,
			String extension) {
		File file = new File(dir, name + extension);
		if (file.exists()) {
			file = new File(dir, name + " copy" + extension);
			for (int i = 2; file.exists(); i++) {
				file = new File(dir, String.format("%s copy %d%s", name, i,
						extension));
			}
		}
		return file;
	}

	public static File getBackupFile(File file, String extension) {
		File backupFile = new File(file.getParent(), file.getName() + extension);
		for (int i = 1; file.exists(); i++) {
			backupFile = new File(file.getParent(), String.format(
					".__%s_%03d%s", file.getName(), i, extension));
		}
		return backupFile;
	}

	private static void zipFileToStream(File file, String path,
			ZipOutputStream zos) throws IOException {
		if (!file.isFile()) {
			throw new IllegalArgumentException("Refusing to ZIP non-file: "
					+ file.getAbsolutePath());
		}
		ZipEntry entry = new ZipEntry(path);
		entry.setTime(file.lastModified());
		zos.putNextEntry(entry);
		try (FileInputStream in = new FileInputStream(file);) {
			copy(in, zos);
		}
	}

	public static void zipFile(File sourceFile, File destinationFile)
			throws ZipException, IOException {
		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(
				destinationFile))) {
			zipFileToStream(sourceFile, sourceFile.getName(), zos);
		}
	}

	public static void zipDirectory(final File sourceDir,
			final File destinationFile) throws ZipException, IOException {
		if (!sourceDir.isDirectory()) {
			throw new IllegalArgumentException(
					"Refusing to ZIP non-directory: "
							+ sourceDir.getAbsolutePath());
		}
		final int stripPathLength = sourceDir.getParentFile().getAbsolutePath()
				.length() + 1;
		try (final ZipOutputStream zos = new ZipOutputStream(
				new FileOutputStream(destinationFile));) {
			visitFilesRecursively(sourceDir, new FileVisitor() {
				@Override
				public void visit(File file) throws IOException {
					if (!file.isDirectory()) {
						zipFileToStream(
								file,
								file.getAbsolutePath().substring(
										stripPathLength), zos);
					}
				}
			});
		}
	}

	public static void zipFiles(File sourceDir, List<File> sourceFiles,
			OutputStream out) throws ZipException, IOException {
		final int stripPathLength = sourceDir.getAbsolutePath().length() + 1;
		try (final ZipOutputStream zos = new ZipOutputStream(out)) {
			for (File file : sourceFiles) {
				if (file.isDirectory()) {
					visitFilesRecursively(file, new FileVisitor() {
						@Override
						public void visit(File f) throws IOException {
							if (!f.isDirectory()) {
								zipFileToStream(f, f.getAbsolutePath()
										.substring(stripPathLength), zos);
							}
						}
					});
				} else {
					zipFileToStream(file, file.getName(), zos);
				}
			}
		}
	}

	public static void zipFiles(File sourceDir, List<File> sourceFiles,
			File destinationFile) throws ZipException, IOException {
		zipFiles(sourceDir, sourceFiles, new FileOutputStream(destinationFile));
	}

	public static void unzip(File sourceFile, File destinationDir)
			throws ZipException, IOException {
		unzip(sourceFile, destinationDir, null);
	}

	private static boolean mkdirs(File dir, FileVisitor fileVisitor)
			throws IOException {
		if (fileVisitor == null) {
			return dir.mkdirs();
		} else if (dir.exists()) {
			return true;
		} else {
			File parent = dir.getParentFile();
			if (parent.exists() || mkdirs(parent, fileVisitor)) {
				if (dir.mkdir()) {
					fileVisitor.visit(dir);
					return true;
				}
			}
			return false;
		}
	}

	public static void unzip(File sourceFile, File destinationDir,
			FileVisitor fileVisitor) throws ZipException, IOException {
		try (ZipFile zipFile = new ZipFile(sourceFile, ZipFile.OPEN_READ)) {
			for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries
					.hasMoreElements();) {
				ZipEntry entry = entries.nextElement();
				File destinationFile = new File(destinationDir, entry.getName());
				if (entry.isDirectory()) {
					if (!mkdirs(destinationFile, fileVisitor)) {
						throw new IOException("Failed to create directory for "
								+ destinationFile.getAbsolutePath());
					}
				} else {
					File parent = destinationFile.getParentFile();
					if (!mkdirs(parent, fileVisitor)) {
						throw new IOException("Failed to create directory ["
								+ parent.getAbsolutePath() + "] for file "
								+ destinationFile.getAbsolutePath());
					}
					InputStream in = zipFile.getInputStream(entry);
					try (OutputStream out = new FileOutputStream(
							destinationFile)) {
						copy(in, out);
					}
					long lastModified = entry.getTime();
					if (lastModified != -1) {
						destinationFile.setLastModified(lastModified);
					}
					if (fileVisitor != null) {
						fileVisitor.visit(destinationFile);
					}
				}
			}
		}
	}

	public static String readFileToString(File file, Charset charset)
			throws IOException {
		if (charset == null) {
			charset = Charset.defaultCharset();
		}
		try (FileInputStream in = new FileInputStream(file)) {
			FileChannel fc = in.getChannel();
			MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0,
					fc.size());
			return charset.decode(buffer).toString();
		}
	}

	public static interface FileVisitor {
		public void visit(File file) throws IOException;
	}

	private static void visitFilesRecursively(final File dir,
			final FileVisitor fileVisitor) throws IOException {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					visitFilesRecursively(file, fileVisitor);
				}
				fileVisitor.visit(file);
			}
		}
	}

	private static final String USAGE = "FileUtil <size|unzip|delete> <source-path> [destination-path]";

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println(USAGE);
			return;
		}
		String action = args[0];
		File file = new File(args[1]);
		long t = System.nanoTime();
		switch (action) {
		case "size":
			System.out.println(size(file));
			break;
		case "unzip":
			unzip(file, new File(args.length < 3 ? "." : args[3]));
			break;
		case "delete":
			delete(file);
			break;
		default:
			System.err.println(USAGE);
			System.err.println("No such action: " + action);
		}
		t = System.nanoTime() - t;

		System.out.printf("Done in %d ns (%.3f ms, %.3f s)%n", t, t
				/ (double) 1000000, t / (double) 1000000000);
	}
}
