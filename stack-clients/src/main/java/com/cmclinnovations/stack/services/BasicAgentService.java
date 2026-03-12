package com.cmclinnovations.stack.services;

import java.util.ArrayList;
import java.util.List;

import com.cmclinnovations.stack.clients.core.BasicEndpointConfig;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;

public class BasicAgentService extends ContainerService {

    public static final String TYPE = "basic-agent";

    // External path to socket on host
    private static final String API_SOCK = "API_SOCK";
    // Internal path to socket in containers
    private static final String DOCKER_SOCKET_PATH = "/var/run/docker.sock";

    public BasicAgentService(String stackName, ServiceConfig config) {
        super(stackName, config);

        String name = this.getName();
        config.getEndpoints().entrySet().forEach(entry -> {
            String endpointConfigName = StackClient.removeStackName(name) + "-" + entry.getKey();
            String url = entry.getValue().getUrl().toString().replace("localhost", name);
            BasicEndpointConfig endpointConfig = new BasicEndpointConfig(endpointConfigName, url);

            addEndpointConfig(endpointConfig);
        });

        ContainerSpec containerSpec = config.getContainerSpec();
        setDockerAPIEnvironmentVariables(containerSpec);
        addDockerAPIMount(containerSpec);
    }

    protected void setDockerAPIEnvironmentVariables(ContainerSpec containerSpec) {
        List<String> env = new ArrayList<>(containerSpec.getEnv());
        env.add(API_SOCK + "=" + System.getenv(API_SOCK));
        env.add(StackClient.EXECUTABLE_KEY + "=" + System.getenv(StackClient.EXECUTABLE_KEY));
        containerSpec.withEnv(env);
    }

    private void addDockerAPIMount(ContainerSpec containerSpec) {
        List<Mount> mounts = containerSpec.getMounts();

        // Ensure that "mounts" is not "null"
        if (null == mounts) {
            mounts = new ArrayList<>();
            containerSpec.withMounts(mounts);
        }

        // Add the Docker API socket as a bind mount
        // This is required for a container to make Docker API calls
        Mount dockerSocketMount = new Mount()
                .withType(MountType.BIND)
                .withSource(System.getenv(API_SOCK))
                .withTarget(DOCKER_SOCKET_PATH);
        if (!mounts.contains(dockerSocketMount)) {
            mounts.add(dockerSocketMount);
        }
    }

}