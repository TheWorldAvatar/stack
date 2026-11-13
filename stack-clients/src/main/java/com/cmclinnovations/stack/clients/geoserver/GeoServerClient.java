package com.cmclinnovations.stack.clients.geoserver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.core.RESTEndpointConfig;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.docker.DockerClient;
import com.cmclinnovations.stack.clients.postgis.PostGISClient;
import com.cmclinnovations.stack.clients.postgis.PostGISEndpointConfig;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import it.geosolutions.geoserver.rest.GeoServerRESTManager;
import it.geosolutions.geoserver.rest.Util;
import it.geosolutions.geoserver.rest.encoder.GSLayerEncoder;
import it.geosolutions.geoserver.rest.encoder.GSResourceEncoder;
import it.geosolutions.geoserver.rest.encoder.GSResourceEncoder.ProjectionPolicy;
import it.geosolutions.geoserver.rest.encoder.coverage.GSImageMosaicEncoder;
import it.geosolutions.geoserver.rest.encoder.datastore.GSPostGISDatastoreEncoder;
import it.geosolutions.geoserver.rest.encoder.feature.GSFeatureTypeEncoder;
import it.geosolutions.geoserver.rest.encoder.metadata.virtualtable.GSVirtualTableEncoder;

public class GeoServerClient extends ClientWithEndpoint<RESTEndpointConfig> {

    private static final Logger logger = LoggerFactory.getLogger(GeoServerClient.class);
    private final GeoServerRESTManager manager;

    private final PostGISEndpointConfig postgreSQLEndpoint;

    private static GeoServerClient instance = null;

    private static final ObjectMapper objectMapper = JsonHelper.getMapper();

    private static final Path GEOSERVER_DATA_DIRECTORY = Path.of("/opt/geoserver_data");

    public static final Path SERVING_DIRECTORY = GEOSERVER_DATA_DIRECTORY.resolve("www");
    private static final Path STATIC_DATA_DIRECTORY = SERVING_DIRECTORY.resolve("static_data");
    private static final Path ICONS_DIRECTORY = SERVING_DIRECTORY.resolve("icons");
    private static final Path WORKSPACES_DIRECTORY = GEOSERVER_DATA_DIRECTORY.resolve("workspaces");
    private static final String USER_PROJECTIONS_DIR = "/opt/geoserver_data/user_projections";
    private static final String GEOSERVER_RASTER_INDEX_DATABASE_SUFFIX = "_geoserver_indices";
    private static final String DIM_PREFIX = "dim_";

    private static final Path STYLES_RELATIVE_PATH = Path.of("styles");

    public static GeoServerClient getInstance() {
        if (null == instance) {
            instance = new GeoServerClient();
        }
        return instance;
    }

    private GeoServerClient() {
        this(null, null, null);
    }

    public GeoServerClient(URL restURL, String username, String password) {
        super(EndpointNames.GEOSERVER, RESTEndpointConfig.class);
        if (null == restURL || null == username || null == password) {
            RESTEndpointConfig geoserverEndpointConfig = readEndpointConfig();
            if (null == restURL) {
                restURL = geoserverEndpointConfig.getUrl();
            }
            if (null == username) {
                username = geoserverEndpointConfig.getUserName();
            }
            if (null == password) {
                password = geoserverEndpointConfig.getPassword();
            }
        }

        manager = new GeoServerRESTManager(restURL, username, password);

        postgreSQLEndpoint = readEndpointConfig(EndpointNames.POSTGIS, PostGISEndpointConfig.class);
    }

    public void createWorkspace(String workspaceName) {
        if (manager.getReader().existsWorkspace(workspaceName)) {
            logger.info("GeoServer workspace '{}' already exists.", workspaceName);
        } else {
            if (manager.getPublisher().createWorkspace(workspaceName)) {
                logger.info("GeoServer workspace '{}' created.", workspaceName);
            } else {
                throw new RuntimeException(
                        "GeoServer workspace '" + workspaceName + "' does not exist and could not be created.");
            }
        }
    }

    public void removeWorkspace(String workspaceName) {
        if (!manager.getReader().existsWorkspace(workspaceName)) {
            logger.info("GeoServer workspace '{}' does not exists and cannot be deleted.", workspaceName);
        } else {
            if (manager.getPublisher().removeWorkspace(workspaceName, true)) {
                logger.info("GeoServer workspace '{}'' removed", workspaceName);
            } else {
                throw new RuntimeException("GeoServer workspace " + workspaceName + "' could not be deleted.");
            }
        }
    }

    public void reload() {
        manager.getPublisher().reload();
    }

