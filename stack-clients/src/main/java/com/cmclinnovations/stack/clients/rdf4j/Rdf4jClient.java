package com.cmclinnovations.stack.clients.rdf4j;

import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.eclipse.rdf4j.repository.sparql.config.SPARQLRepositoryConfig;
import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.EndpointNames;

public class Rdf4jClient extends ClientWithEndpoint<Rdf4jEndpointConfig> {

    private static Rdf4jClient instance = null;

    private final RemoteRepositoryManager manager;

    public static Rdf4jClient getInstance() {
        if (null == instance) {
            instance = new Rdf4jClient();
        }
        return instance;
    }

    private Rdf4jClient() {
        this(null, null, null);
    }

    public Rdf4jClient(String serverUrl, String username, String password) {
        super(EndpointNames.RDF4J, Rdf4jEndpointConfig.class);
        if (null == serverUrl || null == username || null == password) {
            Rdf4jEndpointConfig rdf4jEndpointConfig = readEndpointConfig();
            if (null == serverUrl) {
                serverUrl = rdf4jEndpointConfig.getServerServiceUrl();
            }
            if (null == username) {
                username = rdf4jEndpointConfig.getUsername();
            }
            if (null == password) {
                password = rdf4jEndpointConfig.getPassword();
            }
        }

        manager = RemoteRepositoryManager.getInstance(serverUrl, username, password);
    }

    public void createSparqlRepository(String id, String title, String queryEndpointUrl) {
        RepositoryConfig config = new RepositoryConfig(id, title, new SPARQLRepositoryConfig(queryEndpointUrl));
        manager.addRepositoryConfig(config);
    }

    public void createSparqlRepository(String id, String title, String queryEndpointUrl, String updateEndpointUrl) {
        RepositoryConfig config = new RepositoryConfig(id, title,
                new SPARQLRepositoryConfig(queryEndpointUrl, updateEndpointUrl));
        manager.addRepositoryConfig(config);
    }

}
