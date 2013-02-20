package com.marakana.webfilez;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FileUtil {

	private FileUtil() {

	}

	public static void delete(Path file) throws IOException {
		Objects.requireNonNull(file);
		if (Files.isRegularFile(file)) {
			Files.delete(file);
		} else {
			Files.walkFileTree(file, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file,
						BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir,
						IOException e) throws IOException {
					if (e == null) {
						Files.delete(dir);
						return FileVisitResult.CONTINUE;
					} else {
						throw e; // directory iteration failed
					}
				}
			});
		}
	}

	public static void copy(Path source, Path destination) throws IOException {
		if (source.equals(destination)) {
			return;
		} else if (!Files.exists(source)) {
			throw new FileNotFoundException("No such file/dir to copy: "
					+ source);
		} else if (Files.isDirectory(source)) {
			if (!Files.exists(destination)) {
				Files.createDirectories(destination);
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(source)) {
				for (Path file : files) {
					copy(file, destination.resolve(file.getFileName()));
				}
			}
		} else {
			Files.copy(source, destination);
		}
		Files.setLastModifiedTime(destination,
				Files.getLastModifiedTime(source));
	}

	public static long size(Path file) throws IOException {
		Objects.requireNonNull(file);
		if (Files.isRegularFile(file)) {
			return Files.size(file);
		} else {
			long size = 0;
			try (DirectoryStream<Path> files = Files.newDirectoryStream(file)) {
				for (Path f : files) {
					size += size(f);
				}
			}
			return size;
		}
	}

	public static Path getUniqueFileInDirectory(Path dir, String name) {
		int extension = name.lastIndexOf('.');
		return extension <= 0 || extension >= name.length() - 1 ? getUniqueFileInDirectory(
				dir, name, "") : getUniqueFileInDirectory(dir,
				name.substring(0, extension), name.substring(extension));
	}

	public static Path getUniqueFileInDirectory(Path dir, String name,
			String extension) {
		Path file = dir.resolve(name + extension);
		if (Files.exists(file)) {
			file = dir.resolve(name + " copy" + extension);
			for (int i = 2; Files.exists(file); i++) {
				file = dir.resolve(String.format("%s copy %d%s", name, i,
						extension));
			}
		}
		return file;
	}

	public static Path getBackupFile(Path file, String extension) {
		Path backupFile = file.resolveSibling(file.getFileName() + extension);
		for (int i = 1; Files.exists(backupFile); i++) {
			backupFile = backupFile.resolveSibling(String.format(
					".__%s_%03d%s", file.getFileName(), i, extension));
		}
		return backupFile;
	}

	private static void zipFileToStream(Path file, String path,
			ZipOutputStream zos) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("Refusing to ZIP non-file: "
					+ file);
		}
		ZipEntry entry = new ZipEntry(path);
		entry.setTime(Files.getLastModifiedTime(file).toMillis());
		zos.putNextEntry(entry);
		Files.copy(file, zos);
	}

	public static void zipFile(Path sourceFile, Path destinationFile)
			throws ZipException, IOException {
		try (ZipOutputStream zos = new ZipOutputStream(
				Files.newOutputStream(destinationFile))) {
			zipFileToStream(sourceFile, sourceFile.getFileName().toString(),
					zos);
		}
	}

	public static long sizeOfZip(Path path) throws IOException {
		if (Files.exists(path) && Files.isRegularFile(path)) {
			try (ZipFile zipFile = new ZipFile(path.toFile(), ZipFile.OPEN_READ)) {
				long size = 0;
				for (Enumeration<? extends ZipEntry> entries = zipFile
						.entries(); entries.hasMoreElements();) {
					ZipEntry entry = entries.nextElement();
					if (!entry.isDirectory()) {
						size += entry.getSize();
					}
				}
				return size;
			}
		} else {
			throw new IllegalArgumentException("No such file: " + path);
		}
	}

	public static void zipDirectory(final Path sourceDir,
			final Path relativeToPath, final ZipOutputStream out)
			throws ZipException, IOException {
		Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file,
					BasicFileAttributes attrs) throws IOException {
				zipFileToStream(file, relativeToPath.relativize(file)
						.toString(), out);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public static void zipDirectory(final Path sourceDir,
			final Path destinationFile) throws ZipException, IOException {
		if (!Files.isDirectory(sourceDir)) {
			throw new IllegalArgumentException(
					"Refusing to ZIP non-directory: " + sourceDir);
		}
		try (final ZipOutputStream out = new ZipOutputStream(
				Files.newOutputStream(destinationFile))) {
			zipDirectory(sourceDir, sourceDir.getParent(), out);
		}
	}

	public static void zipFiles(final Path sourceDir, List<Path> sourceFiles,
			OutputStream out) throws ZipException, IOException {
		try (final ZipOutputStream zOut = new ZipOutputStream(out)) {
			for (Path file : sourceFiles) {
				if (Files.isDirectory(file)) {
					zipDirectory(file, sourceDir, zOut);
				} else {
					zipFileToStream(file, file.getFileName().toString(), zOut);
				}
			}
		}
	}

	public static void zipFiles(Path sourceDir, List<Path> sourceFiles,
			Path destinationFile) throws ZipException, IOException {
		zipFiles(sourceDir, sourceFiles, Files.newOutputStream(destinationFile));
	}

	public static void unzip(Path sourceFile, Path destinationDir)
			throws ZipException, IOException {
		unzip(sourceFile, destinationDir, null);
	}

	private static void mkdirs(Path dir, PathHandler pathHandler)
			throws IOException {
		if (dir != null) {
			if (pathHandler == null) {
				Files.createDirectories(dir);
			} else if (!Files.exists(dir)) {
				mkdirs(dir.getParent(), pathHandler);
				Files.createDirectory(dir);
				pathHandler.handle(dir);
			}
		}
	}

	public static void unzip(Path sourceFile, Path destinationDir,
			PathHandler pathHandler) throws ZipException, IOException {
		try (ZipFile zipFile = new ZipFile(sourceFile.toFile(),
				ZipFile.OPEN_READ)) {
			for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries
					.hasMoreElements();) {
				ZipEntry entry = entries.nextElement();
				Path destinationFile = destinationDir.resolve(entry.getName());
				if (entry.isDirectory()) {
					mkdirs(destinationFile, pathHandler);
				} else {
					mkdirs(destinationFile.getParent(), pathHandler);
					Files.copy(zipFile.getInputStream(entry), destinationFile);
					long lastModified = entry.getTime();
					if (lastModified != -1) {
						Files.setLastModifiedTime(destinationFile,
								FileTime.fromMillis(lastModified));
					}
					if (pathHandler != null) {
						pathHandler.handle(destinationFile);
					}
				}
			}
		}
	}

	public static interface PathHandler {
		public void handle(Path path) throws IOException;
	}

	private static final String USAGE = "FileUtil <size|unzip|delete> <source-path> [destination-path]";

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println(USAGE);
			return;
		}
		String action = args[0];
		System.out.println(action);
		Path file = FileSystems.getDefault().getPath(args[1]);
		long t = System.nanoTime();
		switch (action) {
		case "size":
			System.out.println(size(file));
			break;
		case "size-of-zip":
			System.out.println(sizeOfZip(file));
			break;
		case "unzip":
			unzip(file,
					FileSystems.getDefault().getPath(
							args.length < 3 ? "." : args[3]));
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
