package com.cmclinnovations.stack.services;

import java.util.HashMap;
import java.util.Map;

import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.utils.FileUtils;
import com.cmclinnovations.stack.services.config.Connection;
import com.cmclinnovations.stack.services.config.ServiceConfig;

public class TraefikService extends ContainerService implements ReverseProxyService {

    public TraefikService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    public void addService(ContainerService service) {
        Map<String, String> labels = service.getContainerSpec().getLabels();
        if (null == labels) {
            labels = new HashMap<>();
            service.getContainerSpec().withLabels(labels);
        }
        labels.put("traefik.enable", "true");

        service.getConfig().getEndpoints().forEach((name,connection) -> {
            // TODO: Set the labels correctly
            // labels.put("traefik.http.routers." + service.getContainerName() + ".rule",
            //         "PathPrefix(`" + FileUtils.fixSlashes(connection.getExternalPath().getPath(), true, false) + "`)");
            // labels.put(
            //         "traefik.http.services." + service.getServiceName() + ".loadbalancer.server.port",
            //         String.valueOf(connection.getInternalPort()));
        });
    }

}
