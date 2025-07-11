package com.cmclinnovations.stack.services;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.docker.PodmanClient;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.ContainerSpecConfig;
import com.github.dockerjava.api.model.ContainerSpecFile;
import com.github.dockerjava.api.model.ContainerSpecSecret;
import com.github.dockerjava.api.model.EndpointSpec;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.NetworkAttachmentConfig;
import com.github.dockerjava.api.model.PortConfig;
import com.github.dockerjava.api.model.PortConfigProtocol;
import com.github.dockerjava.api.model.ServiceRestartCondition;
import com.github.dockerjava.api.model.ServiceRestartPolicy;
import com.github.dockerjava.api.model.ServiceSpec;

import io.theworldavatar.swagger.podman.ApiException;
import io.theworldavatar.swagger.podman.api.ContainersApi;
import io.theworldavatar.swagger.podman.api.ImagesApi;
import io.theworldavatar.swagger.podman.api.PodsApi;
import io.theworldavatar.swagger.podman.api.SecretsApi;
import io.theworldavatar.swagger.podman.model.ContainerCreateResponse;
import io.theworldavatar.swagger.podman.model.IDResponse;
import io.theworldavatar.swagger.podman.model.ImageData;
import io.theworldavatar.swagger.podman.model.ListContainer;
import io.theworldavatar.swagger.podman.model.ListPodsReport;
import io.theworldavatar.swagger.podman.model.MountPoint;
import io.theworldavatar.swagger.podman.model.NamedVolume;
import io.theworldavatar.swagger.podman.model.Namespace;
import io.theworldavatar.swagger.podman.model.PerNetworkOptions;
import io.theworldavatar.swagger.podman.model.PodSpecGenerator;
import io.theworldavatar.swagger.podman.model.PortMapping;
import io.theworldavatar.swagger.podman.model.Schema2HealthConfig;
import io.theworldavatar.swagger.podman.model.Secret;
import io.theworldavatar.swagger.podman.model.SecretInfoReport;
import io.theworldavatar.swagger.podman.model.SpecGenerator;

public class PodmanService extends DockerService {

    public static final String TYPE = "podman";

    public static final Map<ServiceRestartCondition, String> restartPolicyMap = Map.of(
            ServiceRestartCondition.ANY, "always",
            ServiceRestartCondition.NONE, "no",
            ServiceRestartCondition.ON_FAILURE, "on-failure");

    public PodmanService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    public PodmanClient initClient(URI dockerUri) {
        return new PodmanClient(dockerUri);
    }

    @Override
    public PodmanClient getClient() {
        return (PodmanClient) super.getClient();
    }

    @Override
    public void initialise() {

        addStackSecrets();
    }

