package com.cmclinnovations.stack.services;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.core.StackHost;
import com.cmclinnovations.stack.clients.rdf4j.Rdf4jEndpointConfig;
import com.cmclinnovations.stack.services.config.ServiceConfig;

public class GrlcService extends ContainerService {

    public static final String TYPE = "grlc";

    private static final String EXTERNAL_PORT_KEY = "EXTERNAL_PORT";
    private static final String GRLC_SERVER_NAME_KEY = "GRLC_SERVER_NAME";
    private static final String GRLC_SPARQL_ENDPOINT_KEY = "GRLC_SPARQL_ENDPOINT";

    public GrlcService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    protected void doPreStartUpConfiguration() {

        StackHost stackHost = StackClient.getStackHost();
        String serverName = stackHost.getStringBuilder()
                // Assume running locally if name not set
                .withName("localhost")
                // Assume using Nginx external port if name not set, otherwise no port (80/443 assumed)
                .withPort(stackHost.getName().isPresent() ? null : System.getenv(EXTERNAL_PORT_KEY))
                .withPath()
                .withExtraPath("rest")
                .build()
                .replaceAll("/+", "\\\\/");

        setEnvironmentVariableIfAbsent(GRLC_SERVER_NAME_KEY, serverName);
        setEnvironmentVariableIfAbsent(GRLC_SPARQL_ENDPOINT_KEY,
                readEndpointConfig(EndpointNames.RDF4J, Rdf4jEndpointConfig.class)
                        .getOutgoingRepositoryUrl().toString());

    }
}
