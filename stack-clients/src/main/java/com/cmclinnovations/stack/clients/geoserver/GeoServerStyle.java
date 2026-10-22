package com.cmclinnovations.stack.clients.geoserver;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class GeoServerStyle {

    private final String name;
    private final Path file;
    private final Legend legend;

    @JsonCreator
    private GeoServerStyle(
            @JsonProperty(value = "name", required = true) @Nonnull String name,
            @JsonProperty(value = "file", required = true) @Nonnull Path file,
            @JsonProperty("legend") Legend legend) {
        this.name = name;
        this.file = file;
        this.legend = legend;
    }

    public String getName() {
        return name;
    }

    public Path getFile() {
        return file;
    }

    public Legend getLegend() {
        return legend;
    }

}
