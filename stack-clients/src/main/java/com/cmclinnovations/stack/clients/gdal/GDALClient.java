package com.cmclinnovations.stack.clients.gdal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;
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

    private static final String GDALSRSINFO = "gdalsrsinfo";

    private static final Logger logger = LoggerFactory.getLogger(GDALClient.class);

    private final PostGISEndpointConfig postgreSQLEndpoint;

    private static GDALClient instance = null;

    public static GDALClient getInstance() {
        if (null == instance) {
            instance = new GDALClient();
        }
        return instance;
    }

    private GDALClient() {
        postgreSQLEndpoint = readEndpointConfig(EndpointNames.POSTGIS, PostGISEndpointConfig.class);
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
            Multimap<String, String> foundGeoFiles = findGeoFiles(tmpDir.toString());
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
                                filesToAdd.addAll(files.map(Object::toString)
                                        .collect(Collectors.toList()));
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

        List<String> command = Arrays.asList(options.generateCommand(
                layerName, append,
                filePath, computePGSQLSourceString(database)));

        CommandResult result = runLocalCommand(
                command,
                options.getEnv(),
                null,
                300);

        // Some GDAL builds do not support '-if'; if encountered, retry once without it.
        if (result.exitCode != 0 && result.stderr.contains("Unknown option name '-if'")) {
            logger.warn("ogr2ogr does not support '-if' in this runtime; retrying without that option.");
            List<String> fallbackCommand = stripOptionAndValue(command, "-if");
            result = runLocalCommand(
                    fallbackCommand,
                    options.getEnv(),
                    null,
                    300);
        }

        handleLocalCommandErrors(result, "ogr2ogr");
    }

    private List<String> stripOptionAndValue(List<String> command, String option) {
        List<String> stripped = new ArrayList<>();
        boolean skipNext = false;
        for (String token : command) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (option.equals(token)) {
                skipNext = true;
                continue;
            }
            stripped.add(token);
        }
        return stripped;
    }

    private String excelToCSV(String filePath) {
        String outputDirectory = FileUtils.removeExtension(filePath);
        CommandResult result = runLocalCommand(
                List.of("ogr2ogr",
                        "-oo", "HEADERS=FORCE",
                        "-f", "CSV",
                        outputDirectory,
                        filePath),
                Map.of(),
                null,
                300);
        handleLocalCommandErrors(result, "ogr2ogr");
        return outputDirectory;
    }

    public void uploadRasterFilesToPostGIS(String database, String schema, String layerName,
            String dirPath, GDALOptions<?> gdalOptions, MultidimSettings mdimSettings, boolean append) {
        try (TempDir tempDir = makeLocalTempDir()) {

            tempDir.copyFrom(Path.of(dirPath));
            List<String> postgresFiles = convertRastersToGeoTiffs(database, schema, layerName, tempDir,
                    gdalOptions, mdimSettings);

            ensurePostGISRasterSupportEnabled(database);
            uploadRasters(database, schema, layerName, postgresFiles, append);
        }
    }

    private Multimap<String, String> findGeoFiles(String dirPath) {
        // NB In contrast to what the GDAL documentation claims, this applies not only
        // to raster files
        // but also vector files. -fr returns both directories and files.
        CommandResult result = runLocalCommand(
                List.of("gdalmanage", "identify", "-fr", dirPath),
                Map.of(),
                null,
                60);
        handleLocalCommandErrors(result, "gdalmanage identify");

        // Directories are filtered out from the result

        return result.stdout.lines()
                .map(entry -> entry.split(": "))
                .filter(a -> Files.isRegularFile(Path.of(a[0])))
                .collect(ArrayListMultimap::create,
                        (m, pair) -> m.put(pair[1], pair[0]),
                        Multimap::putAll);
    }

    private void addCustomCRStoPostGis(String filePath, String databaseName, String newSrid) {

        String detectedSrid = getDetectedSrid(filePath);

        if (detectedSrid.equals("EPSG:-1")) {
            logger.info("Unknown CRS detected, adding custom projection to postGIS and GeoServer");

            String proj4String = getProj4String(filePath);
            String wktString = getWktString(filePath);

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

    private String getDetectedSrid(String filePath) {
        CommandResult result = runLocalCommand(
                List.of(GDALSRSINFO, "-o", "epsg", filePath),
                Map.of(),
                null,
                60);
        handleLocalCommandErrors(result, "gdalsrsinfo epsg");
        return result.stdout.replace("\n", "");
    }

    private String getProj4String(String filePath) {
        CommandResult result = runLocalCommand(
                List.of(GDALSRSINFO, "-o", "proj4", filePath),
                Map.of(),
                null,
                60);
        handleLocalCommandErrors(result, "gdalsrsinfo proj4");
        return result.stdout.replace("\n", "");
    }

    private String getWktString(String filePath) {
        CommandResult result = runLocalCommand(
                List.of(GDALSRSINFO, "-o", "wkt", "--single-line", filePath),
                Map.of(),
                null,
                60);
        handleLocalCommandErrors(result, "gdalsrsinfo wkt");
        return result.stdout;
    }

    private JSONArray getTimeFromGdalmdiminfo(String timeArrayName, String filePath) {
        CommandResult result = runLocalCommand(
                List.of("gdalmdiminfo", "-detailed", "-array", timeArrayName, filePath),
                Map.of(),
                null,
                120);
        handleLocalCommandErrors(result, "gdalmdiminfo");

        String inputString = result.stdout.replace(" ", "");
        return new JSONObject(inputString).getJSONArray("values");
    }

    private List<String> multipleGeoTiffRastersFromMultiDim(MultidimSettings mdimSettings, String filePath,
            Path outputDirectory, String layerName, JSONArray timeArray) {

        String variableArrayName = mdimSettings.getLayerArrayName();
        String dateTimeFormat = mdimSettings.getTimeOptions().getFormat();

        List<String> filenames = new ArrayList<>(timeArray.length());

        String inputRasterFilePath = "NETCDF:" + filePath + ":" + variableArrayName;

        for (int index = 0; index < timeArray.length(); index++) {

            String filename;
            if (null != dateTimeFormat) {
                filename = variableArrayName + "_" + timeArray.getString(index) + ".tif";
            } else {
                filename = variableArrayName + "_" + (index + 1) + ".tif";
            }
            filenames.add(filename);
            String outputRasterFilePath = outputDirectory.resolve(filename).toString();

            CommandResult result = runLocalCommand(
                    List.of("gdalwarp",
                            "-srcband", Integer.toString(index + 1),
                            "-t_srs", "EPSG:4326",
                            "-r", "cubicspline",
                            "-wo", "OPTIMIZE_SIZE=YES",
                            "-multi",
                            "-wo", "NUM_THREADS=ALL_CPUS",
                            inputRasterFilePath,
                            outputRasterFilePath),
                    Map.of(),
                    null,
                    300);
            handleLocalCommandErrors(result, "gdalwarp");
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

        String timeSqlType = getRasterTimeSqlType(dateTimeFormat);

        String dataTimeSQLValues = getRasterTimeSQLValues(dateTimeFormat, timeZone, timeArray, arrayName,
                postgresOutputPathsAndNBands);

        PostGISClient postGISClient = PostGISClient.getInstance();
        postGISClient.executeUpdate(database,
                "CREATE TABLE IF NOT EXISTS \"" + layerName
                        + "_times\" (\"index\" SERIAL, \"filename\" text, \"band\" integer, \"time\" "
                        + timeSqlType
                        + " CONSTRAINT time_key PRIMARY KEY, \"label\" text)");
        postGISClient.executeUpdate(database,
                "INSERT INTO \"" + layerName + "_times\" VALUES " + dataTimeSQLValues);
    }

    private List<String> convertRastersToGeoTiffs(String databaseName, String schemaName,
            String layerName, TempDir tempDir, GDALOptions<?> options, MultidimSettings mdimSettings) {

        Multimap<String, String> foundRasterFiles = findGeoFiles(tempDir.toString());
        Set<Path> createdDirectories = new HashSet<>();
        List<String> postgresFiles = new ArrayList<>();

        for (Map.Entry<String, Collection<String>> fileTypeEntry : foundRasterFiles.asMap().entrySet()) {
            String inputFormat = fileTypeEntry.getKey();
            for (String filePath : fileTypeEntry.getValue()) {

                if (null == options.getSridIn()) {
                    addCustomCRStoPostGis(filePath, databaseName, options.getSridOut());
                }

                postgresFiles.addAll(processFile(inputFormat, filePath, databaseName, schemaName,
                        layerName, tempDir, options, mdimSettings, createdDirectories));
            }
        }
        createdDirectories.forEach(directoryPath -> directoryPath.toFile().setReadable(true, false));
        return postgresFiles;
    }

    private Collection<String> processFile(String inputFormat, String filePath,
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
                try {
                    Files.createDirectories(dirPath);
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to create GDAL output directory '" + dirPath + "'.", ex);
                }
            }
        }

        Collection<String> postgresOutputPaths;
        if (inputFormat.equals("netCDF")) {
            logger.info("netCDF found, uploading without translate and creating gdal virtual format .vrt file");
            copyMultiDimRasters(filePath, postgresOutputPath);

            String timeArrayName = mdimSettings.getTimeOptions().getArrayName();
            JSONArray timeArray = getTimeFromGdalmdiminfo(timeArrayName, filePath);

            List<String> geoTiffFilenames = multipleGeoTiffRastersFromMultiDim(mdimSettings, filePath,
                    geotiffsOutputDirectory, layerName, timeArray);

            Map<String, Integer> postgresOutputPathsAndNBands = multipleVrtRastersFromMultiDim(
                    mdimSettings, postgresOutputPath,
                    geoTiffFilenames);

            createRasterTimesTable(databaseName, layerName, postgresOutputPathsAndNBands, timeArray,
                    mdimSettings.getTimeOptions());

            postgresOutputPaths = postgresOutputPathsAndNBands.keySet();
        } else {
            postgresOutputPaths = generateGeoTiffRaster(inputFormat, filePath, postgresOutputPath,
                    options);
        }

        return postgresOutputPaths;
    }

    private List<String> generateGeoTiffRaster(String inputFormat, String filePath,
            String postgresOutputPath, GDALOptions<?> options) {

        CommandResult result = runLocalCommand(
                Arrays.asList(options.generateCommand(
                        inputFormat,
                        filePath,
                        postgresOutputPath)),
                options.getEnv(),
                null,
                300);
        handleLocalCommandErrors(result, "gdal_translate/gdalwarp");

        return List.of(postgresOutputPath);
    }

    private void copyMultiDimRasters(String filePath, String postgresOutputPath) {
        try {
            Files.copy(Path.of(filePath), Path.of(postgresOutputPath), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to copy multidimensional raster from '" + filePath + "' to '"
                    + postgresOutputPath + "'.", ex);
        }
    }

    private Map<String, Integer> multipleVrtRastersFromMultiDim(MultidimSettings mdimSettings,
            String postgresOutputPath, List<String> geoTiffFilenames) {
        Map<String, Integer> postgresOutputPathsAndNBands = new LinkedHashMap<>();

        String inputRasterFilePath = "NETCDF:" + postgresOutputPath + ":" + mdimSettings.getLayerArrayName();

        for (int index = 0; index < geoTiffFilenames.size(); ++index) {
            String geoTiffFilename = geoTiffFilenames.get(index);
            String outputRasterFilePath = Paths.get(postgresOutputPath)
                    .resolveSibling(FileUtils.replaceExtension(geoTiffFilename, "vrt"))
                    .toString();
            CommandResult result = runLocalCommand(
                    List.of("gdalwarp",
                            "-srcband", Integer.toString(index + 1),
                            "-t_srs", "EPSG:4326",
                            "-wo", "OPTIMIZE_SIZE=YES",
                            inputRasterFilePath,
                            outputRasterFilePath),
                    Map.of(),
                    null,
                    300);
            handleLocalCommandErrors(result, "gdalwarp");

            postgresOutputPathsAndNBands.put(outputRasterFilePath, 1);
        }
        return postgresOutputPathsAndNBands;
    }

    private void ensurePostGISRasterSupportEnabled(String database) {
        PostGISClient postGISClient = PostGISClient.getInstance();
        postGISClient.executeUpdate(database, "CREATE EXTENSION IF NOT EXISTS postgis_raster");
        postGISClient.executeUpdate(database,
                "ALTER DATABASE \"" + database + "\" SET postgis.enable_outdb_rasters = True");
        postGISClient.executeUpdate(database,
                "ALTER DATABASE \"" + database + "\" SET postgis.gdal_enabled_drivers = 'GTiff netCDF VRT'");
    }

    private void uploadRasters(String database, String schemaName, String layerName,
            List<String> geotiffFiles, boolean append) {

        String mode = append ? "-a" : "-d";
        String filesArg = geotiffFiles.stream().map(GDALClient::shellQuote).collect(Collectors.joining(" "));
        String command = "raster2pgsql " + mode + " -C -t auto -R -F -q -I -M -Y "
                + filesArg + " \"" + schemaName + "\".\"" + layerName + "\""
                + " | psql -h " + shellQuote(postgreSQLEndpoint.getHostName())
                + " -p " + shellQuote(postgreSQLEndpoint.getPort())
                + " -U " + shellQuote(postgreSQLEndpoint.getUsername())
                + " -d " + shellQuote(database)
                + " -w";
        CommandResult result = runLocalCommand(
                List.of("bash", "-lc", command),
                Map.of("PGPASSWORD", postgreSQLEndpoint.getPassword()),
                null,
                3600);
        handleLocalCommandErrors(result, "raster2pgsql");
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private CommandResult runLocalCommand(List<String> command, Map<String, String> env, String stdin,
            long timeoutSeconds) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(env);
        Process process;
        try {
            process = processBuilder.start();
            if (null != stdin) {
                process.getOutputStream().write(stdin.getBytes());
            }
            process.getOutputStream().close();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Timed out running local command: " + String.join(" ", command));
            }
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to run local command: " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running local command: " + String.join(" ", command), ex);
        }
    }

    private void handleLocalCommandErrors(CommandResult result, String commandName) {
        if (result.exitCode != 0) {
            throw new RuntimeException(commandName + " returned '" + result.exitCode
                    + "' and wrote the following to stderr:\n" + result.stderr);
        }
        if (!result.stderr.isBlank()) {
            logger.warn("{} returned '0' but wrote the following to stderr:\n{}", commandName, result.stderr);
        }
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
