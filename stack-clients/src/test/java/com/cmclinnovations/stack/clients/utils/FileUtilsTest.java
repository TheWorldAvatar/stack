package com.cmclinnovations.stack.clients.utils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FileUtilsTest {
    @Test
    void testEnsureScriptsExecutable() {

    }

    @Test
    void testSanitiseFilename() {
        Path path = Paths.get("abc", "efg", "+Hello()World-123.csv");
        Assertions.assertEquals("_Hello__World_123.csv", FileUtils.sanitiseFilename(path));
    }

    @Test
    void testFilterOnExtension() {
        Assertions.assertEquals(List.of(
                Paths.get("abc", "efg", "123.csv"),
                Paths.get("abc", "efg", "345.csv")),
                Stream.of(
                        Paths.get("abc", "efg", "123.csv"),
                        Paths.get("abc", "efg", "345.csv"),
                        Paths.get("abc", "efg", "678.json"))
                        .filter(path -> FileUtils.filterOnExtension(path, ".csv"))
                        .collect(Collectors.toList()));
    }

    @Test
    void testGetFileNameWithoutExtension() {
        Assertions.assertEquals(List.of(
                "123", "345", "678"),
                Stream.of(
                        Paths.get("abc", "efg", "123.csv"),
                        Paths.get("abc", "efg", "345.csv"),
                        Paths.get("abc", "efg", "678.json"))
                        .map(path -> FileUtils.getFileNameWithoutExtension(path))
                        .collect(Collectors.toList()));
    }

    private Collection<URI> getExpectedURIs(URL dirURL, Collection<String> expectedFiles)
            throws URISyntaxException, IOException {
        return expectedFiles.stream().map(file -> {
            try {
                return new URI(dirURL.toString() + "/" + file);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toSet());
    }

    @Test
    void listFilesFromPath() throws IOException, URISyntaxException {
        URL dirURL = FileUtilsTest.class.getResource("files");
        Collection<URI> expectedURIs = getExpectedURIs(dirURL, List.of("123.csv", "abc.txt"));
        Assertions.assertEquals(expectedURIs, FileUtils.listFiles(dirURL));
    }

    @Test
    void listFilesFromPathFiltered() throws IOException, URISyntaxException {
        URL dirURL = FileUtilsTest.class.getResource("files");
        Collection<URI> expectedURIs = getExpectedURIs(dirURL, List.of("123.csv"));
        Assertions.assertEquals(expectedURIs, FileUtils.listFiles(dirURL, ".csv"));
    }

    @Test
    void listFilesFromJar() throws IOException, URISyntaxException {
        URL dirURL = new URL("jar", "", FileUtilsTest.class.getResource("files.jar").getPath() + "!/files");
        Collection<URI> expectedURIs = getExpectedURIs(dirURL, List.of("123.csv", "abc.txt"));
        Assertions.assertEquals(expectedURIs, FileUtils.listFiles(dirURL));
    }

    @Test
    void listFilesFromJarFiltered() throws IOException, URISyntaxException {
        URL dirURL = new URL("jar", "", FileUtilsTest.class.getResource("files.jar").getPath() + "!/files");
        Collection<URI> expectedURIs = getExpectedURIs(dirURL, List.of("abc.txt"));
        Assertions.assertEquals(expectedURIs, FileUtils.listFiles(dirURL, ".txt"));
    }

    private static Stream<Arguments> fixSlashesTestProvider() throws IOException {
        return Stream.of(
                Arguments.of("/", "", true, true),
                Arguments.of("/", "", true, false),
                Arguments.of("/", "", false, true),
                Arguments.of("", "", false, false),
                Arguments.of("/path/", "path", true, true),
                Arguments.of("/path", "path", true, false),
                Arguments.of("path/", "path", false, true),
                Arguments.of("path", "path", false, false),
                Arguments.of("/path/", "/path", true, true),
                Arguments.of("/path", "/path", true, false),
                Arguments.of("path/", "/path", false, true),
                Arguments.of("path", "/path", false, false),
                Arguments.of("/path/", "path/", true, true),
                Arguments.of("/path", "path/", true, false),
                Arguments.of("path/", "path/", false, true),
                Arguments.of("path", "path/", false, false),
                Arguments.of("/path/", "/path/", true, true),
                Arguments.of("/path", "/path/", true, false),
                Arguments.of("path/", "/path/", false, true),
                Arguments.of("path", "/path/", false, false),
                Arguments.of("/path/file/", "path/file", true, true),
                Arguments.of("/path/file", "path/file", true, false),
                Arguments.of("path/file/", "path/file", false, true),
                Arguments.of("path/file", "path/file", false, false),
                Arguments.of("/path/file/", "/path/file", true, true),
                Arguments.of("/path/file", "/path/file", true, false),
                Arguments.of("path/file/", "/path/file", false, true),
                Arguments.of("path/file", "/path/file", false, false),
                Arguments.of("/path/file/", "path/file/", true, true),
                Arguments.of("/path/file", "path/file/", true, false),
                Arguments.of("path/file/", "path/file/", false, true),
                Arguments.of("path/file", "path/file/", false, false),
                Arguments.of("/path/file/", "/path/file/", true, true),
                Arguments.of("/path/file", "/path/file/", true, false),
                Arguments.of("path/file/", "/path/file/", false, true),
                Arguments.of("path/file", "/path/file/", false, false));
    }

    @ParameterizedTest()
    @MethodSource("fixSlashesTestProvider")
    void testFixSlashesEmpty(String expected, String input, boolean leading, boolean trailing) {

        Assertions.assertEquals(expected, FileUtils.fixSlashes(input, leading, trailing));

    }
}
