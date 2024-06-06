package com.cmclinnovations.stack.clients.core.datasets;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphClient;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.geoserver.GeoServerClient;
import com.cmclinnovations.stack.clients.geoserver.StaticGeoServerData;
import com.cmclinnovations.stack.clients.ontop.OntopClient;
import com.cmclinnovations.stack.clients.postgis.PostGISClient;
import com.cmclinnovations.stack.services.OntopService;
import com.cmclinnovations.stack.services.ServiceManager;
import com.cmclinnovations.stack.services.config.Connection;
import com.cmclinnovations.stack.services.config.ServiceConfig;

public class DatasetLoader {


    private static final ServiceManager serviceManager = new ServiceManager(false);

    private DatasetLoader() {
    }

    public static void loadInputDatasets() {

        Map<String, Dataset> allDatasets = DatasetReader.getAllDatasets();

        Stream<Dataset> selectedDatasets = DatasetReader.getStackSpecificDatasets(allDatasets);

        uploadDatasets(selectedDatasets);

    }

    public static void uploadDatasets(Collection<Dataset> selectedDatasets) {
        selectedDatasets.forEach(DatasetLoader::loadDataset);
    }

    public static void uploadDatasets(Stream<Dataset> selectedDatasets) {
        selectedDatasets.forEach(DatasetLoader::loadDataset);
    }

    public static void loadDataset(Dataset dataset) {
        Path directory = dataset.getDirectory();

        if (!dataset.isSkip()) {
            List<DataSubset> dataSubsets = dataset.getDataSubsets();
            // Ensure PostGIS database exists, if specified
            if (dataset.usesPostGIS()) {
                PostGISClient.getInstance().createDatabase(dataset.getDatabase());
            }

            // Ensure Blazegraph namespace exists, if specified
            if (dataset.usesBlazegraph()) {
                BlazegraphClient.getInstance().createNamespace(dataset.getNamespace(),
                        dataset.getNamespaceProperties());
            }

            if (dataset.usesGeoServer()) {
                GeoServerClient geoServerClient = GeoServerClient.getInstance();
                String workspaceName = dataset.getWorkspaceName();
                // Ensure GeoServer workspace exists
                geoServerClient.createWorkspace(workspaceName);
                // Upload styles to GeoServer
                dataset.getGeoserverStyles().forEach(style -> geoServerClient.loadStyle(style, workspaceName));
            }

            if (dataset.hasStaticGeoServerData()) {
                GeoServerClient geoServerClient = GeoServerClient.getInstance();
                StaticGeoServerData staticGeoServerData = dataset.getStaticGeoServerData();
                geoServerClient.loadIcons(directory, staticGeoServerData.getIconsDir());
                geoServerClient.loadOtherFiles(directory, staticGeoServerData.getOtherFiles());
            }

            dataSubsets.forEach(subset -> subset.load(dataset));

            if (dataset.usesOntop()) {
                String newOntopServiceName = dataset.getOntopName();

                ServiceConfig newOntopServiceConfig = serviceManager.duplicateServiceConfig(EndpointNames.ONTOP,
                        newOntopServiceName);

                newOntopServiceConfig.setEnvironmentVariable(OntopService.ONTOP_DB_NAME, dataset.getDatabase());
                newOntopServiceConfig.getEndpoints()
                        .replaceAll((endpointName, connection) -> new Connection(
                                connection.getUrl(),
                                connection.getUri(),
                                URI.create(connection.getExternalPath().toString()
                                        .replace(EndpointNames.ONTOP, newOntopServiceName))));

                serviceManager.initialiseService(StackClient.getStackName(), newOntopServiceName);

                List<String> ontopMappings = dataset.getOntopMappings();

                OntopClient ontopClient = OntopClient.getInstance(newOntopServiceName);
                ontopMappings.forEach(mapping -> ontopClient.updateOBDA(directory.resolve(mapping)));

                if (PostGISClient.DEFAULT_DATABASE_NAME.equals(dataset.getDatabase())) {
                    OntopClient defaultOntopClient = OntopClient.getInstance();
                    ontopMappings.forEach(mapping -> defaultOntopClient.updateOBDA(directory.resolve(mapping)));
                }
            }

            // record added datasets in the default kb namespace
            BlazegraphClient.getInstance().getRemoteStoreClient("kb")
                    .executeUpdate(new DCATUpdateQuery().getQueryStringForCataloging(dataset));
        }
    }

}
