package com.cmclinnovations.stack.clients.geoserver;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockserver.model.HttpResponse.response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockserver.model.HttpRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmclinnovations.stack.clients.mocks.MockGeoServer;
import com.cmclinnovations.stack.clients.mocks.MockPostGIS;
import com.cmclinnovations.stack.clients.mocks.MockServiceWithFileSystem;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.fasterxml.jackson.core.type.TypeReference;

@Testcontainers
public class GeoServerClientTest {

    private static final String SCHEMA_NAME = "public";
    private static final String DATABASE_NAME = "postgres";
    private static final String EXISTING_WORKSPACE = "existingWorkspace";
    private static final String NEW_WORKSPACE = "newWorkspace";
    private static final String EXISTING_COVERAGE_STORE = "existingCoverageStore";
    private static final String NEW_COVERAGE_STORE = "newCoverageStore";
    private static final String NEW_POSTGIS_STORE = "newPostgresStore";

    @AutoClose("stop")
    public static MockGeoServer mockGeoServer;
    @AutoClose
    public static MockPostGIS mockPostGIS;

    public static GeoServerClient geoServerClient;

    @Container
    static MockServiceWithFileSystem dummyGeoServerContainer = new MockServiceWithFileSystem("geoserver");

    @BeforeAll
    static void setup() throws MalformedURLException {
        mockPostGIS = new MockPostGIS();
        mockPostGIS.addOverride(postGISClientMock -> doNothing().when(postGISClientMock).createDatabase(anyString()));

        mockGeoServer = new MockGeoServer();
        geoServerClient = GeoServerClient.getInstance();
    }

    @AfterEach
    void verifyCalls() {
        mockGeoServer.verifyCalls();
        mockGeoServer.reset();
    }

    @Test
    void testAddProjectionsToGeoserver() {
        mockGeoServer.addExpectation("/rest/reload", "POST", 200);

        String wktString = "PROJCRS[\"OSGB36 / British National Grid\",BASEGEOGCRS[\"OSGB36\",DATUM[\"Ordnance Survey of Great Britain 1936\",ELLIPSOID[\"Airy 1830\",6377563.396,299.3249646,LENGTHUNIT[\"metre\",1]]],PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],ID[\"EPSG\",4277]],CONVERSION[\"British National Grid\",METHOD[\"Transverse Mercator\",ID[\"EPSG\",9807]],PARAMETER[\"Latitude of natural origin\",49,ANGLEUNIT[\"degree\",0.0174532925199433],ID[\"EPSG\",8801]],PARAMETER[\"Longitude of natural origin\",-2,ANGLEUNIT[\"degree\",0.0174532925199433],ID[\"EPSG\",8802]],PARAMETER[\"Scale factor at natural origin\",0.9996012717,SCALEUNIT[\"unity\",1],ID[\"EPSG\",8805]],PARAMETER[\"False easting\",400000,LENGTHUNIT[\"metre\",1],ID[\"EPSG\",8806]],PARAMETER[\"False northing\",-100000,LENGTHUNIT[\"metre\",1],ID[\"EPSG\",8807]]],CS[Cartesian,2],AXIS[\"(E)\",east,ORDER[1],LENGTHUNIT[\"metre\",1]],AXIS[\"(N)\",north,ORDER[2],LENGTHUNIT[\"metre\",1]],USAGE[SCOPE[\"Engineering survey, topographic mapping.\"],AREA[\"United Kingdom (UK) - offshore to boundary of UKCS within 49°45'N to 61°N and 9°W to 2°E; onshore Great Britain (England, Wales and Scotland). Isle of Man onshore.\"],BBOX[49.75,-9.01,61.01,2.01]],ID[\"EPSG\",27700]]";
        String srid = "ESPG:27700";

        geoServerClient.addProjectionsToGeoserver(wktString, srid);

        String expectedFile = srid + "=" + wktString + "\n";
        String remoteFilePath = "/opt/geoserver_data/user_projections/epsg.properties";
        dummyGeoServerContainer.assertFileContent(expectedFile, remoteFilePath);
    }

    @Test
    void testCreateGeoTiffLayerExisting() {
        mockGeoServer.addExpectation(
                "/rest/workspaces/" + EXISTING_WORKSPACE + "/coveragestores/" + EXISTING_COVERAGE_STORE + ".xml", "GET",
                200);

        geoServerClient.createGeoTiffLayer(EXISTING_WORKSPACE, EXISTING_COVERAGE_STORE, DATABASE_NAME, SCHEMA_NAME,
                new GeoServerRasterSettings(), new MultidimSettings());
    }

