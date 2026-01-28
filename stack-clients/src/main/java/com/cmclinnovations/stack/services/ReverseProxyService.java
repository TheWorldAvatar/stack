package com.cmclinnovations.stack.services;

import java.net.URL;
import java.util.List;

import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.github.dockerjava.api.model.EndpointSpec;
import com.github.dockerjava.api.model.PortConfig;

public interface ReverseProxyService extends Service {

    public void addStackServiceToReverseProxy(ContainerService service);

    /**
     * Updates the external port mapping for the reverse proxy service.
     * This allows multiple stacks to run on the same host by exposing each stack's
     * reverse proxy on a different external port.
     * 
     * @param config The service configuration containing the endpoint
     *               specifications
     */
    default void updateExternalPort(ServiceConfig config) {
        String externalPort = System.getenv("EXTERNAL_PORT");
        if (null != externalPort) {
            EndpointSpec endpointSpec = config.getDockerServiceSpec().getEndpointSpec();
            if (null != endpointSpec) {
                List<PortConfig> ports = endpointSpec.getPorts();
                if (null != ports) {
                    ports.stream()
                            .filter(port -> port.getTargetPort() == 80)
                            .forEach(port -> port.withPublishedPort(Integer.parseInt(externalPort)));
                }
            }
        }
    }

    /**
     * Gets the port from a URL, defaulting to 80 if not specified.
     * This is a common pattern when working with HTTP services that don't
     * explicitly specify a port.
     * 
     * @param url The URL to extract the port from
     * @return The port number, or 80 if the URL doesn't specify a port (-1)
     */
    default int getPortOrDefault(URL url) {
        int port = url.getPort();
        return (port == -1) ? 80 : port;
    }
}
