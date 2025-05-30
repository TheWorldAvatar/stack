package com.cmclinnovations.stack.clients.rdf4j;

import com.cmclinnovations.stack.clients.core.PasswordEndpointConfig;
import com.cmclinnovations.stack.services.Rdf4jService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_NULL)
public class Rdf4jEndpointConfig extends PasswordEndpointConfig {
    private final String hostName;
    private final String port;
    private final String username;
    private final String serviceUrl;

    protected Rdf4jEndpointConfig() {
        this(null, null, null, null, null);
    }

    public Rdf4jEndpointConfig(String name, String hostName, String port, String username, String passwordFile) {
        super(name, passwordFile);
        this.hostName = hostName;
        this.port = port;
        this.username = username;
        this.serviceUrl = "http://" + this.hostName + ":" + this.port;
    }

    public String getHostName() {
        return hostName;
    }

    public String getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    @JsonIgnore
    public String getServerServiceUrl() {
        return getServiceUrl() + "/rdf4j-server";
    }

    @JsonIgnore
    public String getWorkbenchServiceUrl() {
        return getServiceUrl() + "/rdf4j-workbench";
    }

    @JsonIgnore
    public String getRepositoryUrl(String repositoryUrl) {
        return getServerServiceUrl() + "/repositories/" + repositoryUrl;
    }

    @JsonIgnore
    public String getIncomingRepositoryUrl() {
        return getRepositoryUrl(Rdf4jService.IN_STACK_REPO_ID);
    }

    @JsonIgnore
    public String getOutgoingRepositoryUrl() {
        return getRepositoryUrl(Rdf4jService.OUT_STACK_REPO_ID);
    }

}