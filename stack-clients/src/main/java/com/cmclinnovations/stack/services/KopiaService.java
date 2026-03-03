package com.cmclinnovations.stack.services;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.fasterxml.jackson.databind.JsonNode;

public class KopiaService extends ContainerService {
    public static final String TYPE = "kopia";

    private static final String REPOSITORY_CONFIG = "/inputs/data/kopia/repository.config";
    private static final String STORAGE_KEY = "storage";

    private static final String PASSWORD_SECRETS_FLAG = "--password=$(cat /run/secrets/kopia_password)";

    private static final Logger LOGGER = LoggerFactory.getLogger(KopiaService.class);

    public KopiaService(String stackName, ServiceConfig config) {
        super(stackName, config);
    }

    @Override
    public void doFirstTimePostStartUpConfiguration() {
        JsonNode storageConfig = JsonHelper.readFile(REPOSITORY_CONFIG).get(STORAGE_KEY);
        String storageType = storageConfig.get("type").asText();
        JsonNode storageConfigOptions = storageConfig.get("config");
        List<String> createRepoCommandArgs = new ArrayList<>(List.of(TYPE, "repository", "create", storageType));
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
        super.executeCommand(createRepoCommandArgs.toArray(new String[0]));
    }
}
