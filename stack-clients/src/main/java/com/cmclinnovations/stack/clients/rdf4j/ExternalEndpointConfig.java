package com.cmclinnovations.stack.clients.rdf4j;

import com.cmclinnovations.stack.clients.core.AbstractEndpointConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_NULL)
public class ExternalEndpointConfig extends AbstractEndpointConfig {
    private final String id;
    private final String endpoint;

    protected ExternalEndpointConfig() {
        this(null, null, null);
    }

    public ExternalEndpointConfig(String id, String name, String endpoint) {
        super(name);
        this.id = id;
        this.endpoint = endpoint;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getId() {
        return id;
    }

}
