package com.cmclinnovations.stack.clients.utils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;

public final class FileUtils {

    private FileUtils() {
    }

    public static void ensureScriptsExecutable(Path dir) throws IOException {
        try (Stream<Path> dirContent = Files.list(dir)) {
            dirContent.filter(path -> Files.isRegularFile(path) && !Files.isExecutable(path))
                    .forEach(file -> file.toFile().setExecutable(true, false));
        }
    }

    public static String sanitiseFilename(Path filepath) {
        // Extract the file name and replace most non-alphanumeric characters with "_"
        return filepath.getFileName().toString().replaceAll("[^\\w.]", "_");
    }

    public static String removeExtension(String filename) {
        int index = filename.lastIndexOf(".");
        if (index > 0) {
            return filename.substring(0, index);
        } else {
            // If "index" is "-1" (there is no ".") or 0 (it is the first character) then
            // just return the whole filename
            return filename;
        }
    }

    public static String replaceExtension(String filename, String newExtension) {
        return removeExtension(filename) + "." + newExtension;
    }

    public static String getFileNameWithoutExtension(Path path) {
        String filename = path.getFileName().toString();
        return removeExtension(filename);
    }

    public static String getFileNameWithoutExtension(URL url) {
        String[] pathComponents = url.getPath().split("/");
        return removeExtension(pathComponents[pathComponents.length - 1]);
    }

    public static boolean filterOnExtension(Path path, String extension) {
        return path.getFileName().toString().endsWith(extension);
    }

    public static Collection<URI> listFiles(URL dirURL, String fileExtension) throws IOException, URISyntaxException {
        return listFiles(dirURL).stream().filter(uri -> uri.toString().endsWith(fileExtension))
                .collect(Collectors.toSet());
    }

    public static Collection<URI> listFiles(URL dirURL) throws IOException, URISyntaxException {
        if (dirURL != null) {
            switch (dirURL.getProtocol()) {
                case "file":
                    // A file path: easy enough
                    return listFilesFromPath(Path.of(dirURL.toURI()));
                case "jar":
                    // A JAR path
                    return listFilesFromJar(dirURL);
                default:
            }
        }

        throw new UnsupportedOperationException(
                "Cannot load config files from URL '" + dirURL + "'. Only 'file' and 'jar' protocols are supported.");
    }

    private static Set<URI> listFilesFromPath(Path configDir) throws IOException {
        try (Stream<Path> stream = Files.list(configDir)) {
            return stream.map(Path::toUri).collect(Collectors.toSet());
        }
    }

    private static Set<URI> listFilesFromJar(URL dirURL) throws IOException {
        Set<URI> uris = new HashSet<>();
        // strip out only the JAR file
        String[] urlComponents = dirURL.getPath().split("!");
        String jarPath = urlComponents[0].replaceFirst("file:", "");
        String path = urlComponents[1].replaceFirst("^/?(.*?)/?$", "$1/");
        if (jarPath.startsWith("nested:")) {
            Path outerJarPath = Paths.get(jarPath.substring("nested:".length()));
            try (FileSystem fs = FileSystems.newFileSystem(outerJarPath, FileUtils.class.getClassLoader())) {
                Path innerJarPath = fs.getPath(path.replaceFirst("^(.*?)/?$", "$1/"));
                Path extractedJarPath = outerJarPath.resolveSibling(innerJarPath.getFileName().toString());

                Files.copy(innerJarPath, extractedJarPath, StandardCopyOption.REPLACE_EXISTING);

                jarPath = extractedJarPath.toString();
                path = urlComponents[2].replaceFirst("^/?(.*?)/?$", "$1/");
                dirURL = new URL("jar:" + extractedJarPath.toUri().toURL().toString() + "!/" + path);
            }
        }
        String cleanedDirURL = dirURL.toString().replaceFirst("^(.*?)/?$", "$1/");
        Path basePath = Path.of(path).normalize();
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries(); // gives ALL entries in jar
            while (entries.hasMoreElements()) {
                Path name = Path.of(entries.nextElement().getName()).normalize();
                if (name.startsWith(basePath)) { // filter according to the path
                    String entry = basePath.relativize(name).toString();
                    if (!entry.isEmpty() && !entry.contains("/")) {
                        uris.add(URI.create(cleanedDirURL + entry));
                    }
                }
            }
        }
        return uris;
    }

    public static String fixSlashes(String path, boolean leading, boolean trailing) {
        if (path.isEmpty() && (leading || trailing)) {
            return "/";
        }
        if (leading) {
            path = path.replaceFirst("^([^/])", "/$1");
        } else {
            path = path.replaceFirst("^/", "");
        }
        if (trailing) {
            path = path.replaceFirst("([^/])$", "$1/");
        } else {
            path = path.replaceFirst("/$", "");
        }
        return path;
    }

    public static boolean hasFileExtension(Path file, String extension) {
        return file.toString().endsWith("." + extension);
    }

    public static void checkPathIsRelative(String path, String fieldName) {
        Preconditions.checkArgument(!path.startsWith("/"),
                "The field '" + fieldName + "' must be a relative path, '%s' provided.", path);
        Preconditions.checkArgument(!path.isBlank(),
                "The field '" + fieldName + "' must not be blank, '%s' provided.", path);
    }
}
