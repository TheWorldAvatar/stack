package com.cmclinnovations.stack.services;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.docker.DockerClient;
import com.cmclinnovations.stack.clients.utils.FileUtils;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.ContainerSpecConfig;
import com.github.dockerjava.api.model.ContainerSpecFile;
import com.github.dockerjava.api.model.ServiceSpec;

public class TraefikService extends ContainerService implements ReverseProxyService {

    public static final String TYPE = "traefik";

    private static final String TRAEFIK_CONFIG_NAME = "traefik_config";
    private static final String TRAEFIK_CONFIG_PATH = "/etc/traefik/traefik.yml";
    private static final String TRAEFIK_CONFIG_TEMPLATE = "traefik/configs/traefik.yml";

    // Keycloak authentication configuration
    private static final String KEYCLOAK_AUTH_ENABLED = "KEYCLOAK_AUTH_ENABLED";
    private static final String KEYCLOAK_AUTH_URL = "KEYCLOAK_AUTH_URL";
    private static final String KEYCLOAK_REALM = "KEYCLOAK_REALM";
    private static final String AUTH_MIDDLEWARE_NAME = "keycloak-auth";

    public TraefikService(String stackName, ServiceConfig config) {
        super(stackName, config);
        updateExternalPort(config);
    }

    @Override
    protected void doPreStartUpConfiguration() {
        try (InputStream inStream = new BufferedInputStream(
                TraefikService.class.getResourceAsStream(TRAEFIK_CONFIG_TEMPLATE))) {

            String configContent = new String(inStream.readAllBytes(), StandardCharsets.UTF_8);
            // Replace the ${STACK_NAME} placeholder with actual stack name
            String stackName = getEnvironmentVariable(StackClient.STACK_NAME_KEY);

            configContent = configContent.replace("${STACK_NAME}", stackName);

            // Create Docker Config for Traefik
            DockerClient dockerClient = DockerClient.getInstance();
            if (!dockerClient.configExists(TRAEFIK_CONFIG_NAME)) {
                dockerClient.addConfig(TRAEFIK_CONFIG_NAME, configContent.getBytes(StandardCharsets.UTF_8));
            }

            // Mount the config into the container
            ContainerSpec containerSpec = getContainerSpec();
            List<ContainerSpecConfig> configs = containerSpec.getConfigs();
            if (null == configs) {
                configs = new ArrayList<>();
                containerSpec.withConfigs(configs);
            }

            ContainerSpecConfig traefikConfig = new ContainerSpecConfig()
                    .withConfigName(TRAEFIK_CONFIG_NAME)
                    .withFile(new ContainerSpecFile()
                            .withName(TRAEFIK_CONFIG_PATH)
                            .withUid("0")
                            .withGid("0")
                            .withMode(0444L));
            configs.add(traefikConfig);

        } catch (IOException ex) {
            throw new RuntimeException("Failed to configure Traefik", ex);
        }
    }

    @Override
    public void addStackServiceToReverseProxy(ContainerService service) {
        // Traefik's Swarm provider reads service-level labels, not container labels
        ServiceSpec serviceSpec = service.getServiceSpec();
        Map<String, String> existingLabels = serviceSpec.getLabels();
        final Map<String, String> labels = (existingLabels != null) ? existingLabels : new HashMap<>();
        labels.put("traefik.enable", "true");

        // Check if Keycloak authentication is enabled globally
        boolean authEnabled = isKeycloakAuthEnabled();
        String authMiddleware = authEnabled ? AUTH_MIDDLEWARE_NAME : null;

        service.getConfig().getEndpoints().forEach((endpointName, connection) -> {
            URI externalPath = connection.getExternalPath();
            if (null != externalPath) {
                String serviceName = service.getContainerName();
                String routerName = serviceName + "_" + endpointName;
                String pathPrefix = FileUtils.fixSlashes(externalPath.getPath(), true, false);

                // Configure router with path prefix rule
                labels.put("traefik.http.routers." + routerName + ".rule",
                        "PathPrefix(`" + pathPrefix + "`)");
                labels.put("traefik.http.routers." + routerName + ".entrypoints", "web");

                // Add authentication middleware if enabled
                if (authMiddleware != null) {
                    labels.put("traefik.http.routers." + routerName + ".middlewares", authMiddleware);
                }

                // Configure service with the internal port
                int port = getPortOrDefault(connection.getUrl());
                labels.put("traefik.http.routers." + routerName + ".service", routerName);
                labels.put("traefik.http.services." + routerName + ".loadbalancer.server.port",
                        String.valueOf(port));
            }
        });

        // If auth is enabled, configure the ForwardAuth middleware globally for this
        // Traefik instance
        if (authEnabled) {
            configureKeycloakAuthMiddleware(labels);
        }

        // Set labels on the service spec after they've been populated
        serviceSpec.withLabels(labels);
    }

    /**
     * Checks if Keycloak authentication is enabled via environment variable.
     */
    private boolean isKeycloakAuthEnabled() {
        String enabled = System.getenv(KEYCLOAK_AUTH_ENABLED);
        return "true".equalsIgnoreCase(enabled);
    }

    /**
     * Configures the Keycloak ForwardAuth middleware on the Traefik service.
     * This middleware will be applied to all routers that reference it.
     */
    private void configureKeycloakAuthMiddleware(Map<String, String> labels) {
        String authUrl = System.getenv(KEYCLOAK_AUTH_URL);
        String realm = System.getenv(KEYCLOAK_REALM);

        if (authUrl == null || realm == null) {
            throw new RuntimeException(
                    "KEYCLOAK_AUTH_ENABLED is true but KEYCLOAK_AUTH_URL or KEYCLOAK_REALM is not set. " +
                            "Please configure these environment variables.");
        }

        // Construct the Keycloak userinfo endpoint URL
        // This endpoint validates bearer tokens and returns 200 for valid tokens, 401
        // for invalid
        String userinfoEndpoint = authUrl.replaceAll("/+$", "") + "/realms/" + realm
                + "/protocol/openid-connect/userinfo";

        // Configure ForwardAuth middleware
        labels.put("traefik.http.middlewares." + AUTH_MIDDLEWARE_NAME + ".forwardauth.address", userinfoEndpoint);

        // Forward the Authorization header to Keycloak
        labels.put("traefik.http.middlewares." + AUTH_MIDDLEWARE_NAME + ".forwardauth.authResponseHeaders",
                "Authorization");

        // Trust forwarded headers
        labels.put("traefik.http.middlewares." + AUTH_MIDDLEWARE_NAME + ".forwardauth.trustForwardHeader", "true");
    }

}
