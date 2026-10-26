package com.cmclinnovations.stack.clients.geoserver;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Legend {

    private final String format;
    private final Integer height;
    private final Integer width;
    private final String onlineResource;

    @JsonCreator
    public Legend(
            @JsonProperty(value = "onlineResource", required = true) String onlineResource,
            @JsonProperty("format") String format,
            @JsonProperty("height") Integer height,
            @JsonProperty("width") Integer width) {
        this.onlineResource = Objects.requireNonNull(onlineResource, "onlineResource is required");
        this.format = format;
        this.height = height;
        this.width = width;
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
}
