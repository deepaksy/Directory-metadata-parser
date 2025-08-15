package com.ttl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

import com.ttl.models.CommandLineValues;

public class Main {

    // Simple input validation to prevent injection or invalid paths
    private static String sanitizePathInput(String path) {
        // Remove any trailing/leading whitespace and normalize path separators
        String sanitized = path.trim();

        // Basic sanitation: remove null bytes or disallowed chars
        if (sanitized.contains("\0")) {
            throw new IllegalArgumentException("Invalid path: contains null bytes");
        }

        // Normalize to absolute path if possible
        try {
            Path p = Paths.get(sanitized).toAbsolutePath().normalize();
            sanitized = p.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid path format: " + sanitized);
        }

        return sanitized;
    }

    private static CommandLineValues validateInput(String[] args) {
        if (args.length == 0) {
            System.err.println("Please specify the base path! Use double backslashes (\\\\) on Windows.");
            System.out.println("Usage example: java -jar <jarfile>.jar -basepath=<path-to-directory> [-outputpath=<output-directory>]");
            System.exit(-1);
        }
        CommandLineValues values = new CommandLineValues();
        for (String arg : args) {
            arg = arg.trim();
            if (arg.startsWith("-help")) {
                help_menu();
                System.exit(0);
            }
            if (arg.startsWith("-basepath=")) {
                try {
                    values.basepath = sanitizePathInput(arg.substring("-basepath=".length()));
                } catch (IllegalArgumentException ex) {
                    System.err.println("Invalid -basepath argument: " + ex.getMessage());
                    System.exit(-1);
                }
            } else if (arg.startsWith("-outputpath=")) {
                try {
                    values.outputfilepath = sanitizePathInput(arg.substring("-outputpath=".length()));
                } catch (IllegalArgumentException ex) {
                    System.err.println("Invalid -outputpath argument: " + ex.getMessage());
                    System.exit(-1);
                }
            } else {
                System.err.println("Unknown argument ignored: " + arg);
            }
        }
        if (values.basepath == null || values.basepath.isBlank()) {
            System.err.println("Argument -basepath=<filepath> is required and cannot be empty.");
            System.exit(-1);
        }
        return values;
    }

