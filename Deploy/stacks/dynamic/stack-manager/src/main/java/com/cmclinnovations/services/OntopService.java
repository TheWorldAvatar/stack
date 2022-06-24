package com.cmclinnovations.services;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.cmclinnovations.clients.ontop.OntopClient;
import com.cmclinnovations.clients.postgis.PostGISEndpointConfig;
import com.cmclinnovations.services.config.ServiceConfig;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.ContainerSpecConfig;

public final class OntopService extends ContainerService {

    public static final String TYPE = "ontop";

    private static final String ONTOP_DB_URL = "ONTOP_DB_URL";
    private static final String ONTOP_DB_DRIVER = "ONTOP_DB_DRIVER";
    private static final String ONTOP_DB_DRIVER_URL = "ONTOP_DB_DRIVER_URL";
    private static final String ONTOP_DB_USER = "ONTOP_DB_USER";
    private static final String ONTOP_DB_PASSWORD_FILE = "ONTOP_DB_PASSWORD_FILE";

    public OntopService(String stackName, ServiceManager serviceManager, ServiceConfig config) {
        super(stackName, serviceManager, config);
    }

    @Override
    public void doPreStartUpConfiguration() {
        ContainerSpec containerSpec = getContainerSpec();

        Optional<ContainerSpecConfig> dbConfigRef = containerSpec.getConfigs().stream().findFirst();
        if (dbConfigRef.isPresent()) {
            PostGISEndpointConfig postgreSQLEndpoint = readEndpointConfig(dbConfigRef.get().getConfigName(),
                    PostGISEndpointConfig.class);
            String databaseName = getEnvironmentVariable("ONTOP_DB_NAME");
            setEnvironmentVariableIfAbsent(ONTOP_DB_URL, postgreSQLEndpoint.getJdbcURL(databaseName));
            setEnvironmentVariableIfAbsent(ONTOP_DB_DRIVER, postgreSQLEndpoint.getJdbcDriver());
            setEnvironmentVariableIfAbsent(ONTOP_DB_DRIVER_URL, postgreSQLEndpoint.getJdbcDriverURL());
            setEnvironmentVariableIfAbsent(ONTOP_DB_USER, postgreSQLEndpoint.getUsername());
            setEnvironmentVariableIfAbsent(ONTOP_DB_PASSWORD_FILE, postgreSQLEndpoint.getPasswordFile());
        } else {
            checkEnvironmentVariableNonNull(ONTOP_DB_URL);
            checkEnvironmentVariableNonNull(ONTOP_DB_DRIVER);
            checkEnvironmentVariableNonNull(ONTOP_DB_DRIVER_URL);
            checkEnvironmentVariableNonNull(ONTOP_DB_USER);
            checkEnvironmentVariableNonNull(ONTOP_DB_PASSWORD_FILE);
        }

        setEnvironmentVariableIfAbsent("ONTOP_LAZY_INIT", "false");
        setEnvironmentVariableIfAbsent("ONTOP_CORS_ALLOWED_ORIGINS", "*");
        setEnvironmentVariableIfAbsent("ONTOP_DEBUG", "false");
        checkEnvironmentVariableNonNull(OntopClient.ONTOP_MAPPING_FILE);

        containerSpec
                .withCommand(List.of("/bin/sh", "-c", "wget -P /opt/ontop/jdbc "
                        + getEnvironmentVariable(ONTOP_DB_DRIVER_URL)
                        + " && ./entrypoint.sh"));
    }

    @Override
    public void doPostStartUpConfiguration() {
        new OntopClient().updateOBDA(null);
    }

    @Deprecated
    private void copyInJDBCLibrary() {
        try {
            URL url = new URL(getEnvironmentVariable(ONTOP_DB_DRIVER_URL));
            String filename = Path.of(url.getPath()).getFileName().toString();
            String folderPath = "/opt/ontop/jdbc";
            downloadFileAndSendItToContainer(url, folderPath, filename, false);
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException("Value provided for the environment variable '" + ONTOP_DB_DRIVER_URL
                    + "' for contianer '" + getName() + "' is not a valid URL.", ex);
        }
    }

}
