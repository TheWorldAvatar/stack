package com.cmclinnovations.stack.services;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Base64;
import java.util.Optional;

import com.cmclinnovations.stack.clients.core.RESTEndpointConfig;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class GeoServerService extends ContainerService {

    public static final String TYPE = "geoserver";

    private static final String ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD_FILE = "/run/secrets/geoserver_password";

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    // Convert username:password to Base64 String.
    private static final String DEFAULT_AUTHORIZATION = Base64.getEncoder()
            .encodeToString("admin:geoserver".getBytes());

    private final RESTEndpointConfig geoserverEndpointConfig;

    public GeoServerService(String stackName, ServiceConfig config) {
        super(stackName, config);

        String passwordFile = getEnvironmentVariable("ADMIN_PASSWORD_FILE");
        if (null == passwordFile) {
            passwordFile = DEFAULT_ADMIN_PASSWORD_FILE;
        }

        setEnvironmentVariableIfAbsent("RUN_UNPRIVILEGED", "true");
        setEnvironmentVariableIfAbsent("CHANGE_OWNERSHIP_ON_FOLDERS", "/opt /opt/geoserver_data/" + " /geotiffs");

        try {
            geoserverEndpointConfig = new RESTEndpointConfig("geoserver",
                    new URL("http", getHostName(), 8080, "/geoserver/"),
                    ADMIN_USERNAME, passwordFile);

            addEndpointConfig(geoserverEndpointConfig);
        } catch (MalformedURLException ex) {
            throw new RuntimeException("Failed to construct URL for GeoServer config file.", ex);
        }
    }

    @Override
    public void doFirstTimePostStartUpConfiguration() {
        Builder settingsRequestBuilder = createRequestBuilder("settings");

        Optional<JsonNode> settings = getExistingSettings(settingsRequestBuilder);

        if (settings.isPresent()) {
            updateSettings(settingsRequestBuilder, settings.get());
        }

        addUrlCheck();

        updatePassword();
    }

    private Builder createRequestBuilder(String path) {
        try {
            return HttpRequest
                    .newBuilder(new URL("http", getHostName(), 8080, "/geoserver/rest/" + path).toURI())
                    .header("Authorization", "basic " + DEFAULT_AUTHORIZATION)
                    .header("accept", "application/json");
        } catch (MalformedURLException | URISyntaxException ex) {
            throw new RuntimeException("Failed to construct URL for updating GeoServer.", ex);
        }
    }

    private Optional<JsonNode> getExistingSettings(Builder requestBuilder) {

        // Return "empty" to signal that no further REST calls need to be made
        Optional<JsonNode> settings = Optional.empty();

        HttpRequest settingsGetRequest = requestBuilder.build();

        ObjectMapper objectMapper = JsonHelper.getMapper();

        boolean serverReady = false;
        do {
            try {
                Thread.sleep(1000);
                HttpResponse<String> response = httpClient.send(settingsGetRequest, BodyHandlers.ofString());

                switch (response.statusCode()) {
                    case 401:
                        // Password has probably already been changed, so can skip further setup
                        serverReady = true;
                        break;
                    case 404:
                        // Server probably hasn't finished initialising
                        break;
                    case 200:
                        // Server is ready and the password is still the default
                        serverReady = true;
                        settings = Optional.of(objectMapper.readTree(response.body()));
                        break;
                    default:
                        throw new RuntimeException(
                                "GeoServer returned unexpected status code '" + response.statusCode()
                                        + "' with body:\n" + response.body());
                }
            } catch (ConnectException ex) {
                // Just wait, probably just downloading plugins
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(
                        "Failed to parse response from GeoServer get settings request as JSON.", ex);
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Failed to process send/recieve message as part of GeoServer get settings request.", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); // set interrupt flag
                throw new RuntimeException("GeoServer get settings request was interupted.", ex);
            }
        } while (!serverReady);

        return settings;
    }

    private void updateSettings(Builder requestBuilder, JsonNode settings) {
        // Add global setting so that the the GeoServer web interface still works
        // through the reverse-proxy.
        settings.withObject("/global/settings")
                .put("proxyBaseUrl", "${X-Forwarded-Proto}:\\/\\/${X-Forwarded-Host}\\/geoserver")
                .put("useHeadersProxyURL", true)
                .put("numDecimals", 6);

        HttpRequest settingsPutRequest = requestBuilder
                .PUT(BodyPublishers.ofString(settings.toString()))
                .header("content-type", "application/json")
                .build();

        try {
            httpClient.send(settingsPutRequest, BodyHandlers.discarding());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to process send/receive message as part of GeoServer settings update request.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); // set interrupt flag
            throw new RuntimeException("GeoServer settings update request was interrupted.", ex);
        }
    }

    private void updatePassword() {

        String password = geoserverEndpointConfig.getPassword();
        // Change the password from the default one.
        HttpRequest passwordPutRequest;
        try {
            passwordPutRequest = HttpRequest
                    .newBuilder(new URL("http", getHostName(), 8080, "/geoserver/rest/security/self/password").toURI())
                    .header("Authorization", "basic " + DEFAULT_AUTHORIZATION)
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .PUT(BodyPublishers.ofString("{\"newPassword\": \"" + password + "\"}"))
                    .build();
        } catch (MalformedURLException | URISyntaxException ex) {
            throw new RuntimeException("Failed to construct URL for updating GeoServer password.", ex);
        }

        try {
            httpClient.send(passwordPutRequest, BodyHandlers.discarding());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to process send/recieve message as part of GeoServer password update request.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); // set interrupt flag
            throw new RuntimeException("GeoServer password update request was interrupted.", ex);
        }
    }

    private void addUrlCheck() {

        Builder requestBuilder = createRequestBuilder("urlchecks");

        ObjectNode urlCheck = JsonNodeFactory.instance.objectNode();
        urlCheck.putObject("regexUrlCheck")
                .put("name", "Local GeoTiffs")
                .put("description", "Enable the reading of Geotiffs from inside the container.")
                .put("enabled", true)
                .put("regex", "^file:/geotiffs/.*$");

        HttpRequest settingsPutRequest = requestBuilder
                .POST(BodyPublishers.ofString(urlCheck.toString()))
                .header("content-type", "application/json")
                .build();
        try {
            httpClient.send(settingsPutRequest, BodyHandlers.discarding());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to process send/receive message as part of GeoServer urlChecks update request.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); // set interrupt flag
            throw new RuntimeException("GeoServer urlChecks update request was interrupted.", ex);
        }
    }

}
