package com.cmclinnovations.stack.services;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;

public class KopiaService extends ContainerService {
    private final JsonNode storageConfig;

    public static final String TYPE = "kopia";

    private static final String REPOSITORY_CONFIG = "/inputs/data/kopia/repository.config";
    private static final String VOLUME_DIR = "/data/";
    private static final String STORAGE_KEY = "storage";
    private static final String CREATE_REPO_ACTION = "create";
    private static final String CONNECT_REPO_ACTION = "connect";

    private static final String PASSWORD_SECRETS_FLAG = "--password=$(cat /run/secrets/kopia_password)";

    private static final Logger LOGGER = LoggerFactory.getLogger(KopiaService.class);

    public KopiaService(String stackName, ServiceConfig config) {
        super(stackName, config);
        this.storageConfig = JsonHelper.readFile(REPOSITORY_CONFIG).get(STORAGE_KEY);
    }

    @Override
    public void doPreStartUpConfiguration() {
        // Mount all volumes on the stack (except kopia) for backups
        List<InspectVolumeResponse> volumes = super.getVolumes();
        List<Mount> mounts = new ArrayList<>();

        for (InspectVolumeResponse vol : volumes) {
            if (!vol.getName().endsWith(TYPE)) {
                // Remove stack name prefix (STACK_) from volume name
                String volName = vol.getName().substring(StackClient.getStackName().length() + 1);
                String destinationPath = VOLUME_DIR + volName;
                mounts.add(new Mount()
                        .withType(MountType.VOLUME) // Use Docker volume for named volumes
                        .withSource(volName)
                        .withTarget(destinationPath)
                        .withReadOnly(false));
            }
        }

        ContainerSpec containerSpec = super.getContainerSpec();
        containerSpec.withMounts(mounts);
    }

    @Override
    public void doFirstTimePostStartUpConfiguration() {
        if (!connectRepository()) {
            LOGGER.info("No existing Kopia repository found, creating new repository.");
            createRepository();
        }
    }

    private void createRepository() {
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        createOrConnectRepository(CREATE_REPO_ACTION, errorStream);
    }

    private boolean connectRepository() {
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        createOrConnectRepository(CONNECT_REPO_ACTION, errorStream);
        return errorStream.toString().startsWith("Connected to repository");
    }

    private void createOrConnectRepository(String action, ByteArrayOutputStream errorStream) {
        String storageType = this.storageConfig.get("type").asText();
        JsonNode storageConfigOptions = this.storageConfig.get("config");
        List<String> createRepoCommandArgs = new ArrayList<>(List.of(TYPE, "repository", action, storageType));
        switch (storageType) {
            case "filesystem":
                String storagePath = storageConfigOptions.get("path").asText();
                createRepoCommandArgs.add("--path=" + storagePath);
                break;
            default:
                LOGGER.warn(
                        "Unsupported storage type '{}' for automatic stack setup. Please manually configure the storage",
                        storageType);
                break;
        }

        createRepoCommandArgs.add(PASSWORD_SECRETS_FLAG);
        super.createComplexCommand(createRepoCommandArgs.toArray(new String[0]))
                .withErrorStream(errorStream)
                .exec();
    }
}
