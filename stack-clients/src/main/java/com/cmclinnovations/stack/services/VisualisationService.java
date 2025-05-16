package com.cmclinnovations.stack.services;

import com.cmclinnovations.stack.clients.core.PasswordEndpointConfig;
import com.cmclinnovations.stack.services.config.ServiceConfig;

public class VisualisationService extends ContainerService {

    public static final String TYPE = "visualisation";

    public VisualisationService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    protected void doPreStartUpConfiguration() {
        ensureOptionalSecret("mapbox_username");
        ensureOptionalSecret("mapbox_api_key");

        String sessionSecret = new PasswordEndpointConfig("viz_session_secret", "/run/secrets/viz_session_secret")
                .getPassword();
        setEnvironmentVariableIfAbsent("SESSION_SECRET", sessionSecret);
    }

}
