package com.cmclinnovations.apis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class GDALClient extends ContainerClient {

    private final PostGISEndpointConfig postgreSQLEndpoint;

    public GDALClient() {
        postgreSQLEndpoint = readEndpointConfig("postgis", PostGISEndpointConfig.class);
    }

    private String computePGSQLSourceString(String database) {
        return "PG:dbname=" + database + " host=" + postgreSQLEndpoint.getHostName()
                + " port=" + postgreSQLEndpoint.getPort() + " user=" + postgreSQLEndpoint.getUsername()
                + " password=" + postgreSQLEndpoint.getPassword();
    }

    public void uploadVectorStringToPostGIS(String database, String layername, String fileContents,
            Ogr2OgrOptions options) {
        String containerId = getDockerClient().getContainerId("gdal");
        String tmpDir = getDockerClient().makeTempDir(containerId);
        boolean exceptionThrown = false;
        try {
            getDockerClient().sendFiles(containerId, Map.of(layername, fileContents.getBytes()), tmpDir);

            uploadVectorToPostGIS(database, layername, tmpDir + "/" + layername, null, options);
        } catch (IOException ex) {
            exceptionThrown = true;
            throw new RuntimeException(
                    "Failed to send file content for '" + layername + "' to database '" + database + "'.", ex);
        } catch (Throwable ex) {
            exceptionThrown = true;
            throw ex;
        } finally {
            try {
                getDockerClient().deleteDirectory(containerId, tmpDir);
            } catch (Exception ex2) {
                if (exceptionThrown) {
                    // Don't worry about this exception as any previously thrown exception is more
                    // important.
                } else {
                    throw ex2;
                }
            }
        }
    }

    public void uploadVectorFileToPostGIS(String database, String layername, String filePath, Ogr2OgrOptions options) {
        String fileContents;
        try {
            fileContents = Files.readString(Path.of(filePath));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read file '" + filePath + "'.", ex);
        }
        uploadVectorStringToPostGIS(database, layername, fileContents, options);
    }

    public void uploadVectorURLToPostGIS(String database, String layername, String url, Ogr2OgrOptions options) {
        uploadVectorToPostGIS(database, layername, url, null, options);
    }

    private void uploadVectorToPostGIS(String database, String layername, String filePath, String fileContents,
            Ogr2OgrOptions options) {

        String containerId = getDockerClient().getContainerId("gdal");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        getDockerClient().createComplexCommand(containerId, options.appendToArgs("ogr2ogr", "-overwrite",
                "-f", "PostgreSQL",
                computePGSQLSourceString(database),
                filePath,
                "-nln", layername))
                .withHereDocument(fileContents)
                .withOutputStream(outputStream)
                .withErrorStream(errorStream)
                .withEnvVar("PG_USE_COPY", "YES")
                .withEnvVars(options.getEnv())
                .exec();

        if (0 != errorStream.size()) {
            throw new RuntimeException("Docker exec command wrote the following to stderr:\n" + errorStream.toString());
        }
    }

}
