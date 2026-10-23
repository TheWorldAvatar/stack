package com.cmclinnovations.stack.clients.geoserver;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GeoServerStyle {

    @JsonProperty(value = "name", required = true, access = JsonProperty.Access.WRITE_ONLY)
    private String name;

    @JsonProperty(value = "file", required = true, access = JsonProperty.Access.WRITE_ONLY)
    private Path file;

    private Legend legend;

    public GeoServerStyle() {
        // For Jackson
    }

    public GeoServerStyle(String name, Path file, Legend legend) {
        this.name = name;
        this.file = file;
        this.legend = legend;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public Legend getLegend() {
        return legend;
    }

    public void setLegend(Legend legend) {
        this.legend = legend;
    }
}