    public void loadStyle(GeoServerStyle style, String workspaceName) {
        String name = style.getName();
        if (manager.getReader().existsStyle(workspaceName, name)) {
            logger.info("GeoServer style '{}:{}' already exists.", workspaceName, name);
        } else {
            if (manager.getPublisher().publishStyleInWorkspace(workspaceName,
                    Path.of("/inputs/config").resolve(style.getFile()).toFile(), name)) {
                logger.info("GeoServer style '{}:{}' created.", workspaceName, name);
            } else {
                throw new RuntimeException("GeoServer style '" + workspaceName + ":" + name
                        + "' does not exist and could not be created.");
            }
        }

        if (null != style.getLegend()) {
            updateStyleLegend(style, workspaceName);
        }
    }

    private Path getStyleDirectory(String workspaceName) {
        return WORKSPACES_DIRECTORY.resolve(workspaceName).resolve(STYLES_RELATIVE_PATH);
    }

    private void updateStyleLegend(GeoServerStyle style, String workspaceName) {
        Legend legend = style.getLegend();
        Path legendPath = getStyleDirectory(workspaceName);

        if (legend.getFile() != null) {
            Path sourcePath = Path.of("/inputs/config").resolve(legend.getFile());
            Path targetPath = legendPath.resolve(legend.getFile());
            try {
                if (hasLocalGeoServerDataMount()) {
                    copyRecursively(sourcePath, targetPath);
                } else {
                    String containerId = getContainerId(EndpointNames.GEOSERVER);
                    sendFileContent(containerId, targetPath, Files.readAllBytes(sourcePath));
                }
            } catch (IOException ex) {
                throw new RuntimeException(
                        "GeoServer legend update failed for style '" + workspaceName + ":" + style.getName() + "'.",
                        ex);
            }

            legend.setOnlineResource(legend.getFile());
        }

        RESTEndpointConfig endpointConfig = readEndpointConfig();
        String baseUrl = endpointConfig.getUrl().toString();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        String styleEndpoint = String.format("%srest/workspaces/%s/styles/%s.json", baseUrl, workspaceName,
                style.getName());

        String payloadJson;
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("style", objectMapper.valueToTree(style));
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to serialise GeoServer style legend payload for style '" + workspaceName
                    + ":" + style.getName() + "'.", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(styleEndpoint))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (endpointConfig.getUserName() + ":" + endpointConfig.getPassword())
                                .getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payloadJson))
                .build();

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("GeoServer legend update failed for style '" + workspaceName + ":"
                        + style.getName() + "' with status " + response.statusCode() + ": " + response.body());
            }
            logger.info("GeoServer legend for '{}:{}' updated.", workspaceName, style.getName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "GeoServer legend update failed for style '" + workspaceName + ":" + style.getName() + "'.", ex);
        } catch (IOException ex) {
            throw new RuntimeException(
                    "GeoServer legend update failed for style '" + workspaceName + ":" + style.getName() + "'.", ex);
        }
    }

    public void loadOtherFiles(Path baseDirectory, List<GeoserverOtherStaticFile> files) {
        files.forEach(file -> {
            loadStaticFile(baseDirectory, file);
        });
    }

    private boolean hasLocalGeoServerDataMount() {
        return Files.isDirectory(SERVING_DIRECTORY);
    }

    private boolean hasLocalGeoTiffsMount() {
        return Files.isDirectory(Path.of(StackClient.GEOTIFFS_DIR));
    }

    static void prepareGeoTiffDirectory(Path geotiffRoot, Path geotiffDir) throws IOException {
        Path root = geotiffRoot.toRealPath();
        Path directory = geotiffDir.toRealPath();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IllegalArgumentException("GeoTIFF mosaic directory must be inside '" + root + "'.");
        }

        // The volume's group is shared with GeoServer, which must write mosaic metadata.
        GroupPrincipal group = Files.readAttributes(root, PosixFileAttributes.class).group();
        for (Path parent = directory.getParent(); !parent.equals(root); parent = parent.getParent()) {
            makeGeoTiffPathGroupWritable(parent, group);
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (!Files.isSymbolicLink(path)) {
                    makeGeoTiffPathGroupWritable(path, group);
                }
            }
        }
    }

    private static void makeGeoTiffPathGroupWritable(Path path, GroupPrincipal group) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class);
        if (!attributes.group().equals(group)) {
            Files.getFileAttributeView(path, PosixFileAttributeView.class).setGroup(group);
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        Set<PosixFilePermission> updatedPermissions = new HashSet<>(permissions);
        updatedPermissions.add(PosixFilePermission.GROUP_READ);
        updatedPermissions.add(PosixFilePermission.GROUP_WRITE);
        if (attributes.isDirectory()) {
            updatedPermissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (!updatedPermissions.equals(permissions)) {
            Files.setPosixFilePermissions(path, updatedPermissions);
        }
    }

    private void copyRecursively(Path sourcePath, Path targetPath) throws IOException {
        if (Files.isDirectory(sourcePath)) {
            Files.createDirectories(targetPath);
            try (var paths = Files.walk(sourcePath)) {
                paths.forEach(currentSourcePath -> {
                    Path relativePath = sourcePath.relativize(currentSourcePath);
                    Path currentTargetPath = targetPath.resolve(relativePath);
                    try {
                        if (Files.isDirectory(currentSourcePath)) {
                            Files.createDirectories(currentTargetPath);
                        } else {
                            Files.createDirectories(currentTargetPath.getParent());
                            Files.copy(currentSourcePath, currentTargetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException ex) {
                        throw new RuntimeException(
                                "Failed to copy GeoServer file '" + currentSourcePath + "' to '"
                                        + currentTargetPath + "'.",
                                ex);
                    }
                });
            }
        } else {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loadStaticFile(Path baseDirectory, GeoserverOtherStaticFile file) {
        Path absSourcePath = baseDirectory.resolve(file.getSource());
        Path absTargetPath = STATIC_DATA_DIRECTORY.resolve(file.getTarget());

        if (!Files.exists(absSourcePath)) {
            throw new RuntimeException(
                    "Static GeoServer data '" + absSourcePath.toString() + "' does not exist and could not be loaded.");
        } else {
            try {
                if (hasLocalGeoServerDataMount()) {
                    copyRecursively(absSourcePath, absTargetPath);
                } else if (Files.isDirectory(absSourcePath)) {
                    sendFolder(getContainerId(EndpointNames.GEOSERVER), absSourcePath.toString(),
                            absTargetPath.toString());
                } else {
                    sendFileContent(getContainerId(EndpointNames.GEOSERVER),
                            STATIC_DATA_DIRECTORY.resolve(file.getTarget()),
                            Files.readAllBytes(baseDirectory.resolve(file.getSource())));
                }
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Failed to serialise file '" + absSourcePath.toString() + "'.");
            }

        }
    }

    public void loadIcons(Path baseDirectory, String iconDir) {
        if (!Files.exists(baseDirectory.resolve(iconDir))) {
            throw new RuntimeException(
                    "Static GeoServer data '" + baseDirectory.resolve(iconDir)
                            + "' does not exist and could not be loaded.");
        } else if (Files.isDirectory(baseDirectory.resolve(iconDir))) {
            try {
                if (hasLocalGeoServerDataMount()) {
                    copyRecursively(baseDirectory.resolve(iconDir), ICONS_DIRECTORY);
                } else {
                    sendFolder(getContainerId(EndpointNames.GEOSERVER), baseDirectory.resolve(iconDir).toString(),
                            ICONS_DIRECTORY.toString());
                }
            } catch (IOException ex) {
                throw new RuntimeException("Failed to load GeoServer icons from '" + iconDir + "'.", ex);
            }
        } else {
            throw new RuntimeException("Geoserver icon directory " + iconDir + "does not exist or is not a directory.");
        }
    }

    public void createPostGISDataStore(String workspaceName, String name, String database, String schema) {
        if (manager.getReader().existsDatastore(workspaceName, name)) {
            logger.info("GeoServer datastore '{}' already exists.", name);
        } else {

            GSPostGISDatastoreEncoder encoder = new GSPostGISDatastoreEncoder(name);
            encoder.setHost(postgreSQLEndpoint.getHostName());
            encoder.setPort(Integer.parseInt(postgreSQLEndpoint.getPort()));
            encoder.setUser(postgreSQLEndpoint.getUsername());
            encoder.setPassword(postgreSQLEndpoint.getPassword());
            encoder.setDatabase(database);
            encoder.setSchema(schema);
            encoder.setValidateConnections(true);

            if (manager.getStoreManager().create(workspaceName, encoder)) {
                logger.info("GeoServer datastore '{}' created.", name);
            } else {
                throw new RuntimeException(
                        "GeoServer datastore '" + name + "' does not exist and could not be created.");
            }
        }
    }

    public void createPostGISLayer(String workspaceName, String database, String schema, String layerName,
            GeoServerVectorSettings geoServerSettings) {
        String storeName = database;

        // Need to include the "Util.DEFAULT_QUIET_ON_NOT_FOUND" argument because the
        // 2-arg version of "existsLayer" incorrectly calls the 3-arg version of the
        // "existsLayerGroup" method.
        if (manager.getReader().existsLayer(workspaceName, layerName, Util.DEFAULT_QUIET_ON_NOT_FOUND)) {
            logger.info("GeoServer database layer '{}' already exists.", layerName);
        } else {
            createPostGISDataStore(workspaceName, storeName, database, schema);

            GSFeatureTypeEncoder fte = geoServerSettings.getFeatureTypeSettings();
            if (fte.getName() == null) {
                fte.setName(layerName);
            }
            if (!GeoServerElementUtils.nodeIsSet(fte, "projectionPolicy")) {
                fte.setProjectionPolicy(ProjectionPolicy.NONE);
            }
            if (!GeoServerElementUtils.nodeIsSet(fte, "title")) {
                fte.setTitle(layerName);
            }

            GSVirtualTableEncoder virtualTable = geoServerSettings.getVirtualTable();
            configureVirtualTable(layerName, fte, virtualTable);

            processDimensions(geoServerSettings, fte);

            if (manager.getPublisher().publishDBLayer(workspaceName, storeName, fte,
                    geoServerSettings.getLayerSettings())) {
                logger.info("GeoServer database layer '{}' created.", layerName);
            } else {
                throw new RuntimeException(
                        "GeoServer database layer '" + layerName + "' does not exist and could not be created.");
            }
        }
    }

    private void configureVirtualTable(String layerName, GSFeatureTypeEncoder fte, GSVirtualTableEncoder virtualTable) {
        if (null != virtualTable) {
            // Append the layer name to the table name
            virtualTable.setName(layerName + "_" + virtualTable.getName());
            // Handle sql stored in a separate file
            virtualTable.setSql(JsonHelper.handleFileValues(virtualTable.getSql()));
            fte.setNativeName(virtualTable.getName());
            fte.setMetadataVirtualTable(virtualTable);
        }
    }

    public void createGeoTiffLayer(String workspaceName, String name, String database, String schema,
            GeoServerRasterSettings geoServerSettings, MultidimSettings mdimSettings) {

        if (manager.getReader().existsCoveragestore(workspaceName, name)) {
            logger.info("GeoServer coverage store '{}' already exists.", name);
        } else {
            String geoserverRasterIndexDatabaseName = database + GEOSERVER_RASTER_INDEX_DATABASE_SUFFIX;
            PostGISClient postgisClient = PostGISClient.getInstance();
            postgisClient.createDatabase(geoserverRasterIndexDatabaseName);
            postgisClient.createSchema(geoserverRasterIndexDatabaseName, schema);

            Properties datastoreProperties = new Properties();
            datastoreProperties.putIfAbsent("SPI", "org.geotools.data.postgis.PostgisNGDataStoreFactory");
            datastoreProperties.putIfAbsent("host", postgreSQLEndpoint.getHostName());
            datastoreProperties.putIfAbsent("port", postgreSQLEndpoint.getPort());
            datastoreProperties.putIfAbsent("database", geoserverRasterIndexDatabaseName);
            datastoreProperties.putIfAbsent("schema", schema);
            datastoreProperties.putIfAbsent("user", postgreSQLEndpoint.getUsername());
            datastoreProperties.putIfAbsent("passwd", postgreSQLEndpoint.getPassword());
            datastoreProperties.putIfAbsent("Loose\\ bbox", "true");
            datastoreProperties.putIfAbsent("Estimated\\ extends", "false");
            datastoreProperties.putIfAbsent("validate\\ connections", "true");
            datastoreProperties.putIfAbsent("Connection\\ timeout", "10");
            datastoreProperties.putIfAbsent("preparedStatements", "true");
            StringWriter stringWriter = new StringWriter();

            Path geotiffDir = Path.of(StackClient.GEOTIFFS_DIR, database, schema, name);
            try {

                Map<String, byte[]> files = new HashMap<>();

                datastoreProperties.store(stringWriter, "");
                files.put("datastore.properties", stringWriter.toString().getBytes());
                String indexerProperties = "Schema=location:String,*the_geom:Polygon\nPropertyCollectors=";

                if (null != mdimSettings) {
                    TimeOptions timeOptions = mdimSettings.getTimeOptions();
                    if (null != timeOptions) {
                        String regex = timeOptions.getRegex();
                        String format = timeOptions.getFormat();

                        if (null != format) {
                            indexerProperties = "TimeAttribute=time\nSchema=location:String,time:java.util.Date,*the_geom:Polygon\nPropertyCollectors=TimestampFileNameExtractorSPI[timeregex](time)";
                            files.put("timeregex.properties", ("regex=" + regex + ",format=" + format).getBytes());
                        } else {
                            indexerProperties = "AdditionalDomainAttributes=time_index(time_index)\nSchema=location:String,time_index:String,*the_geom:Polygon\nPropertyCollectors=StringFileNameExtractorSPI[timeregex](time_index)";
                            files.put("timeregex.properties", ("regex=" + regex).getBytes());
                        }
                    }
                }

                files.put("indexer.properties",
                        indexerProperties.getBytes());

                if (hasLocalGeoTiffsMount()) {
                    Files.createDirectories(geotiffDir);
                    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                        Files.write(geotiffDir.resolve(entry.getKey()), entry.getValue());
                    }
                    prepareGeoTiffDirectory(Path.of(StackClient.GEOTIFFS_DIR), geotiffDir);
                } else {
                    String containerId = getContainerId(EndpointNames.GEOSERVER);
                    sendFilesContent(containerId, files, geotiffDir.toString());
                }
            } catch (IOException ex) {
                throw new RuntimeException(
                        "The 'datastore.properties' and 'indexer.properties' files for the GeoServer coverage datastore '"
                                + name + "' could not be created or made writable by the shared volume group.",
                        ex);
            }
            try {
                GSImageMosaicEncoder storeEncoder = geoServerSettings.getDataStoreSettings();
                if (null == storeEncoder.getName()) {
                    storeEncoder.setName(name);
                }
                storeEncoder.setAllowMultithreading(true);

                GSLayerEncoder layerEncoder = geoServerSettings.getLayerSettings();
                if (null == layerEncoder) {
                    // TODO Work out how to set the layer title/display name
                }

                processDimensions(geoServerSettings, storeEncoder);

                if (manager.getPublisher().publishExternalMosaic(workspaceName, name, geotiffDir.toFile(),
                        storeEncoder, layerEncoder)) {
                    logger.info("GeoServer coverage (datastore and layer) '{}' created.", name);
                } else {
                    throw new RuntimeException(
                            "GeoServer coverage datastore and/or layer '" + name
                                    + "' does not exist and could not be created.");
                }
            } catch (FileNotFoundException ex) {
                throw new RuntimeException(
                        "GeoServer coverage datastore '" + name + "' does not exist and could not be created.", ex);
            }
        }
    }

    private void processDimensions(GeoServerDimensionSettings dimensionSettings, GSResourceEncoder resourceEncoder) {
        Map<String, UpdatedGSFeatureDimensionInfoEncoder> dimensions = dimensionSettings.getDimensions();
        if (null != dimensions) {
            dimensions.forEach((dimName, value) -> {
                if (!dimName.startsWith(DIM_PREFIX) && !dimName.equals("time")
                        && !dimName.equals("elevation")) {
                    throw new RuntimeException(
                            "When using a GeoServer custom dimension (i.e. not `time` or `elevation) the name `"
                                    + dimName + "` must begin with the prefix `dim_`.");
                }
                resourceEncoder.setMetadataDimension(dimName, value);
            });
        }
    }

    public void addProjectionsToGeoserver(String wktString, String srid) {
        byte[] projectionFileContent = (srid + "=" + wktString + "\n").getBytes();
        boolean wroteProjection = false;

        if (hasLocalGeoServerDataMount()) {
            try {
                Path userProjectionsDir = Path.of(USER_PROJECTIONS_DIR);
                Files.createDirectories(userProjectionsDir);
                Files.write(userProjectionsDir.resolve("epsg.properties"), projectionFileContent);
                wroteProjection = true;
            } catch (IOException ex) {
                throw new RuntimeException("Failed to write GeoServer custom projections file to local data mount.",
                        ex);
            }
        }

        try {
            String geoserverContainerId = getContainerId("geoserver");
            DockerClient dockerClient = DockerClient.getInstance();
            dockerClient.makeDir(geoserverContainerId, USER_PROJECTIONS_DIR);
            sendFileContent(geoserverContainerId,
                    Path.of(USER_PROJECTIONS_DIR, "epsg.properties"),
                    projectionFileContent);
            wroteProjection = true;
        } catch (RuntimeException ex) {
            if (!wroteProjection) {
                logger.debug("GeoServer projection could not be written via Docker API.", ex);
            } else {
                logger.warn("Failed to update GeoServer projection file via Docker after local write succeeded.", ex);
            }
        }

        if (!wroteProjection) {
            logger.warn(
                    "GeoServer custom projection '{}' could not be written because no shared data mount or Docker socket is available.",
                    srid);
            return;
        }

        GeoServerClient.getInstance().reload();
    }
}
