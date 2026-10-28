package com.cmclinnovations.stack.clients.geoserver;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Legend {

    private final String file;

    private final String format;
    private final Integer height;
    private final Integer width;
    private String onlineResource;

    @JsonCreator
    public Legend(
            @JsonProperty(value = "file", access = JsonProperty.Access.WRITE_ONLY) String file,
            @JsonProperty("onlineResource") String onlineResource,
            @JsonProperty("format") String format,
            @JsonProperty("height") Integer height,
            @JsonProperty("width") Integer width) {
        this.file = file;
        this.onlineResource = onlineResource;
        this.format = format;
        this.height = height;
        this.width = width;
    }

    public String getFile() {
        return file;
    }

    public String getFormat() {
        return format;
    }

    public Integer getHeight() {
        return height;
    }

    public Integer getWidth() {
        return width;
    }

    public String getOnlineResource() {
        return onlineResource;
    }

    public void setOnlineResource(String onlineResource) {
        this.onlineResource = onlineResource;
    }
}
