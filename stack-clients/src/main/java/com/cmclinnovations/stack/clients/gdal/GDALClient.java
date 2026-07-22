package com.cmclinnovations.stack.clients.gdal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.Vector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.MultiDimInfoOptions;
import org.gdal.gdal.TranslateOptions;
import org.gdal.gdal.WarpOptions;
import org.gdal.gdal.gdal;
import org.gdal.osr.SpatialReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.docker.ContainerClient;
import com.cmclinnovations.stack.clients.geoserver.GeoServerClient;
import com.cmclinnovations.stack.clients.geoserver.MultidimSettings;
import com.cmclinnovations.stack.clients.geoserver.TimeOptions;
import com.cmclinnovations.stack.clients.postgis.PostGISClient;
import com.cmclinnovations.stack.clients.postgis.PostGISEndpointConfig;
import com.cmclinnovations.stack.clients.utils.DateStringFormatter;
import com.cmclinnovations.stack.clients.utils.DateTimeParser;
import com.cmclinnovations.stack.clients.utils.FileUtils;
import com.cmclinnovations.stack.clients.utils.TempDir;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

/**
 * Contains methods to run gdal commands for transforming and uploading raster
 * and vector data
 */
public class GDALClient extends ContainerClient {

    private static final String GDAL = "gdal";

    private static final String POSTGIS = "postgis";

    private static final Logger logger = LoggerFactory.getLogger(GDALClient.class);

    private final PostGISEndpointConfig postgreSQLEndpoint;

    private static GDALClient instance = null;

    static {
        gdal.AllRegister();
        gdal.UseExceptions();
    }

    public static GDALClient getInstance() {
        if (null == instance) {
            instance = new GDALClient();
        }
        return instance;
    }

    private GDALClient() {
        postgreSQLEndpoint = readEndpointConfig(EndpointNames.POSTGIS, PostGISEndpointConfig.class);
    }

    private static Vector<String> toVector(String... values) {
        return new Vector<>(Arrays.asList(values));
    }

    private static Vector<String> commandToOptions(String[] command) {
        return new Vector<>(Arrays.asList(Arrays.copyOfRange(command, 1, command.length - 2)));
    }

    private String computePGSQLSourceString(String database) {
        return "PG:dbname=" + database
        // Duplicate hostname to introduce a retry if the first connection fails
                + " host=" + postgreSQLEndpoint.getHostName() + "," + postgreSQLEndpoint.getHostName()
                + " port=" + postgreSQLEndpoint.getPort()
                + " user=" + postgreSQLEndpoint.getUsername()
                + " password=" + postgreSQLEndpoint.getPassword();
    }

