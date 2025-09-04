package com.cmclinnovations.stack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cmclinnovations.stack.clients.core.StackHost;
import com.fasterxml.jackson.annotation.JsonProperty;

public class StackConfig {

    private enum Selector {
        @JsonProperty("includes")
        INCLUDES,
        @JsonProperty("excludes")
        EXCLUDES;
    }

    @JsonProperty
    private StackHost host = new StackHost();

    @JsonProperty("services")
    private final EnumMap<Selector, List<String>> serviceSelectors = new EnumMap<>(Selector.class);

    @JsonProperty
    private final Map<String, String> volumes = new HashMap<>();

    @JsonProperty
    private final Boolean isolated = false;

    @JsonProperty("hostName")
    private void setHostName(String hostName) {
        host = new StackHost(hostName);
    }

    public StackHost getHost() {
        return host;
    }

    List<String> getIncludedServices() {
        return serviceSelectors.getOrDefault(Selector.INCLUDES, Collections.emptyList());
    }

    List<String> getExcludedServices() {
        return serviceSelectors.getOrDefault(Selector.EXCLUDES, Collections.emptyList());
    }

    Map<String, String> getVolumes() {
        return volumes;
    }

    public boolean isIsolated() {
        return isolated;
    }
}
