package com.cmclinnovations.stack.clients.postgis;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.docker.DockerConfigHandler;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Database {

    private final String databaseName;

    private PostGISEndpointConfig endpointConfig;

    /**
     * Constructor for the short form (just the name as a string)
     */
    @JsonCreator
    public Database(String databaseName) {
        this.databaseName = databaseName;
    }

    /**
     * Constructor for the long form (full JSON object)
     */
    @JsonCreator
    public Database(@JsonProperty(value = "name") String name,
            @JsonProperty(value = "endpoint") PostGISEndpointConfig endpointConfig) {
        this.databaseName = name;
        this.endpointConfig = endpointConfig;
        DockerConfigHandler.writeEndpointConfig(endpointConfig);
    }

    public String getEndpointName() {
        return getEndpointConfig().getName();
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public PostGISEndpointConfig getEndpointConfig() {
        if (null == endpointConfig) {
            endpointConfig = DockerConfigHandler.readEndpointConfig(EndpointNames.POSTGIS,
                    PostGISEndpointConfig.class);
        }
        return endpointConfig;
    }

    @Override
    public String toString() {
        return databaseName;
    }

    public void ensureDefault() {
        if (EndpointNames.POSTGIS != getEndpointName()) {
            throw new IllegalStateException("This class/method does not support non-default Postgres databases.");
        }
    }

}