    public void uploadVectorStringToPostGIS(String database, String schema, String layerName, String fileContents,
            Ogr2OgrOptions options, boolean append) {

        try (TempDir tmpDir = makeLocalTempDir()) {
            Path filePath = tmpDir.getPath().resolve(layerName);
            try {
                Files.writeString(filePath, fileContents);
                uploadVectorToPostGIS(database, schema, layerName, filePath.toString(), options, append);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to write string for vector '" + layerName
                        + "' layer to a file in a temporary directory.", ex);
            }
        }
    }

    public void uploadVectorFilesToPostGIS(String database, String schema, String layerName, String dirPath,
            Ogr2OgrOptions options, boolean append) {
        try (TempDir tmpDir = makeLocalTempDir()) {
            tmpDir.copyFrom(Path.of(dirPath));
            String gdalContainerId = getContainerId(GDAL);
            Multimap<String, String> foundGeoFiles = findGeoFiles(gdalContainerId, tmpDir.toString());
            for (var entry : foundGeoFiles.asMap().entrySet()) {
                Collection<String> filesOfType = entry.getValue();
                switch (entry.getKey()) {
                    case "XLSX":
                    case "XLS":
                        Collection<String> filesToRemove = new ArrayList<>();
                        Collection<String> filesToAdd = new ArrayList<>();
                        for (String filePath : filesOfType) {
                            String newDirPath = excelToCSV(filePath);
                            filesToRemove.add(filePath);
                            try (Stream<Path> files = Files.list(Path.of(newDirPath))) {
                                files.forEach(file -> filesToAdd.add(file.toString()));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        filesOfType.removeAll(filesToRemove);
                        filesOfType.addAll(filesToAdd);
                        break;
                    case "ESRI Shapefile":
                        filesOfType.removeIf(file -> !file.endsWith(".shp"));
                        break;
                    default:
                        break;
                }

                for (String filePath : filesOfType) {
                    uploadVectorToPostGIS(database, schema, layerName, filePath, options, append);
                    // If inserting multiple sources into a single layer then ensure subsequent
                    // files are appended.
                    if (null != layerName) {
                        append = true;
                    }
                }
            }
        }
    }

    public void uploadVectorFileToPostGIS(String database, String schema, String layerName, String filePath,
            Ogr2OgrOptions options, boolean append) {

        try (TempDir tmpDir = makeLocalTempDir()) {
            Path sourcePath = Path.of(filePath);
            tmpDir.copyFrom(sourcePath);
            uploadVectorToPostGIS(database, schema, layerName,
                    tmpDir.getPath().resolve(sourcePath.getFileName()).toString(),
                    options, append);
        }
    }

    public void uploadVectorURLToPostGIS(String database, String schema, String layerName, String url,
            Ogr2OgrOptions options, boolean append) {
        uploadVectorToPostGIS(database, schema, layerName, url, options, append);
    }

    private void uploadVectorToPostGIS(String database, String schema, String layerName, String filePath,
            Ogr2OgrOptions options, boolean append) {

        options.setSchema(schema);

        String containerId = getContainerId(GDAL);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        String execId = createComplexCommand(containerId, options.generateCommand(
                layerName, append,
                filePath, computePGSQLSourceString(database)))
                .withOutputStream(outputStream)
                .withErrorStream(errorStream)
                .withEnvVars(options.getEnv())
                .withEvaluationTimeout(300)
                .exec();
        handleErrors(errorStream, execId, logger);
    }

    private String excelToCSV(String filePath) {
        String containerId = getContainerId(GDAL);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        String outputDirectory = FileUtils.removeExtension(filePath);
        String execId = createComplexCommand(containerId, "ogr2ogr",
                "-oo", "HEADERS=FORCE",
                "-f", "CSV",
                outputDirectory, // all sheets get put as individual csv into directory with same name as input
                                 // file
                filePath)
                .withOutputStream(outputStream)
                .withErrorStream(errorStream)
                .withEvaluationTimeout(300)
                .exec();
        handleErrors(errorStream, execId, logger);
        return outputDirectory;
    }

    public void uploadRasterFilesToPostGIS(String database, String schema, String layerName,
            String dirPath, GDALOptions<?> gdalOptions, MultidimSettings mdimSettings, boolean append) {

        String gdalContainerId = getContainerId(GDAL);
        String postGISContainerId = getContainerId(POSTGIS);

        try (TempDir tempDir = makeLocalTempDir()) {

            tempDir.copyFrom(Path.of(dirPath));
            List<String> postgresFiles = convertRastersToGeoTiffs(gdalContainerId, database, schema, layerName, tempDir,
                    gdalOptions, mdimSettings);

            ensurePostGISRasterSupportEnabled(postGISContainerId, database);
            uploadRasters(postGISContainerId, database, schema, layerName, postgresFiles, append);
        }
    }

    private Multimap<String, String> findGeoFiles(String containerId, String dirPath) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        // NB In contrast to what the GDAL documentation claims, this applies not only
        // to raster files
        // but also vector files. -fr returns both directories and files.
        String execId = createComplexCommand(containerId, "gdalmanage", "identify", "-fr", dirPath)
                .withOutputStream(outputStream)
                .withErrorStream(errorStream)
                .exec();
        handleErrors(errorStream, execId, logger);

        // Directories are filtered out from the result

        Multimap<String, String> foundGeoFiles = ArrayListMultimap.create();
        outputStream.toString().lines().forEach(entry -> {
            String[] parts = entry.split(": ");
            if (2 == parts.length && Files.isRegularFile(Path.of(parts[0]))) {
                foundGeoFiles.put(parts[1], parts[0]);
            }
        });
        return foundGeoFiles;
    }

    private void addCustomCRStoPostGis(String gdalContainerId, String filePath, String databaseName, String newSrid) {

        String detectedSrid = getDetectedSrid(gdalContainerId, filePath);

        if (detectedSrid.equals("EPSG:-1")) {
            logger.info("Unknown CRS detected, adding custom projection to postGIS and GeoServer");

            String proj4String = getProj4String(gdalContainerId, filePath);
            String wktString = getWktString(gdalContainerId, filePath);

            String[] sridAuthNameArray;
            try {
                sridAuthNameArray = newSrid.split(":");
                String authName = sridAuthNameArray[0];
                String srid = sridAuthNameArray[1];
                PostGISClient.getInstance().addProjectionsToPostgis(databaseName, proj4String,
                        wktString,
                        authName, srid);
                GeoServerClient.getInstance().addProjectionsToGeoserver(wktString, srid);
            } catch (NullPointerException ex) {
                throw new RuntimeException(
                        "Custom CRS not specified, add \"sridOut\": \"<AUTH>:<123456>\" to GDAL...Options node.", ex);
            }
        }
    }

    private String getDetectedSrid(String gdalContainerId, String filePath) {
        Dataset dataset = gdal.Open(filePath);
        if (null == dataset) {
            return "EPSG:-1";
        }

        try {
            String projection = dataset.GetProjection();
            if (null == projection || projection.isBlank()) {
                return "EPSG:-1";
            }

            SpatialReference spatialReference = new SpatialReference(projection);
            try {
                spatialReference.AutoIdentifyEPSG();
                String authorityName = spatialReference.GetAuthorityName(null);
                String authorityCode = spatialReference.GetAuthorityCode(null);
                if (null != authorityName && null != authorityCode) {
                    return authorityName + ":" + authorityCode;
                }
                return "EPSG:-1";
            } finally {
                spatialReference.delete();
            }
        } finally {
            dataset.delete();
        }
    }

    private String getProj4String(String gdalContainerId, String filePath) {
        Dataset dataset = gdal.Open(filePath);
        if (null == dataset) {
            return "";
        }

        try {
            SpatialReference spatialReference = new SpatialReference(dataset.GetProjection());
            try {
                return spatialReference.ExportToProj4();
            } finally {
                spatialReference.delete();
            }
        } finally {
            dataset.delete();
        }
    }

    private String getWktString(String gdalContainerId, String filePath) {
        Dataset dataset = gdal.Open(filePath);
        if (null == dataset) {
            return "";
        }

        try {
            SpatialReference spatialReference = new SpatialReference(dataset.GetProjection());
            try {
                return spatialReference.ExportToWkt();
            } finally {
                spatialReference.delete();
            }
        } finally {
            dataset.delete();
        }
    }

    private JSONArray getTimeFromGdalmdiminfo(String timeArrayName, String filePath) {
        Dataset dataset = gdal.Open(filePath);
        if (null == dataset) {
            throw new RuntimeException("Failed to open multidimensional raster '" + filePath + "'.");
        }

        String inputString;
        try {
            MultiDimInfoOptions infoOptions = new MultiDimInfoOptions(toVector("-detailed", "-array", timeArrayName));
            try {
                inputString = gdal.GDALMultiDimInfo(dataset, infoOptions).replace(" ", "");
            } finally {
                infoOptions.delete();
            }
        } finally {
            dataset.delete();
        }

        return new JSONObject(inputString).getJSONArray("values");
    }

    private List<String> multipleGeoTiffRastersFromMultiDim(MultidimSettings mdimSettings, String filePath,
            Path outputDirectory, String layerName, JSONArray timeArray) {

        String variableArrayName = mdimSettings.getLayerArrayName();
        String dateTimeFormat = mdimSettings.getTimeOptions().getFormat();

        List<String> filenames = new ArrayList<>(timeArray.length());

        String inputRasterFilePath = "NETCDF:" + filePath + ":" + variableArrayName;
        Dataset sourceDataset = gdal.Open(inputRasterFilePath);
        if (null == sourceDataset) {
            throw new RuntimeException(
                    "Failed to open multidimensional raster subdataset '" + inputRasterFilePath + "'.");
        }

        try {
            for (int index = 0; index < timeArray.length(); index++) {

                String filename;
                if (null != dateTimeFormat) {
                    filename = variableArrayName + "_" + timeArray.getString(index) + ".tif";
                } else {
                    filename = variableArrayName + "_" + (index + 1) + ".tif";
                }
                filenames.add(filename);
                String outputRasterFilePath = outputDirectory.resolve(filename).toString();

                WarpOptions warpOptions = new WarpOptions(toVector(
                        "-srcband", Integer.toString(index + 1),
                        "-t_srs", "EPSG:4326",
                        "-r", "cubicspline",
                        "-wo", "OPTIMIZE_SIZE=YES",
                        "-multi",
                        "-wo", "NUM_THREADS=ALL_CPUS"));
                try {
                    Dataset outputDataset = gdal.Warp(outputRasterFilePath, new Dataset[] { sourceDataset },
                            warpOptions);
                    if (null == outputDataset) {
                        throw new RuntimeException(
                                "Failed to warp '" + inputRasterFilePath + "' to '" + outputRasterFilePath + "'.");
                    }
                    outputDataset.delete();
                } finally {
                    warpOptions.delete();
                }
            }
        } finally {
            sourceDataset.delete();
        }

        return filenames;
    }

    private String getRasterTimeSqlType(String dateTimeFormat) {
        return (null != dateTimeFormat) ? "TIMESTAMPTZ" : "TEXT";
    }

    private String getRasterTimeSQLValues(String dateTimeFormat, String timeZone, JSONArray timeArray, String arrayName,
            Map<String, Integer> postgresOutputPathsAndNBands) {
        StringJoiner values = new StringJoiner(","); // SQL needs row1,...,rowN

        Iterator<String> filenames = postgresOutputPathsAndNBands.entrySet().stream().sequential()
                .flatMap(entry -> Collections
                        .nCopies(entry.getValue(), "'" + Paths.get(entry.getKey()).getFileName() + "'").stream())
                .collect(Collectors.toList()).iterator();

        Iterator<String> bands = postgresOutputPathsAndNBands.values().stream().sequential()
                .flatMap(nBands -> Stream.iterate(1, n -> n + 1).limit(nBands).map(band -> "'" + band + "'"))
                .collect(Collectors.toList()).iterator();

        ArrayList<String> dateTimesLists = new ArrayList<>();
        ArrayList<String> labelList = new ArrayList<>();
        if (null != dateTimeFormat) {
            DateTimeParser dateTimeParser = new DateTimeParser(dateTimeFormat, timeZone);
            for (int index = 0; index < timeArray.length(); index++) {
                // Convert the time from "dateTimeFormat" format to a format suitable for
                // PostGIS
                ZonedDateTime zonedDateTime = dateTimeParser.parse(timeArray.getString(index));
                String datetime = "'" + zonedDateTime.toInstant().toString() + "'";
                dateTimesLists.add(datetime);
                labelList.add(datetime);
            }
        } else {
            for (int index = 0; index < timeArray.length(); index++) {
                String timeStringUnFormatted = timeArray.getString(index);
                String timeStringFormatted = "'"
                        + DateStringFormatter.customDateStringFormatter(timeStringUnFormatted, arrayName) + "'";
                dateTimesLists.add("lastval()::text");
                labelList.add(timeStringFormatted);
            }
        }
        ListIterator<String> dateTimes = dateTimesLists.listIterator();
        ListIterator<String> labels = labelList.listIterator();

        while (filenames.hasNext() && bands.hasNext() && dateTimes.hasNext() && labels.hasNext()) {
            StringJoiner row = new StringJoiner(",", "(", ")"); // SQL needs ('col1',...,'colM')
            row.add("DEFAULT");
            row.add(filenames.next());
            row.add(bands.next());
            row.add(dateTimes.next());
            row.add(labels.next());

            values.add(row.toString());
        }
        return values.toString();
    }

    private void createRasterTimesTable(String database, String layerName,
            Map<String, Integer> postgresOutputPathsAndNBands, JSONArray timeArray, TimeOptions timeOptions) {

        String dateTimeFormat = timeOptions.getFormat();
        String timeZone = timeOptions.getTimeZone();
        String arrayName = timeOptions.getArrayName();

        String postGISContainerId = getContainerId(POSTGIS);

        String timeSqlType = getRasterTimeSqlType(dateTimeFormat);

        String dataTimeSQLValues = getRasterTimeSQLValues(dateTimeFormat, timeZone, timeArray, arrayName,
                postgresOutputPathsAndNBands);

        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        String hereDocument = "CREATE TABLE IF NOT EXISTS \"" + layerName
                + "_times\" (\"index\" SERIAL, \"filename\" text, \"band\" integer, \"time\" " + timeSqlType
                + " CONSTRAINT time_key PRIMARY KEY, \"label\" text); "
                + "INSERT INTO \"" + layerName + "_times\" VALUES " + dataTimeSQLValues + ";";
        String execId = createComplexCommand(postGISContainerId,
                "psql", "-U", postgreSQLEndpoint.getUsername(), "-d", database, "-w")
                .withHereDocument(hereDocument)
                .withErrorStream(errorStream)
                .exec();

        handleErrors(errorStream, execId, logger);
        errorStream.reset();
    }

    private List<String> convertRastersToGeoTiffs(String gdalContainerId, String databaseName, String schemaName,
            String layerName, TempDir tempDir, GDALOptions<?> options, MultidimSettings mdimSettings) {

        Multimap<String, String> foundRasterFiles = findGeoFiles(gdalContainerId, tempDir.toString());
        Set<Path> createdDirectories = new HashSet<>();
        List<String> postgresFiles = new ArrayList<>();

        for (Map.Entry<String, Collection<String>> fileTypeEntry : foundRasterFiles.asMap().entrySet()) {
            String inputFormat = fileTypeEntry.getKey();
            for (String filePath : fileTypeEntry.getValue()) {

                if (null == options.getSridIn()) {
                    addCustomCRStoPostGis(gdalContainerId, filePath, databaseName, options.getSridOut());
                }

                postgresFiles.addAll(processFile(gdalContainerId, inputFormat, filePath, databaseName, schemaName,
                        layerName, tempDir, options, mdimSettings, createdDirectories));
            }
        }
        createdDirectories.forEach(
                directoryPath -> executeSimpleCommand(gdalContainerId, "chmod", "-R", "777", directoryPath.toString()));
        return postgresFiles;
    }

    private Collection<String> processFile(String gdalContainerId, String inputFormat, String filePath,
            String databaseName, String schemaName, String layerName, TempDir tempDir,
            GDALOptions<?> options, MultidimSettings mdimSettings, Set<Path> createdDirectories) {

        String postgresOutputPath;
        String geotiffsOutputPath = generateOutFilePath(tempDir.toString(), databaseName, schemaName, layerName,
                filePath, "geotiffs");
        Path geotiffsOutputDirectory = Paths.get(geotiffsOutputPath).getParent();

        List<Path> directoryPaths = new ArrayList<>();
        directoryPaths.add(geotiffsOutputDirectory);

        if (inputFormat.equals("netCDF")) {
            postgresOutputPath = generateOutFilePath(tempDir.toString(), databaseName, schemaName, layerName,
                    filePath, "multidim_geospatial");
            Path directoryPath = Paths.get(postgresOutputPath).getParent();
            directoryPaths.add(directoryPath);
        } else {
            postgresOutputPath = geotiffsOutputPath;
        }

        for (Path dirPath : directoryPaths) {
            if (createdDirectories.add(dirPath)) {
                makeDir(gdalContainerId, dirPath.toString());
                executeSimpleCommand(gdalContainerId, "chmod", "-R", "777", dirPath.toString());
            }
        }

        Collection<String> postgresOutputPaths;
        if (inputFormat.equals("netCDF")) {
            logger.info("netCDF found, uploading without translate and creating gdal virtual format .vrt file");
            copyMultiDimRasters(gdalContainerId, filePath, postgresOutputPath);

            String timeArrayName = mdimSettings.getTimeOptions().getArrayName();
            JSONArray timeArray = getTimeFromGdalmdiminfo(timeArrayName, filePath);

            List<String> geoTiffFilenames = multipleGeoTiffRastersFromMultiDim(mdimSettings, filePath,
                    geotiffsOutputDirectory, layerName, timeArray);

            Map<String, Integer> postgresOutputPathsAndNBands = multipleVrtRastersFromMultiDim(gdalContainerId,
                    mdimSettings, postgresOutputPath,
                    geoTiffFilenames);

            createRasterTimesTable(databaseName, layerName, postgresOutputPathsAndNBands, timeArray,
                    mdimSettings.getTimeOptions());

            postgresOutputPaths = postgresOutputPathsAndNBands.keySet();
        } else {
            postgresOutputPaths = generateGeoTiffRaster(gdalContainerId, inputFormat, filePath, postgresOutputPath,
                    options);
        }

        return postgresOutputPaths;
    }

    private List<String> generateGeoTiffRaster(String gdalContainerId, String inputFormat, String filePath,
            String postgresOutputPath, GDALOptions<?> options) {

        Dataset sourceDataset = gdal.Open(filePath);
        if (null == sourceDataset) {
            throw new RuntimeException("Failed to open raster source '" + filePath + "'.");
        }

        try {
            TranslateOptions translateOptions = new TranslateOptions(commandToOptions(options.generateCommand(
                    inputFormat,
                    filePath,
                    postgresOutputPath)));
            try {
                Dataset outputDataset = gdal.Translate(postgresOutputPath, sourceDataset, translateOptions);
                if (null == outputDataset) {
                    throw new RuntimeException(
                            "Failed to translate raster '" + filePath + "' to '" + postgresOutputPath + "'.");
                }
                outputDataset.delete();
            } finally {
                translateOptions.delete();
            }
        } finally {
            sourceDataset.delete();
        }

        return List.of(postgresOutputPath);
    }

    private void copyMultiDimRasters(String gdalContainerId, String filePath, String postgresOutputPath) {
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        String execId = createComplexCommand(gdalContainerId, "cp",
                filePath,
                postgresOutputPath)
                .withErrorStream(errorStream)
                .withEvaluationTimeout(300)
                .exec();
        handleErrors(errorStream, execId, logger);
    }

    private Map<String, Integer> multipleVrtRastersFromMultiDim(String gdalContainerId, MultidimSettings mdimSettings,
            String postgresOutputPath, List<String> geoTiffFilenames) {
        Map<String, Integer> postgresOutputPathsAndNBands = new LinkedHashMap<>();

        String inputRasterFilePath = "NETCDF:" + postgresOutputPath + ":" + mdimSettings.getLayerArrayName();
        Dataset sourceDataset = gdal.Open(inputRasterFilePath);
        if (null == sourceDataset) {
            throw new RuntimeException(
                    "Failed to open multidimensional raster subdataset '" + inputRasterFilePath + "'.");
        }

        try {
            for (int index = 0; index < geoTiffFilenames.size(); ++index) {
                String geoTiffFilename = geoTiffFilenames.get(index);
                String outputRasterFilePath = Paths.get(postgresOutputPath)
                        .resolveSibling(FileUtils.replaceExtension(geoTiffFilename, "vrt"))
                        .toString();

                WarpOptions warpOptions = new WarpOptions(toVector(
                        "-srcband", Integer.toString(index + 1),
                        "-t_srs", "EPSG:4326",
                        "-wo", "OPTIMIZE_SIZE=YES"));
                try {
                    Dataset outputDataset = gdal.Warp(outputRasterFilePath, new Dataset[] { sourceDataset },
                            warpOptions);
                    if (null == outputDataset) {
                        throw new RuntimeException(
                                "Failed to warp '" + inputRasterFilePath + "' to '" + outputRasterFilePath + "'.");
                    }
                    outputDataset.delete();
                } finally {
                    warpOptions.delete();
                }

                postgresOutputPathsAndNBands.put(outputRasterFilePath, 1);
            }
        } finally {
            sourceDataset.delete();
        }
        return postgresOutputPathsAndNBands;
    }

    private void ensurePostGISRasterSupportEnabled(String postGISContainerId, String database) {
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        String execId = createComplexCommand(postGISContainerId,
                "psql", "-U", postgreSQLEndpoint.getUsername(), "-d", database, "-w")
                .withHereDocument("CREATE EXTENSION IF NOT EXISTS postgis_raster;" +
                        "ALTER DATABASE \"" + database + "\" SET postgis.enable_outdb_rasters = True;" +
                        "ALTER DATABASE \"" + database + "\" SET postgis.gdal_enabled_drivers = 'GTiff netCDF VRT';")
                .withErrorStream(errorStream)
                .exec();
        handleErrors(errorStream, execId, logger);
    }

    private void uploadRasters(String postGISContainerId, String database, String schemaName, String layerName,
            List<String> geotiffFiles, boolean append) {

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        String mode = append ? "-a" : "-d";
        String execId = createComplexCommand(postGISContainerId, "bash", "-c",
                "(which raster2pgsql || (apt update && apt install -y postgis && rm -rf /var/lib/apt/lists/*)) && " +
                // https://postgis.net/docs/using_raster_dataman.html#RT_Raster_Loader
                        "raster2pgsql " + mode + " -C -t auto -R -F -q -I -M -Y"
                        + geotiffFiles.stream().collect(Collectors.joining("' '", " '", "' "))
                        + "\"" + schemaName + "\".\"" + layerName + "\""
                        + " | psql -U " + postgreSQLEndpoint.getUsername() + " -d " + database + " -w")
                .withOutputStream(outputStream)
                .withErrorStream(errorStream)
                .withEvaluationTimeout(3600)
                .exec();

        handleErrors(errorStream, execId, logger);
    }

    // add .tif extension on files in geotiffs directory

    // return filePath for any file to either "geotiffs" or "multidim_geospatial"
    private static String generateOutFilePath(String basePathIn, String databaseName, String schemaName,
            String layerName, String filePath, String destinationDirectory) {
        if (destinationDirectory.equals("multidim_geospatial")) {
            // the Path object of multidim_geospatial
            Path multiDimOutDirPath = Path.of(StackClient.MULTIDIM_GEOSPATIAL_DIR, databaseName, schemaName, layerName);
            return multiDimOutDirPath.resolve(Path.of(basePathIn).relativize(Path.of(filePath)))
                    .toString();
        } else {
            // alternative should be destinationDirectory.equals("geotiffs"), and this shall
            // be default
            // returns the Path object of geotiffs
            Path rasterOutDirPath = Path.of(StackClient.GEOTIFFS_DIR, databaseName, schemaName, layerName);
            String rasterOutFilePath = rasterOutDirPath.resolve(Path.of(basePathIn).relativize(Path.of(filePath)))
                    .toString();
            return FileUtils.replaceExtension(rasterOutFilePath, "tif");

        }
    }

}
