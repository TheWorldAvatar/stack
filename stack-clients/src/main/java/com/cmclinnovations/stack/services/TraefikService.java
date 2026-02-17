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

    private static final String TRAEFIK_DYNAMIC_CONFIG_NAME = "traefik_dynamic_config";
    private static final String TRAEFIK_DYNAMIC_CONFIG_PATH = "/etc/traefik/dynamic.yml";
    private static final String TRAEFIK_DYNAMIC_CONFIG_TEMPLATE = "traefik/configs/dynamic.yml";

    // Forward authentication middleware name (defined by the forwardauth service)
    private static final String AUTH_ENABLED = "AUTH_ENABLED";
    private static final String AUTH_MIDDLEWARE_NAME = "oauth-auth-redirect";

    public TraefikService(String stackName, ServiceConfig config) {
        super(stackName, config);
        updateExternalPort(config);
    }

    @Override
    protected void doPreStartUpConfiguration() {
        DockerClient dockerClient = DockerClient.getInstance();
        ContainerSpec containerSpec = getContainerSpec();
        List<ContainerSpecConfig> configs = containerSpec.getConfigs();
        if (null == configs) {
            configs = new ArrayList<>();
            containerSpec.withConfigs(configs);
        }

        String stackName = getEnvironmentVariable(StackClient.STACK_NAME_KEY);

        // Create and mount static Traefik configuration
        configureTraefikStaticConfig(dockerClient, configs, stackName);

        // Create and mount dynamic Traefik configuration (for middlewares / custom
        // rules and routers etc)
        configureTraefikDynamicConfig(dockerClient, configs);
    }

    private void configureTraefikDynamicConfig(DockerClient dockerClient, List<ContainerSpecConfig> configs) {
        if (isAuthEnabled()) {
            try (InputStream inStream = new BufferedInputStream(
                    TraefikService.class.getResourceAsStream(TRAEFIK_DYNAMIC_CONFIG_TEMPLATE))) {

                String dynamicConfigContent = new String(inStream.readAllBytes(), StandardCharsets.UTF_8);

                if (!dockerClient.configExists(TRAEFIK_DYNAMIC_CONFIG_NAME)) {
                    dockerClient.addConfig(TRAEFIK_DYNAMIC_CONFIG_NAME,
                            dynamicConfigContent.getBytes(StandardCharsets.UTF_8));
                }

                ContainerSpecConfig dynamicConfig = new ContainerSpecConfig()
                        .withConfigName(TRAEFIK_DYNAMIC_CONFIG_NAME)
                        .withFile(new ContainerSpecFile()
                                .withName(TRAEFIK_DYNAMIC_CONFIG_PATH)
                                .withUid("0")
                                .withGid("0")
                                .withMode(0444L));
                configs.add(dynamicConfig);

            } catch (IOException ex) {
                throw new RuntimeException("Failed to configure Traefik dynamic config", ex);
            }
        }
    }

    private void configureTraefikStaticConfig(DockerClient dockerClient, List<ContainerSpecConfig> configs,
            String stackName) {
        try (InputStream inStream = new BufferedInputStream(
                TraefikService.class.getResourceAsStream(TRAEFIK_CONFIG_TEMPLATE))) {

            String configContent = new String(inStream.readAllBytes(), StandardCharsets.UTF_8);
            configContent = configContent.replace("${STACK_NAME}", stackName);

            if (!dockerClient.configExists(TRAEFIK_CONFIG_NAME)) {
                dockerClient.addConfig(TRAEFIK_CONFIG_NAME, configContent.getBytes(StandardCharsets.UTF_8));
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
            throw new RuntimeException("Failed to configure Traefik static config", ex);
        }
    }

    @Override
    public void addStackServiceToReverseProxy(ContainerService service) {
        // Traefik's Swarm provider reads service-level labels, not container labels
        ServiceSpec serviceSpec = service.getServiceSpec();
        Map<String, String> existingLabels = serviceSpec.getLabels();
        final Map<String, String> labels = (existingLabels != null) ? existingLabels : new HashMap<>();

        // Check if authentication is enabled globally
        // The middleware is defined in Traefik's dynamic config (file provider)
        boolean authEnabled = isAuthEnabled();
        String authMiddleware = authEnabled ? AUTH_MIDDLEWARE_NAME + "@file" : null;

        // Track if any endpoints with external paths were found
        final boolean[] hasExternalEndpoints = { false };

        service.getConfig().getEndpoints().forEach((endpointName, connection) -> {
            URI externalPath = connection.getExternalPath();
            if (null != externalPath) {
                hasExternalEndpoints[0] = true;

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

        // Only enable Traefik for services that have external endpoints
        if (hasExternalEndpoints[0]) {
            labels.put("traefik.enable", "true");

            // Note: The traefik-forward-auth middleware is defined by the forwardauth
            // service
            // Services that need authentication simply reference this middleware in their
            // router config
        }

        // Set labels on the service spec after they've been populated
        serviceSpec.withLabels(labels);
    }

    /**
     * Checks if authentication is enabled via environment variable.
     * When enabled, services will use the forwardauth middleware
     * that is defined in Traefik's dynamic configuration (file provider).
     */
    private boolean isAuthEnabled() {
        String enabled = System.getenv(AUTH_ENABLED);
        return "true".equalsIgnoreCase(enabled);
    }

}