    public static void help_menu() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar <jarfile>.jar -basepath=<path-to-directory> [-outputpath=<output-directory>]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -basepath=<path>       Required. The base directory path to scan for files.");
        System.out.println("  -outputpath=<path>     Optional. Directory where output files (results and errors) will be saved.");
        System.out.println("                         If omitted, current working directory is used.");
        System.out.println();
        System.out.println("Description:");
        System.out.println("  This utility recursively scans the directory specified by -basepath.");
        System.out.println("  It writes details of all files found to an output text file, including:");
        System.out.println("   - File name");
        System.out.println("   - Absolute path");
        System.out.println("   - Creation date");
        System.out.println("   - Last modified date");
        System.out.println("   - File size in bytes");
        System.out.println("  Errors encountered during scanning (like access denied) are logged to errors_parsing_<baseFolderName>.txt.");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar MyFileScanner.jar -basepath=C:\\Users\\Public\\Documents -outputpath=C:\\Output");
        System.out.println();
    }

    // FileVisitor collecting accessible files only, skipping inaccessible directories/files gracefully
    static class SafeFileCollector extends SimpleFileVisitor<Path> {
        private final List<Path> fileList = new ArrayList<>();
        private final BufferedWriter errorWriter;

        public SafeFileCollector(BufferedWriter errorWriter) {
            this.errorWriter = errorWriter;
        }

        public List<Path> getFileList() {
            return fileList;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!Files.isReadable(dir)) {
                logError("Skipping unreadable directory: " + dir);
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileList.add(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            if (exc instanceof AccessDeniedException) {
                logError("Access denied (skipped): " + file);
                return FileVisitResult.SKIP_SUBTREE;
            }
            logError("Error visiting: " + file + " - " + exc.getMessage());
            return FileVisitResult.CONTINUE;
        }

        private void logError(String message) {
            try {
                errorWriter.write(message);
                errorWriter.newLine();
                errorWriter.flush();
            } catch (IOException e) {
                System.err.println("Failed to write error: " + e.getMessage());
            }
        }
    }

    public static List<Path> safeWalk(Path startPath, BufferedWriter errorWriter) {
        SafeFileCollector collector = new SafeFileCollector(errorWriter);
        try {
            Files.walkFileTree(startPath, collector);
        } catch (IOException e) {
            try {
                errorWriter.write("Error walking file tree: " + e.getMessage());
                errorWriter.newLine();
                errorWriter.flush();
            } catch (IOException ioEx) {
                System.err.println("Failed to write error: " + ioEx.getMessage());
            }
        }
        return collector.getFileList();
    }

    public static void getDirectoryContents(Path startPath, BufferedWriter br, BufferedWriter errorWriter) throws IOException {
        List<Path> filePaths = safeWalk(startPath, errorWriter);
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

        filePaths.parallelStream()
                .filter(Files::isRegularFile)
                .map(path -> {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                        FileTime creationTime = attrs.creationTime();
                        FileTime modifiedTime = attrs.lastModifiedTime();
                        long sizeBytes = attrs.size();
                        String creationStr = formatter.format(creationTime.toInstant());
                        String modifiedStr = formatter.format(modifiedTime.toInstant());
                        return String.format("%s|%s|%s|%s|%d%n",
                                path.getFileName(),
                                path.toAbsolutePath(),
                                creationStr,
                                modifiedStr,
                                sizeBytes);
                    } catch (IOException e) {
                        String errorMsg = "Error accessing file attributes: " + path + " - " + e.getMessage();
                        System.err.println(errorMsg);
                        try {
                            errorWriter.write(errorMsg);
                            errorWriter.newLine();
                            errorWriter.flush();
                        } catch (IOException ex) {
                            System.err.println("Failed to write error: " + ex.getMessage());
                        }
                        return null;
                    }
                })
                .filter(line -> line != null)
                .forEachOrdered(line -> {
                    try {
                        br.write(line);
                    } catch (IOException e) {
                        String errorMsg = "Error writing line: " + e.getMessage();
                        System.err.println(errorMsg);
                        try {
                            errorWriter.write(errorMsg);
                            errorWriter.newLine();
                            errorWriter.flush();
                        } catch (IOException ex) {
                            System.err.println("Failed to write error: " + ex.getMessage());
                        }
                    }
                });
    }

    public static void main(String[] args) {
        CommandLineValues paths = validateInput(args);
        Path basePath = Paths.get(paths.basepath);
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            System.err.println("Base path does not exist or is not a directory: " + basePath);
            System.exit(1);
        }
        String baseFolderName;
        try {
            baseFolderName = basePath.getFileName().toString();
        } catch (Exception e) {
            baseFolderName = "output";
        }
        Path outputDir;
        if (paths.outputfilepath != null && !paths.outputfilepath.isBlank()) {
            outputDir = Paths.get(paths.outputfilepath);
            try {
                if (!Files.exists(outputDir)) {
                    Files.createDirectories(outputDir);
                }
            } catch (IOException e) {
                System.err.println("Unable to create output directory: " + outputDir);
                System.exit(1);
                return;
            }
        } else {
            outputDir = Paths.get(System.getProperty("user.dir"));
        }
        String outputFileName = baseFolderName.endsWith(".txt") ? baseFolderName : baseFolderName + ".txt";
        Path outputFile = outputDir.resolve(outputFileName);
        Path errorFile = outputDir.resolve(String.format("errors_parsing_%s.txt", baseFolderName));
        System.out.println("---------------------------------");
        System.out.println("Writing directory contents to: " + outputFile.toAbsolutePath());
        System.out.println("Writing error logs to: " + errorFile.toAbsolutePath());
        try (BufferedWriter br = Files.newBufferedWriter(outputFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedWriter errorWriter = Files.newBufferedWriter(errorFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            getDirectoryContents(basePath, br, errorWriter);
            System.out.println("Operation completed successfully.");
        } catch (IOException e) {
            System.err.println("Error while writing to files: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