    @Override
    public void addStackSecrets() {
        SecretsApi secretsApi = new SecretsApi(getClient().getPodmanClient());
        try {
            String stackName = StackClient.getStackName();
            List<SecretInfoReport> existingStackSecrets = secretsApi
                    .secretListLibpod(
                            URLEncoder.encode("{\"name\":[\"^" + stackName + "_\"]}", StandardCharsets.UTF_8));

            for (File secretFile : Path.of("/run/secrets").toFile()
                    .listFiles(file -> file.isFile() && !file.getName().startsWith(".git"))) {
                try (Stream<String> lines = Files.lines(secretFile.toPath())) {
                    String data = lines.collect(Collectors.joining("\n"));
                    String secretName = secretFile.getName();

                    String fullSecretName = StackClient.prependStackName(secretName);
                    Optional<SecretInfoReport> currentSecret = existingStackSecrets.stream()
                            .filter(secret -> secret.getSpec().getName().equals(fullSecretName))
                            .findFirst();
                    if (currentSecret.isEmpty()) {
                        getClient().addSecret(secretName, data);
                    } else {
                        existingStackSecrets.remove(currentSecret.get());
                    }
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to load secret file '" + secretFile.getAbsolutePath() + "'.",
                            ex);
                }
            }

            for (SecretInfoReport oldSecret : existingStackSecrets) {
                secretsApi.secretDeleteLibpod(oldSecret.getID(), null);
            }
        } catch (ApiException ex) {
            throw new RuntimeException("Failed to update secrets.", ex);
        }
    }

    private String getPodName(String containerName) {
        return containerName + "_pod";
    }

    @Override
    protected void addStackConfigs() {

        try {
            Files.walkFileTree(Path.of("/inputs/config"), new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        if (Files.isReadable(file) && !file.getFileName().toString().startsWith(".git")) {
                            String configName = file.getFileName().toString();

                            try (Stream<String> lines = Files.lines(file)) {
                                String data = lines.collect(Collectors.joining("\n"));
                                dockerClient.addConfig(configName, data);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    } catch (IOException ex) {
                        throw new IOException("Failed to load config file '" + file + "'.", ex);
                    }
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load configs.", ex);
        }
    }

    @Override
    protected Optional<Container> configureContainerWrapper(ContainerService service) {
        Optional<Container> container;
        removeService(service);

        container = startPod(service);
        return container;
    }

    private Optional<ListPodsReport> getPod(String containerName) {
        String podName = getPodName(containerName);
        try {
            return new PodsApi(getClient().getPodmanClient()).podListLibpod(
                    URLEncoder.encode("{\"name\":[\"" + podName + "\"]}", StandardCharsets.UTF_8))
                    .stream().findAny();
        } catch (ApiException ex) {
            throw new RuntimeException("Failed to retrieve Pod '" + podName + "'.", ex);
        }
    }

    @Override
    void removeService(String serviceName) {
        Optional<ListPodsReport> pod = getPod(serviceName);

        if (pod.isPresent()) {
            try {
                new PodsApi(getClient().getPodmanClient()).podDeleteLibpod(pod.get().getId(), true);
            } catch (ApiException ex) {
                throw new RuntimeException("Failed to remove Pod '" + pod.get().getName() + "'.", ex);
            }
        }
    }

    private Optional<Container> startPod(ContainerService service) {

        ServiceSpec serviceSpec = configureServiceSpec(service);

        String containerName = serviceSpec.getName();
        ContainerSpec containerSpec = serviceSpec.getTaskTemplate().getContainerSpec();

        ImageData imageConfig;
        try {
            imageConfig = new ImagesApi(getClient()
                    .getPodmanClient()).imageInspectLibpod(containerSpec.getImage());
        } catch (ApiException e) {
            throw new RuntimeException("Failed to retrieve image info '" + containerSpec.getImage() + "'.", e);
        }

        PodSpecGenerator podSpecGenerator = new PodSpecGenerator()
                .name(getPodName(containerName))
                .hostname(containerName);
        EndpointSpec endpointSpec = serviceSpec.getEndpointSpec();
        if (null != endpointSpec) {
            List<PortConfig> ports = endpointSpec.getPorts();
            if (null != ports) {
                List<PortMapping> portMappings = ports.stream()
                        .map(port -> {
                            PortConfigProtocol protocol = port.getProtocol();
                            return new PortMapping()
                                    .containerPort(port.getTargetPort())
                                    .hostPort(port.getPublishedPort())
                                    .protocol(null == protocol ? null : protocol.name());
                        })
                        .collect(Collectors.toList());
                podSpecGenerator.portmappings(portMappings);
            }
        }
        List<NetworkAttachmentConfig> networks = serviceSpec.getTaskTemplate().getNetworks();
        if (null != networks) {
            podSpecGenerator.setNetns(new Namespace().nsmode("bridge"));
            podSpecGenerator.setNetworks(
                    networks.stream().collect(
                            Collectors.toMap(NetworkAttachmentConfig::getTarget,
                                    network -> {
                                        PerNetworkOptions perNetworkOptions = new PerNetworkOptions();
                                        perNetworkOptions.setAliases(List.of(containerName));
                                        return perNetworkOptions;
                                    })));
        }

        // Disable SELinux, this is required to bind mount files and sockets
        podSpecGenerator.addSecurityOptItem("label=disable");

        try {
            PodsApi podsApi = new PodsApi(getClient().getPodmanClient());
            IDResponse podIDResponse = podsApi.podCreateLibpod(podSpecGenerator);

            SpecGenerator containerSpecGenerator = new SpecGenerator();

            containerSpecGenerator.setName(containerName);
            containerSpecGenerator.setPod(podIDResponse.getId());
            containerSpecGenerator.setImage(containerSpec.getImage());
            containerSpecGenerator.setEnv(service.getConfig().getEnvironment());
            containerSpecGenerator.setEntrypoint(containerSpec.getCommand());
            List<ContainerSpecSecret> secrets = containerSpec.getSecrets();
            if (null != secrets) {
                containerSpecGenerator.setSecrets(secrets.stream()
                        .map(dockerSecret -> {
                            Secret secret = new Secret().source(dockerSecret.getSecretName());
                            ContainerSpecFile file = dockerSecret.getFile();
                            if (null != file) {
                                secret.target(file.getName())
                                        .GID(Integer.parseInt(file.getGid()))
                                        .UID(Integer.parseInt(file.getUid()))
                                        .mode(file.getMode().intValue());
                            }
                            return secret;
                        })
                        .collect(Collectors.toList()));
            }
            List<ContainerSpecConfig> configs = containerSpec.getConfigs();
            if (null != configs) {
                configs.forEach(dockerConfig -> {
                    Secret config = new Secret().source(dockerConfig.getConfigName());
                    ContainerSpecFile file = dockerConfig.getFile();
                    if (null != file) {
                        Long mode = file.getMode();
                        config.target("/" + file.getName())
                                .mode(mode == null ? null : Math.toIntExact(mode));
                    }
                    containerSpecGenerator.addSecretsItem(config);
                });
            }
            List<com.github.dockerjava.api.model.Mount> dockerMounts = containerSpec.getMounts();
            if (null != dockerMounts) {
                /*
                 * TODO: This is roughly how this should be done but there is an issue with the
                 * Podman Swagger spec as described here
                 * https://github.com/containers/podman/issues/13717
                 * and here https://github.com/containers/podman/issues/13092
                 * I've bodged the Swagger API so that it uses the slightly more appropriate
                 * MountPoint class (it has "destination" rather than "target"),
                 * this class doesn't have all of the correct fields though.
                 * The correct class is defined here:
                 * https://github.com/opencontainers/runtime-spec/blob/main/specs-go/config.go#
                 * L112-L127
                 */

                containerSpecGenerator.setMounts(dockerMounts.stream()
                        .filter(dockerMount -> dockerMount.getType() != MountType.VOLUME)
                        .map(dockerMount -> new MountPoint()
                                .source(dockerMount.getSource())
                                .destination(dockerMount.getTarget())
                                .type(dockerMount.getType().name().toLowerCase()))
                        .collect(Collectors.toList()));

                // This is a temporary workaround for named volumes
                containerSpecGenerator.setVolumes(dockerMounts.stream()
                        .filter(dockerMount -> dockerMount.getType() == MountType.VOLUME)
                        .map(dockerMount -> new NamedVolume()
                                .name(dockerMount.getSource())
                                .dest(dockerMount.getTarget()))
                        .collect(Collectors.toList()));
            }
            containerSpecGenerator.setLabels(containerSpec.getLabels());

            // Copy across the restart policy
            ServiceRestartPolicy restartPolicy = serviceSpec.getTaskTemplate().getRestartPolicy();
            containerSpecGenerator.setRestartPolicy(restartPolicyMap.get(restartPolicy.getCondition()));
            containerSpecGenerator.setRestartTries((Integer) restartPolicy.getMaxAttempts().intValue());

            // Copy across any user specified health check
            HealthCheck healthCheck = containerSpec.getHealthCheck();
            if (healthCheck != null) {
                Schema2HealthConfig healthConfig = (null != imageConfig && null != imageConfig.getHealthcheck())
                        ? imageConfig.getHealthcheck()
                        : new Schema2HealthConfig()
                                .test(healthCheck.getTest())
                                .startPeriod(healthCheck.getStartPeriod())
                                // TODO: Podman only supports "startInterval" from v5, which we can't use yet.
                                // .startInterval(healthCheck.getStartInterval())
                                .interval(healthCheck.getInterval())
                                .timeout(healthCheck.getTimeout())
                                .retries(Optional.ofNullable(healthCheck.getRetries()).map(Integer::longValue)
                                        .orElse(null));

                containerSpecGenerator.setHealthconfig(healthConfig);
            }

            try {
                ContainersApi containersApi = new ContainersApi(getClient().getPodmanClient());
                ContainerCreateResponse containerCreateResponse = containersApi
                        .containerCreateLibpod(containerSpecGenerator);

                containersApi.containerStartLibpod(containerName, null);

                return getContainerIfCreated(service.getContainerName());
            } catch (ApiException ex) {
                throw new RuntimeException("Failed to create Podman Container '" + containerName + "''.", ex);
            }
        } catch (ApiException ex) {
            throw new RuntimeException("Failed to create Podman Pod '" + containerName + "''.", ex);
        }
    }

    @Override
    protected Optional<Container> getContainerIfCreated(String containerName) {

        Optional<ListContainer> potentialContainer;
        while (true) {
            try {
                potentialContainer = new ContainersApi(getClient().getPodmanClient())
                        .containerListLibpod(true, 1, null, null, null, null,
                                URLEncoder.encode("{\"name\":[\"" + containerName + "\"],\"pod\":[\""
                                        + getPodName(containerName) + "\"]}", StandardCharsets.UTF_8))
                        .stream().findFirst();
            } catch (ApiException ex) {
                throw new RuntimeException("Failed to retrieve state of Container '" + containerName + "'.", ex);
            }

            if (!potentialContainer.isEmpty()) {
                ListContainer container = potentialContainer.get();
                String state = container.getState();
                String status = container.getStatus();
                switch (state) {
                    case "created":
                    case "restarting":
                        break;
                    case "running":
                        if (!(status.isEmpty() || status.equals("healthy"))) {
                            break;
                        }
                    case "exited":
                        return getContainerFromID(container.getId());
                    case "removing":
                    case "paused":
                    case "dead":
                    default:
                        throw new RuntimeException("Failed to start container '" + containerName
                                + "'.\nState is: '" + state + "'\nStatus is: '" + status + "'");
                }
            }
        }
    }

}
