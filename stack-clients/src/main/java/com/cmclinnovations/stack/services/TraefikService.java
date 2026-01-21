package com.cmclinnovations.stack.services;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.utils.FileUtils;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.github.dockerjava.api.model.ContainerSpec;

public class TraefikService extends ContainerService implements ReverseProxyService {

    public static final String TYPE = "traefik";

    private static final String TRAEFIK_CONF_DIR = "/etc/traefik/";
    private static final String TRAEFIK_CONFIG_TEMPLATE = "traefik/configs/traefik.yml";

    public TraefikService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    public void doFirstTimePostStartUpConfiguration() {
        try (InputStream inStream = new BufferedInputStream(
                TraefikService.class.getResourceAsStream(TRAEFIK_CONFIG_TEMPLATE))) {

            String configContent = new String(inStream.readAllBytes(), StandardCharsets.UTF_8);
            // Replace the ${STACK_NAME} placeholder with actual stack name
            String stackName = getEnvironmentVariable(StackClient.STACK_NAME_KEY);
            configContent = configContent.replace("${STACK_NAME}", stackName);

            // Write the configuration file to the container
            Map<String, byte[]> files = new HashMap<>();
            files.put("traefik.yml", configContent.getBytes(StandardCharsets.UTF_8));
            sendFilesContent(files, TRAEFIK_CONF_DIR);

        } catch (IOException ex) {
            throw new RuntimeException("Failed to configure Traefik", ex);
        }
    }

    @Override
    public void addService(ContainerService service) {
        ContainerSpec containerSpec = service.getContainerSpec();
        Map<String, String> labels = new HashMap<>();
        containerSpec.withLabels(labels);
        labels.put("traefik.enable", "true");

        service.getConfig().getEndpoints().forEach((endpointName, connection) -> {
            URI externalPath = connection.getExternalPath();
            if (null != externalPath) {
                String serviceName = service.getName();
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
    }

}
