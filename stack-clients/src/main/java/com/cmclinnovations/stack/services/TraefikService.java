package com.cmclinnovations.stack.services;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
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

    private static final String EXTERNAL_PORT = "EXTERNAL_PORT";

    public static final String TYPE = "traefik";

    private static final String TRAEFIK_CONFIG_NAME = "traefik_config";
    private static final String TRAEFIK_CONFIG_PATH = "/etc/traefik/traefik.yml";
    private static final String TRAEFIK_CONFIG_TEMPLATE = "traefik/configs/traefik.yml";

    public TraefikService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    protected void doPreStartUpConfiguration() {
        try (InputStream inStream = new BufferedInputStream(
                TraefikService.class.getResourceAsStream(TRAEFIK_CONFIG_TEMPLATE))) {

            String configContent = new String(inStream.readAllBytes(), StandardCharsets.UTF_8);
            // Replace the ${STACK_NAME} placeholder with actual stack name
            String stackName = getEnvironmentVariable(StackClient.STACK_NAME_KEY);
            String stackPortString = System.getenv(EXTERNAL_PORT);

            configContent = configContent.replace("${STACK_NAME}", stackName);
            configContent = configContent.replace("${STACK_PORT}", stackPortString);

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

                // Configure service with the internal port
                URL url = connection.getUrl();
                int port = url.getPort();
                if (port == -1) {
                    port = 80; // Default port
                }
                labels.put("traefik.http.routers." + routerName + ".service", routerName);
                labels.put("traefik.http.services." + routerName + ".loadbalancer.server.port",
                        String.valueOf(port));
            }

            // TODO: Set the labels correctly
            // labels.put("traefik.http.routers." + service.getContainerName() + ".rule",
            // "PathPrefix(`" + FileUtils.fixSlashes(connection.getExternalPath().getPath(),
            // true, false) + "`)");
            // labels.put(
            // "traefik.http.services." + service.getServiceName() +
            // ".loadbalancer.server.port",
            // String.valueOf(connection.getInternalPort()));
        });

        // Set labels on the service spec after they've been populated
        serviceSpec.withLabels(labels);
    }

}
