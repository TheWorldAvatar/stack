package com.cmclinnovations.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.cmclinnovations.clients.core.StackClient;
import com.cmclinnovations.clients.docker.DockerClient;
import com.cmclinnovations.services.config.ServiceConfig;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InitializeSwarmCmd;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.ListServicesCmd;
import com.github.dockerjava.api.command.ListTasksCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveServiceCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Config;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.ContainerSpecConfig;
import com.github.dockerjava.api.model.ContainerSpecFile;
import com.github.dockerjava.api.model.ContainerSpecSecret;
import com.github.dockerjava.api.model.LocalNodeState;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Secret;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.ServiceRestartCondition;
import com.github.dockerjava.api.model.ServiceRestartPolicy;
import com.github.dockerjava.api.model.ServiceSpec;
import com.github.dockerjava.api.model.SwarmInfo;
import com.github.dockerjava.api.model.SwarmSpec;
import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.api.model.TaskState;
import com.github.dockerjava.api.model.TaskStatus;

public final class DockerService extends AbstractService {

    public static final String TYPE = "docker";

    private final DockerClient dockerClient;

    private Network network;

    public DockerService(String stackName, ServiceManager serviceManager, ServiceConfig config) {
        super(serviceManager, config);

        dockerClient = new DockerClient(getEndpoint("dockerHost").getUri());

        startDockerSwarm();

        addStackSecrets();

        addStackConfigs();

        createNetwork(stackName);
    }

    private void startDockerSwarm() {
        try (InfoCmd infoCmd = dockerClient.getInternalClient().infoCmd()) {

            SwarmInfo swarmInfo = infoCmd.exec().getSwarm();
            if (null == swarmInfo) {
                throw new RuntimeException("SwarmInfo returned by Docker infoCMD is 'null'.");
            }

            LocalNodeState nodeState = swarmInfo.getLocalNodeState();
            if (null == nodeState) {
                throw new RuntimeException("LocalNodeState returned by Docker infoCMD is 'null'.");
            }

            switch (nodeState) {
                case INACTIVE:
                case PENDING:
                    try (InitializeSwarmCmd initializeSwarmCmd = dockerClient.getInternalClient()
                            .initializeSwarmCmd(new SwarmSpec())) {
                        initializeSwarmCmd.exec();
                    }
                    break;
                case ACTIVE:
                    break;
                default:
                    throw new IllegalStateException("Docker swarm is in a bad state '" + nodeState + "'.");
            }
        }
    }

    private void addStackConfigs() {
        List<Config> existingStackConfigs = dockerClient.getConfigs();

        try {
            Files.walkFileTree(Path.of("/inputs/config"), new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        if (Files.isReadable(file) && !file.getFileName().toString().startsWith(".git")) {
                            String configName = file.getFileName().toString();
                            Optional<Config> currentConfig = dockerClient.getConfig(existingStackConfigs, configName);
                            if (currentConfig.isEmpty()) {
                                try (Stream<String> lines = Files.lines(file)) {
                                    String data = lines.collect(Collectors.joining("\n"));
                                    dockerClient.addConfig(configName, data);
                                }
                            } else {
                                existingStackConfigs.remove(currentConfig.get());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    } catch (IOException ex) {
                        throw new IOException("Failed to load config file '" + file + "'.", ex);
                    }
                }
            });
            for (Config oldConfig : existingStackConfigs) {
                dockerClient.removeConfig(oldConfig);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load configs.", ex);
        }
    }

    public void addStackSecrets() {
        List<Secret> existingStackSecrets = dockerClient.getSecrets();

        for (File secretFile : Path.of("/run/secrets").toFile()
                .listFiles(file -> file.isFile() && !file.getName().startsWith(".git"))) {
            try (Stream<String> lines = Files.lines(secretFile.toPath())) {
                String data = lines.collect(Collectors.joining("\n"));
                String secretName = secretFile.getName();

                Optional<Secret> currentSecret = dockerClient.getSecret(existingStackSecrets, secretName);
                if (currentSecret.isEmpty()) {
                    dockerClient.addSecret(secretName, data);
                } else {
                    existingStackSecrets.remove(currentSecret.get());
                }
            } catch (IOException ex) {
                throw new RuntimeException("Failed to load secret file '" + secretFile.getAbsolutePath() + "'.",
                        ex);
            }
        }

        for (Secret oldSecret : existingStackSecrets) {
            dockerClient.removeSecret(oldSecret);
        }
    }

    private void createNetwork(String name) {
        Optional<Network> potentialNetwork;
        try (ListNetworksCmd listNetworksCmd = dockerClient.getInternalClient().listNetworksCmd()) {
            potentialNetwork = listNetworksCmd.withNameFilter(name).exec().stream().findAny();
        }
        if (potentialNetwork.isEmpty()) {
            try (CreateNetworkCmd createNetworkCmd = dockerClient.getInternalClient().createNetworkCmd()) {
                createNetworkCmd.withName(name).withAttachable(true).withCheckDuplicate(true).exec();
                try (ListNetworksCmd listNetworksCmd = dockerClient.getInternalClient().listNetworksCmd()) {
                    potentialNetwork = listNetworksCmd.withNameFilter(name).exec().stream().findAny();
                }
            }
        }
        potentialNetwork.ifPresent(nw -> this.network = nw);
    }

