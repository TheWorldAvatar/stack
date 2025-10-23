package com.cmclinnovations.stack.clients.timeseries;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.ontop.OntopClient;
import com.cmclinnovations.stack.services.OntopService;
import com.cmclinnovations.stack.services.ServiceManager;
import com.cmclinnovations.stack.services.config.ServiceConfig;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesRDBClientOntop;

public class TimeSeriesRDBClient<T> extends TimeSeriesRDBClientOntop<T> {

    /**
     * Logger for error output.
     */
    private static final Logger LOGGER = LogManager.getLogger(TimeSeriesRDBClient.class);

    // IRI of temporal reference system, used in ontop mapping to indicate reference
    // of time, e.g. Unix
    private String trsIri;

    // name of ontop container
    private String ontopName = "ontop-timeseries";

    public TimeSeriesRDBClient(Class<T> timeClass) {
        super(timeClass);
    }

    public void setTrs(String trsIri) {
        this.trsIri = trsIri;
    }

    public void setOntopName(String ontopName) {
        this.ontopName = ontopName;
    }

    @Override
    public List<Integer> bulkInitTimeSeriesTable(List<List<String>> dataIRIs, List<List<Class<?>>> dataClasses,
            List<String> tsIRIs, Integer srid, Connection conn) {
        List<Integer> result = super.bulkInitTimeSeriesTable(dataIRIs, dataClasses, tsIRIs, srid, conn);

        // spin up a new ontop container if it does not exist
        configureOntop();

        return result;
    }

 public void configureOntop() {
        String stackName = System.getenv("STACK_NAME");
        if (stackName == null) {
            LOGGER.warn("STACK_NAME not detected, skipping Ontop intialisation");
            return;
        }

        ServiceManager serviceManager = new ServiceManager(false);

        ServiceConfig newOntopServiceConfig = serviceManager.duplicateServiceConfig(EndpointNames.ONTOP, ontopName);
        newOntopServiceConfig.setEnvironmentVariable(OntopService.ONTOP_DB_NAME, "postgres");

        newOntopServiceConfig.getEndpoints()
                .replaceAll((endpointName, connection) -> new com.cmclinnovations.stack.services.config.Connection(
                        connection.getUrl(),
                        connection.getUri(),
                        URI.create(connection.getExternalPath().toString()
                                .replace(EndpointNames.ONTOP, ontopName))));
        serviceManager.initialiseService(stackName, ontopName);

        OntopClient ontopClient = OntopClient.getInstance(ontopName);
        String ontopUrl = ontopClient.readEndpointConfig().getUrl();

        // check if mapping exists and only upload mapping if it does not exist
        RemoteStoreClient remoteStoreClient = new RemoteStoreClient(ontopUrl);
        String query = "SELECT * WHERE { ?x ?y ?z } LIMIT 1";

        JSONArray queryResult = null;

        // try to send a query to ontop, catch exceptions in case it is still
        // initialising
        int attempts = 0;
        int maxAttempts = 5;
        while (attempts < maxAttempts) {
            try {
                queryResult = remoteStoreClient.executeQuery(query);
                break;
            } catch (Exception e) {
                attempts++;
                try {
                    Thread.sleep(10_000); // wait 10 seconds before retrying
                } catch (Exception ie) {
                    throw new JPSRuntimeException("Interrupted while retrying query to ontop", e);
                }
            }
        }

        if (queryResult == null) {
            throw new JPSRuntimeException("Failed to execute query after " + maxAttempts + " attempts.");
        }

        if (queryResult.isEmpty()) {
            // create temporary file for ontop mapping
            Path tempDir;
            try {
                tempDir = Files.createTempDirectory("timeseries_ontop_");
            } catch (IOException e) {
                throw new JPSRuntimeException("Failed to create temporary directory to save ontop file", e);
            }

            String obda = prepareMapping();

            Path filePath = tempDir.resolve("ontop.obda");
            try {
                Files.write(filePath, obda.getBytes());
            } catch (IOException e) {
                throw new JPSRuntimeException("Failed to write ontop mapping into temporary folder", e);
            }

            // sends obda to container
            ontopClient.updateOBDA(filePath);

            // clean up
            filePath.toFile().delete();
            tempDir.toFile().delete();
        }
    }

    /**
     * set TRS in Ontop mapping
     * 
     * @return
     */
    private String prepareMapping() {
        String unixTRS = "http://dbpedia.org/resource/Unix_time";
        String generic = "http://example.org/TRS_placeholder";

        String obdaTemplate;
        // read template from resources folder
        try (InputStream is = TimeSeriesRDBClientOntop.class.getResourceAsStream("timeseries_ontop_template.obda")) {
            obdaTemplate = IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JPSRuntimeException("Error while reading timeseries_ontop_template.obda", e);
        }

        if (getTimeClass() == Instant.class) {
            LOGGER.info("Time class is Instant, TRS is set to {}", unixTRS);
            obdaTemplate = obdaTemplate.replace("[TRS_REPLACE]", unixTRS);
        } else if (trsIri == null) {
            obdaTemplate = obdaTemplate.replace("[TRS_REPLACE]", generic);
        } else {
            obdaTemplate = obdaTemplate.replace("[TRS_REPLACE]", trsIri);
        }

        return obdaTemplate;
    }

}