    @Test
    void testCreateGeoTiffLayerNew() {
        mockGeoServer.addExpectation(
                "/rest/workspaces/" + EXISTING_WORKSPACE + "/coveragestores/" + NEW_COVERAGE_STORE + ".xml", "GET",
                404);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/coveragestores/" + NEW_COVERAGE_STORE
                + "/external.imagemosaic", "PUT", 200, response().withBody("<coverageStore></coverageStore>"));

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/coveragestores/" + NEW_COVERAGE_STORE
                + "/coverages.xml", "POST", 200);

        mockGeoServer.addExpectation("/rest/layers/" + EXISTING_WORKSPACE + ":" + NEW_COVERAGE_STORE, "PUT", 200);

        try {
            geoServerClient.createGeoTiffLayer(EXISTING_WORKSPACE, NEW_COVERAGE_STORE, DATABASE_NAME, SCHEMA_NAME,
                    new GeoServerRasterSettings(), new MultidimSettings());
        } catch (RuntimeException ex) {
        }

        mockGeoServer.verifyCalls();
    }

    @Test
    void testCreatePostGISDataStoreExisting() {
        mockGeoServer.addExpectation(
                "/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + NEW_POSTGIS_STORE + ".xml", "GET", 200);

        geoServerClient.createPostGISDataStore(EXISTING_WORKSPACE, NEW_POSTGIS_STORE, DATABASE_NAME, SCHEMA_NAME);
    }

    @Test
    void testCreatePostGISDataStoreNew() {

        mockGeoServer.addExpectation(
                "/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + NEW_POSTGIS_STORE + ".xml", "GET", 404);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores.xml", "POST", 200);

        geoServerClient.createPostGISDataStore(EXISTING_WORKSPACE, NEW_POSTGIS_STORE, DATABASE_NAME, SCHEMA_NAME);
    }

    @Test
    void testCreatePostGISLayerExisting() {
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + DATABASE_NAME + ".xml",
                "GET", 200);

        String layerName = "layerName";
        mockGeoServer.addExpectation("/rest/layers/" + EXISTING_WORKSPACE + ":" + layerName + ".xml", "GET", 200);

        geoServerClient.createPostGISLayer(EXISTING_WORKSPACE, DATABASE_NAME, layerName,
                new GeoServerVectorSettings());
    }

    @Test
    void testCreatePostGISLayerExistingStore() {
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + DATABASE_NAME + ".xml",
                "GET", 200);

        String layerName = "layerName";
        mockGeoServer.addExpectation("/rest/layers/" + EXISTING_WORKSPACE + ":" + layerName + ".xml", "GET", 404);

        mockGeoServer.addExpectation(
                "/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + DATABASE_NAME + "/featuretypes", "POST",
                200);

        mockGeoServer.addExpectation("/rest/layers/" + EXISTING_WORKSPACE + ":" + layerName, "PUT", 200);

        geoServerClient.createPostGISLayer(EXISTING_WORKSPACE, DATABASE_NAME, layerName,
                new GeoServerVectorSettings());
    }

    @Test
    void testCreatePostGISLayerNew() {
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + DATABASE_NAME + ".xml",
                "GET", 404);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores.xml", "POST", 200);

        String layerName = "layerName";
        mockGeoServer.addExpectation("/rest/layers/" + EXISTING_WORKSPACE + ":" + layerName + ".xml", "GET", 404);

        mockGeoServer.addExpectation(
                "/rest/workspaces/" + EXISTING_WORKSPACE + "/datastores/" + DATABASE_NAME + "/featuretypes", "POST",
                200);

        mockGeoServer.addExpectation("/rest/layers/" + EXISTING_WORKSPACE + ":" + layerName, "PUT", 200);

        geoServerClient.createPostGISLayer(EXISTING_WORKSPACE, DATABASE_NAME, layerName,
                new GeoServerVectorSettings());
    }

    @Test
    void testCreateWorkspaceExisting() {
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + ".xml", "GET", 200);

        geoServerClient.createWorkspace(EXISTING_WORKSPACE);
    }

    @Test
    void testCreateWorkspaceNew() {
        mockGeoServer.addExpectation("/rest/workspaces/" + NEW_WORKSPACE + ".xml", "GET", 404);

        mockGeoServer.addExpectation("/rest/workspaces", "POST", 200, HttpRequest.request()
                .withBody("<workspace><name>" + NEW_WORKSPACE + "</name></workspace>"));

        geoServerClient.createWorkspace(NEW_WORKSPACE);
    }

    @Test
    void testGetInstance() {

        GeoServerClient tempClient1 = GeoServerClient.getInstance();
        Assertions.assertNotNull(tempClient1);

        GeoServerClient tempClient2 = GeoServerClient.getInstance();
        Assertions.assertNotNull(tempClient2);

        Assertions.assertSame(tempClient1, tempClient2);
    }

    @Test
    void testLoadIcons() {
        Path iconPath = Assertions
                .assertDoesNotThrow(() -> Path.of(GeoServerClientTest.class.getResource("icons/icon1.gif").toURI()));
        Path baseDirectory = iconPath.getParent().getParent();

        geoServerClient.loadIcons(baseDirectory, "icons");

        byte[] allBytes = Assertions.assertDoesNotThrow(() -> Files.readAllBytes(iconPath));
        dummyGeoServerContainer.assertFileContent(allBytes, "/opt/geoserver_data/www/icons/icon1.gif");
    }

    @Test
    @Disabled("Disabled until fixed in PR #1300")
    void testLoadOtherFilesGood() throws IOException {

        try (InputStream is = GeoServerClientTest.class.getResourceAsStream("otherFiles.json")) {
            Path baseDirectory = Assertions
                    .assertDoesNotThrow(() -> Path.of(GeoServerClientTest.class.getResource("otherFiles").toURI()));
            List<GeoserverOtherStaticFile> otherFiles = Assertions
                    .assertDoesNotThrow(() -> JsonHelper.getMapper().readValue(is,
                            new TypeReference<List<GeoserverOtherStaticFile>>() {
                            }));

            geoServerClient.loadOtherFiles(baseDirectory, otherFiles);

            String staticDir = "/opt/geoserver_data/www/static_data/";
            Map<String, String> expected = Map.of(
                    "a2.txt", "a1",
                    "a3/a4.txt", "a1",
                    "d1/c1.txt", "c1",
                    "d2/c1.txt", "c1",
                    "d1/d1.txt", "d1",
                    "d2/d1.txt", "d1",
                    "d3", "d1",
                    "d4/d5.txt", "d1");

            dummyGeoServerContainer.assertDirContent(expected, staticDir);
        }
    }

    @Test
    void testLoadStyleExisting() throws IOException {
        String styleName = "styleName";
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles/" + styleName + ".xml", "GET",
                200);

        String fileName = "styleFile.sld";
        Assertions.assertDoesNotThrow(() -> geoServerClient
                .loadStyle(JsonHelper.getMapper().readValue(
                        "{\"name\":\"" + styleName + "\", \"file\":\"" + fileName + "\" }",
                        GeoServerStyle.class), EXISTING_WORKSPACE));
    }

    @Test
    void testLoadStyleNew() throws IOException {
        String styleName = "styleName";
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles/" + styleName + ".xml", "GET",
                404);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles", "POST", 200);
        // .withPathParameter("name", styleName,"POST",200);

        Path configDir = null;
        Path stylePath = null;
        try (InputStream styleIn = GeoServerClientTest.class.getResourceAsStream("point_simplepoint.sld")) {
            configDir = Assertions
                    .assertDoesNotThrow(() -> Files.createDirectories(Path.of("/inputs/config")));

            stylePath = configDir.resolve("styleFile.sld");
            Files.copy(styleIn, stylePath);

            Path fileName = stylePath.getFileName();
            Assertions.assertDoesNotThrow(() -> geoServerClient
                    .loadStyle(JsonHelper.getMapper().readValue(
                            "{\"name\":\"" + styleName + "\", \"file\":\"" + fileName + "\" }",
                            GeoServerStyle.class), EXISTING_WORKSPACE));
        } finally {
            if (null != stylePath) {
                Files.deleteIfExists(stylePath);
            }
            if (null != configDir) {
                Files.deleteIfExists(configDir);
                Files.deleteIfExists(configDir.getParent());
            }
        }
    }

    @Test
    void testReload() {
        mockGeoServer.addExpectation("/rest/reload", "POST", 200);

        geoServerClient.reload();
    }

    @Test
    void testRemoveWorkspaceNonExisting() {
        mockGeoServer.addExpectation("/rest/workspaces/" + NEW_WORKSPACE + ".xml", "GET", 404);

        geoServerClient.removeWorkspace(NEW_WORKSPACE);
    }

    @Test
    void testRemoveWorkspaceExisting() {
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + ".xml", "GET", 200);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles.xml", "GET", 200);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE, "DELETE", 200);

        geoServerClient.removeWorkspace(EXISTING_WORKSPACE);
    }

    @Test
    void testRemoveWorkspaceExistingWithStyles() {
        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + ".xml", "GET", 200);

        Assertions.assertDoesNotThrow(() -> {
            try (InputStream stylesFile = GeoServerClientTest.class.getResourceAsStream("styles.xml");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stylesFile))) {
                String styles = reader.lines().collect(Collectors.joining("\n"));
                mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles.xml", "GET", 200,
                        response().withBody(styles));
            }
        });

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles/pophatch", "DELETE", 200);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles/point", "DELETE", 200);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE + "/styles/population", "DELETE", 200);

        mockGeoServer.addExpectation("/rest/workspaces/" + EXISTING_WORKSPACE, "DELETE", 200);

        geoServerClient.removeWorkspace(EXISTING_WORKSPACE);
    }
}