    private Optional<Service> getSwarmService(ContainerService service) {
        try (ListServicesCmd listServicesCmd = dockerClient.getInternalClient().listServicesCmd()) {
            return listServicesCmd.withNameFilter(List.of(service.getContainerName()))
                    .exec().stream().findAny();
        }
    }

    private Optional<Container> getContainerFromID(String containerId) {
        try (ListContainersCmd listContainersCmd = dockerClient.getInternalClient().listContainersCmd()) {
            // Setting "showAll" to "true" ensures non-running containers are also returned
            return listContainersCmd.withIdFilter(List.of(containerId))
                    .withShowAll(true).exec()
                    .stream().findAny();
        }
    }

    public void doPreStartUpConfiguration(ContainerService service) {
        service.setDockerClient(dockerClient);
        service.doPreStartUpConfiguration();
    }

    public void doPostStartUpConfiguration(ContainerService service) {
        service.setDockerClient(dockerClient);
        service.doPostStartUpConfiguration();
    }

    public void startContainer(ContainerService service) {

        Optional<Container> container = dockerClient.getContainer(service.getContainerName());

        if (container.isEmpty()) {
            // No container matching that config

            pullImage(service);

            removeSwarmService(service);

            container = startSwarmService(service);
        }

        final String containerId;
        final String containerState;

        if (container.isPresent()) {
            // Get required details of the existing/new container
            containerId = container.get().getId();
            containerState = container.get().getState();
        } else {
            throw new RuntimeException("Failed to start container for service with name '" + service.getName() + "'.");
        }

        switch (containerState) {
            case "running":
                // The container is already running, all is fine.
                break;
            case "created":
            case "exited":
                // The container is not running, start it.
                try (StartContainerCmd startContainerCmd = dockerClient.getInternalClient()
                        .startContainerCmd(containerId)) {
                    startContainerCmd.exec();
                }
                break;
            default:
                // TODO Need to consider actions for other states
                throw new IllegalStateException("Container '" + containerId + "' in a state (" + containerState
                        + ") that is currently unsupported in the DockerService::startContainer method.");
        }

        // Add container to the stack's network, if not already added
        try (InspectNetworkCmd inspectNetworkCmd = dockerClient.getInternalClient().inspectNetworkCmd()) {
            if (null == inspectNetworkCmd.withNetworkId(network.getId()).exec().getContainers().get(containerId)) {
                try (ConnectToNetworkCmd connectToNetworkCmd = dockerClient.getInternalClient().connectToNetworkCmd()) {
                    connectToNetworkCmd.withContainerId(containerId).withNetworkId(network.getId()).exec();
                }
            }
        }

        service.setContainerId(containerId);
    }

    private Optional<Container> startSwarmService(ContainerService service) {

        ServiceSpec serviceSpec = configureServiceSpec(service);

        try (CreateServiceCmd createServiceCmd = dockerClient.getInternalClient().createServiceCmd(serviceSpec)) {
            CreateServiceResponse createServiceResponse = createServiceCmd.exec();

            TaskStatus taskStatus = new TaskStatus();
            TaskState taskState = TaskState.FAILED;
            do {
                try (ListTasksCmd listTasksCmd = dockerClient.getInternalClient().listTasksCmd()) {
                    Optional<Task> task = listTasksCmd.withServiceFilter(service.getContainerName())
                            .exec().stream().findFirst();
                    if (task.isPresent()) {
                        taskStatus = task.get().getStatus();
                        taskState = taskStatus.getState();
                    }
                }
            } while (TaskState.RUNNING.compareTo(taskState) > 0);

            String errMessage = taskStatus.getErr();
            if (null != errMessage) {
                try (RemoveServiceCmd removeServiceCmd = dockerClient.getInternalClient()
                        .removeServiceCmd(createServiceResponse.getId())) {
                    removeServiceCmd.exec();
                }
                throw new RuntimeException("Failed to start service '" + service.getContainerName()
                        + "'. Error message is:\n" + errMessage);
            } else {
                String containerId = taskStatus.getContainerStatus().getContainerID();
                return getContainerFromID(containerId);
            }
        }
    }

    private void removeSwarmService(ContainerService service) {
        Optional<Service> swarmService = getSwarmService(service);

        if (swarmService.isPresent()) {
            try (RemoveServiceCmd removeServiceCmd = dockerClient.getInternalClient()
                    .removeServiceCmd(swarmService.get().getId())) {
                removeServiceCmd.exec();
            }
        }
    }

