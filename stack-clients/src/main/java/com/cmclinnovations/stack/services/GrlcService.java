package com.cmclinnovations.stack.services;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.rdf4j.Rdf4jEndpointConfig;
import com.cmclinnovations.stack.services.config.ServiceConfig;

public class GrlcService extends ContainerService {

    public static final String TYPE = "grlc";

    private static final String GRLC_SPARQL_ENDPOINT_KEY = "GRLC_SPARQL_ENDPOINT";

    public GrlcService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    protected void doPreStartUpConfiguration() {

        setEnvironmentVariableIfAbsent(GRLC_SPARQL_ENDPOINT_KEY,
                readEndpointConfig(EndpointNames.RDF4J, Rdf4jEndpointConfig.class)
                        .getOutgoingRepositoryUrl().toString());

    }
}
