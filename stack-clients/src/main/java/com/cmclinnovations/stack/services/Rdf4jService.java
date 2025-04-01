package com.cmclinnovations.stack.services;

import com.cmclinnovations.stack.clients.rdf4j.ExternalEndpointConfig;
import com.cmclinnovations.stack.clients.rdf4j.Rdf4jClient;
import com.cmclinnovations.stack.clients.rdf4j.Rdf4jEndpointConfig;

import java.util.List;
import java.util.stream.Collectors;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.services.config.ServiceConfig;

public class Rdf4jService extends ContainerService {

    public static final String TYPE = "rdf4j";

    private static final String RDF4J_USER_KEY = "RDF4J_USER";
    private static final String RDF4J_PASSWORD_FILE_KEY = "RDF4J_PASSWORD_FILE";

    private static final String DEFAULT_USERNAME = "rdf4j_user";
    private static final String DEFAULT_PORT = "8080";
    private static final String DEFAULT_PASSWORD_FILE = "/run/secrets/rdf4j_password";

    public static final String IN_STACK_REPO_ID = "stack-incoming";
    public static final String IN_STACK_REPO_TITLE = "Stack Repository (Incoming)";
    public static final String OUT_STACK_REPO_ID = "stack-outgoing";
    public static final String OUT_STACK_REPO_TITLE = "Stack Repository (Outgoing)";

    public Rdf4jService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    protected void doPreStartUpConfiguration() {

        if (ensureOptionalSecret("rdf4j_password")) {
            setEnvironmentVariableIfAbsent(RDF4J_USER_KEY, DEFAULT_USERNAME);
            setEnvironmentVariableIfAbsent(RDF4J_PASSWORD_FILE_KEY, DEFAULT_PASSWORD_FILE);
        } else {
            removeEnvironmentVariable(RDF4J_USER_KEY);
            removeEnvironmentVariable(RDF4J_PASSWORD_FILE_KEY);
        }

        Rdf4jEndpointConfig endpointConfig = new Rdf4jEndpointConfig(EndpointNames.RDF4J, getHostName(), DEFAULT_PORT,
                getEnvironmentVariable(RDF4J_USER_KEY), getEnvironmentVariable(RDF4J_PASSWORD_FILE_KEY));

        addEndpointConfig(endpointConfig);

    }

    @Override
    public void doEveryTimePostStartUpConfiguration() {
        Rdf4jClient client = Rdf4jClient.getInstance();
        if (!client.hasRepositoryConfig(IN_STACK_REPO_ID))
            client.createBlankRepository(IN_STACK_REPO_ID, IN_STACK_REPO_TITLE);

        List<ExternalEndpointConfig> externalEndpointConfig = readExternalEndpointConfig();
        externalEndpointConfig.forEach(
                config -> client.createSparqlRepository(config.getId(), config.getName(), config.getEndpoint()));
        List<String> ids = externalEndpointConfig.stream().map(ExternalEndpointConfig::getId)
                .collect(Collectors.toList());
        ids.add(IN_STACK_REPO_ID);
        client.createFederatedRepository(OUT_STACK_REPO_ID, OUT_STACK_REPO_TITLE, ids);
    }
}