    private ServiceSpec configureServiceSpec(ContainerService service) {

        ServiceSpec serviceSpec = service.getServiceSpec()
                .withName(service.getContainerName());
        service.getTaskTemplate()
                .withRestartPolicy(new ServiceRestartPolicy().withCondition(ServiceRestartCondition.NONE));
        ContainerSpec containerSpec = service.getContainerSpec()
                .withHostname(service.getName());

        interpolateConfigs(containerSpec);

        interpolateSecrets(containerSpec);

        return serviceSpec;
    }

    private void interpolateConfigs(ContainerSpec containerSpec) {
        List<ContainerSpecConfig> containerSpecConfigs = containerSpec.getConfigs();
        if (null != containerSpecConfigs && !containerSpecConfigs.isEmpty()) {
            List<Config> configs = dockerClient.getConfigs();
            for (ContainerSpecConfig containerSpecConfig : containerSpecConfigs) {
                interpolateConfigFileSpec(containerSpecConfig);
                interpolateConfigId(configs, containerSpecConfig);
                // The stack name needs to be prepended to the name after the file spec is
                // interpolated so that the file name is not modified.
                containerSpecConfig.withConfigName(StackClient.prependStackName(containerSpecConfig.getConfigName()));
            }
        }
    }

    private void interpolateConfigFileSpec(ContainerSpecConfig containerSpecConfig) {
        ContainerSpecFile configFileSpec = containerSpecConfig.getFile();
        if (null == configFileSpec) {
            configFileSpec = new ContainerSpecFile();
            containerSpecConfig.withFile(configFileSpec);
        }
        if (null == configFileSpec.getName()) {
            configFileSpec.withName(containerSpecConfig.getConfigName());
        }
        if (null == configFileSpec.getGid()) {
            configFileSpec.withGid("0");
        }
        if (null == configFileSpec.getUid()) {
            configFileSpec.withUid("0");
        }
        if (null == configFileSpec.getMode()) {
            configFileSpec.withMode(0444l);
        }
    }

    private void interpolateConfigId(List<Config> configs, ContainerSpecConfig containerSpecConfig) {
        if (null == containerSpecConfig.getConfigID()) {
            Optional<String> configID = dockerClient.getConfig(configs, containerSpecConfig.getConfigName())
                    .map(Config::getId);
            if (configID.isPresent()) {
                containerSpecConfig.withConfigID(configID.get());
            } else {
                throw new RuntimeException("Failed to find Config with name '"
                        + containerSpecConfig.getConfigName() + ".");
            }
        }
    }

    private void interpolateSecrets(ContainerSpec containerSpec) {
        List<ContainerSpecSecret> containerSpecSecrets = containerSpec.getSecrets();
        if (null != containerSpecSecrets && !containerSpecSecrets.isEmpty()) {
            List<Secret> secrets = dockerClient.getSecrets();
            for (ContainerSpecSecret containerSpecSecret : containerSpecSecrets) {
                interpolateSecretFileSpec(containerSpecSecret);
                interpolateSecretId(secrets, containerSpecSecret);
                // The stack name needs to be prepended to the name after the file spec is
                // interpolated so that the file name is not modified.
                containerSpecSecret.withSecretName(StackClient.prependStackName(containerSpecSecret.getSecretName()));
            }
        }
    }

    private void interpolateSecretFileSpec(ContainerSpecSecret containerSpecSecret) {
        ContainerSpecFile secretFileSpec = containerSpecSecret.getFile();
        if (null == secretFileSpec) {
            secretFileSpec = new ContainerSpecFile();
            containerSpecSecret.withFile(secretFileSpec);
        }
        if (null == secretFileSpec.getName()) {
            secretFileSpec.withName(containerSpecSecret.getSecretName());
        }
        if (null == secretFileSpec.getGid()) {
            secretFileSpec.withGid("0");
        }
        if (null == secretFileSpec.getUid()) {
            secretFileSpec.withUid("0");
        }
        if (null == secretFileSpec.getMode()) {
            secretFileSpec.withMode(0444l);
        }
    }

    private void interpolateSecretId(List<Secret> secrets, ContainerSpecSecret containerSpecSecret) {
        if (null == containerSpecSecret.getSecretId()) {
            Optional<String> secretID = dockerClient.getSecret(secrets, containerSpecSecret.getSecretName())
                    .map(Secret::getId);
            if (secretID.isPresent()) {
                containerSpecSecret.withSecretId(secretID.get());
            } else {
                throw new RuntimeException("Failed to find Secret with name '"
                        + containerSpecSecret.getSecretName() + "''.");
            }
        }
    }

    private void pullImage(ContainerService service) {
        String image = service.getImage();
        if (dockerClient.getInternalClient().listImagesCmd().withImageNameFilter(image).exec().isEmpty()) {
            // No image with the requested image ID, so try to pull image
            try (PullImageCmd pullImageCmd = dockerClient.getInternalClient().pullImageCmd(image)) {
                pullImageCmd
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Docker image pull command interupted", ex);
            }
        }
    }

}
