package com.cmclinnovations.stack.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.exception.UncheckedException;
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

import uk.ac.cam.cares.jps.base.util.FileUtil;

public class KopiaService extends ContainerService {
    private final JsonNode storageConfig;

    public static final String TYPE = "kopia";

    private static final String VOLUME_DIR = "/data/";
    private static final String KOPIA_PASSWORD_PATH = "/run/secrets/kopia_password";
    private static final String REPOSITORY_CONFIG = "/inputs/data/kopia/repository.config";
    private static final String SFTP_SSH_KEY_PATH = "/tmp/ssh_key";
    private static final String SCHEDULED_SCRIPT_PATH = "/usr/local/bin/kopia-backup.sh";

    private static final String STORAGE_KEY = "storage";
    private static final String CREATE_REPO_ACTION = "create";
    private static final String CONNECT_REPO_ACTION = "connect";

    private String passwordFlag;

    private static final Logger LOGGER = LoggerFactory.getLogger(KopiaService.class);

    public KopiaService(String stackName, ServiceConfig config) {
        super(stackName, config);
        this.storageConfig = JsonHelper.readFile(REPOSITORY_CONFIG).get(STORAGE_KEY);
        this.passwordFlag = "--password=" + FileUtil.readFileLocally(KOPIA_PASSWORD_PATH);
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

        this.genScheduledBackups();
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
        List<String> createRepoCommandArgs = this.genCreateOrConnectRepositoryCommand(action);
        super.createComplexCommand(createRepoCommandArgs.toArray(new String[0]))
                .withErrorStream(errorStream)
                .exec();
    }

    private List<String> genCreateOrConnectRepositoryCommand(String action) {
        String storageType = this.storageConfig.get("type").asText();
        JsonNode storageConfigOptions = this.storageConfig.get("config");
        List<String> createRepoCommandArgs = new ArrayList<>(List.of(TYPE, "repository", action, storageType));
        switch (storageType) {
            case "sftp":
                String storagePath = storageConfigOptions.get("path").asText();
                String host = storageConfigOptions.get("host").asText();
                String user = storageConfigOptions.get("username").asText();
                if (action.equals(CREATE_REPO_ACTION)) {
                    super.executeCommand("sh", "-c", "ssh-keyscan -H " + host + " >> ~/.ssh/known_hosts");
                }
                createRepoCommandArgs.add("--path=" + storagePath);
                createRepoCommandArgs.add("--host=" + host);
                createRepoCommandArgs.add("--username=" + user);
                try {
                    String keyFilePath = storageConfigOptions.get("keyfile").asText();
                    super.sendFileContent(SFTP_SSH_KEY_PATH, Files.readAllBytes(Paths.get(keyFilePath)));
                    createRepoCommandArgs.add("--keyfile=" + SFTP_SSH_KEY_PATH);
                } catch (IOException e) {
                    throw new UncheckedException(e);
                }
                createRepoCommandArgs.add("--known-hosts=/root/.ssh/known_hosts");
                break;
            case "filesystem":
                storagePath = storageConfigOptions.get("path").asText();
                createRepoCommandArgs.add("--path=" + storagePath);
                break;
            default:
                LOGGER.warn(
                        "Unsupported storage type '{}' for automatic stack setup. Please manually configure the storage",
                        storageType);
                break;
        }

        createRepoCommandArgs.add(this.passwordFlag);
        return createRepoCommandArgs;
    }

    private void genScheduledBackups() {
        LOGGER.info("Creating initial snapshot for the repository...");
        super.createComplexCommand(TYPE, "snapshot", CREATE_REPO_ACTION, VOLUME_DIR, this.passwordFlag)
                .exec();

        LOGGER.info("Generating scheduled backup script...");
        StringBuilder scriptContents = new StringBuilder();
        scriptContents.append("#!/bin/bash\n\n")
                // Add a warning that this file is auto-generated
                .append("# AUTO-GENERATED BY JAVA - DO NOT EDIT MANUALLY\n\n")
                .append(
                        String.join(" ", this.genCreateOrConnectRepositoryCommand(CONNECT_REPO_ACTION)))
                .append("\n")
                .append("kopia snapshot create --all ").append(this.passwordFlag);
        super.sendFileContent(SCHEDULED_SCRIPT_PATH, scriptContents.toString().getBytes(StandardCharsets.UTF_8));
        // Requires executable permission for root user for the crone job
        super.createComplexCommand("chmod", "+x", SCHEDULED_SCRIPT_PATH)
                .withUser("root")
                .exec();
    }
}
