package com.cmclinnovations.stack.clients.utils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class JsonHelper {

    private JsonHelper() {
    }

    @Nonnull
    public static final ObjectMapper getMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    public static final String handleFileValues(String value) {
        if (null != value && value.startsWith("@")) {
            String file = value.substring(1);
            try {
                value = Files.readString(Path.of(file));
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Failed to read file '" + Path.of(file).toAbsolutePath().toString() + "'.", ex);
            }
        }
        return value;
    }

    /**
     * Parses a JSON file into a JSON object.
     * 
     * @param file Path to the file.
     */
    public static final JsonNode readFile(String file) {
        try {
            return getMapper().readTree(new File(file));
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException("Invalid file path: " + file, ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read file: " + file, ex);
        }
    }
}
