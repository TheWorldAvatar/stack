package com.cmclinnovations.stack.clients.blazegraph;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.eclipse.rdf4j.sparqlbuilder.core.query.ModifyQuery;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.core.datasets.CopyDatasetQuery;
import com.cmclinnovations.stack.clients.ontop.OntopEndpointConfig;
import com.cmclinnovations.stack.clients.utils.SparqlRulesFile;

import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;

public class BlazegraphClient extends ClientWithEndpoint<BlazegraphEndpointConfig> {

    private static final Logger logger = LoggerFactory.getLogger(BlazegraphClient.class);

    private static final Pattern SERVICE_IRI_PATTERN = Pattern.compile("SERVICE\\s*<ontop>",
            Pattern.CASE_INSENSITIVE);

    private static BlazegraphClient instance = null;

    public static BlazegraphClient getInstance() {
        if (null == instance) {
            instance = new BlazegraphClient();
        }
        return instance;
    }

    private BlazegraphClient() {
        super(EndpointNames.BLAZEGRAPH, BlazegraphEndpointConfig.class);
    }

    public void createNamespace(String namespace) {
        createNamespace(namespace, new Properties());
    }

    public void createNamespace(String namespace, Properties properties) {
        sendCommandToBlazegraph(namespace, new CreateRepositoryCmd(namespace, properties));
    }

    public void removeNamespace(String namespace) {
        sendCommandToBlazegraph(namespace, new RemoveRepositoryCmd(namespace));
    }

    private void sendCommandToBlazegraph(String namespace, BaseCmd command) {
        BlazegraphEndpointConfig endpointConfig = readEndpointConfig();
        String serviceUrl = endpointConfig.getServiceUrl();

        try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setDefaultCredentialsProvider(configureAuthentication(endpointConfig))
                .build()) {
            callRemoteRepositoryManager(namespace, command, serviceUrl, httpClient);
        } catch (Exception ex) {
            throw new RuntimeException(generateMessage(namespace, command, serviceUrl), ex);
        }
    }

    private BasicCredentialsProvider configureAuthentication(BlazegraphEndpointConfig endpointConfig) {
        if (endpointConfig.getPassword().isEmpty()) {
            return null;
        } else {
            final HttpHost targetHost = new HttpHost("http", endpointConfig.getHostName(),
                    Integer.valueOf(endpointConfig.getPort()));
            final BasicCredentialsProvider provider = new BasicCredentialsProvider();
            AuthScope authScope = new AuthScope(targetHost);
            provider.setCredentials(authScope, new UsernamePasswordCredentials(endpointConfig.getUsername(),
                    endpointConfig.getPassword().toCharArray()));
            return provider;
        }
    }

    private void callRemoteRepositoryManager(String namespace, BaseCmd command, String serviceUrl,
            CloseableHttpClient httpClient) throws Exception {
        try (CloseableHttpResponse response = httpClient.execute(command.getRequest(serviceUrl))) {
            switch (response.getCode()) {
                case HttpStatus.SC_CREATED: // Namespace created successfully
                case HttpStatus.SC_OK: // Namespace removed successfully
                    return;
                case HttpStatus.SC_CONFLICT: // Namespace already exists error
                    logger.warn("Namespace '{}' already exists.", namespace);
                    break;
                case HttpStatus.SC_NOT_FOUND: // Namespace does not exist error
                    logger.warn("Namespace '{}' does not exist.", namespace);
                    break;
                default:
                    throw new RuntimeException(generateMessage(namespace, command, serviceUrl)
                            + " Response code: " + response.getCode() + ". Reason: " + response.getReasonPhrase());
            }
        }
    }

    public void runRules(RemoteStoreClient remoteStoreClient, List<Path> ruleFiles) {
        SparqlRulesFile sparqlRules = new SparqlRulesFile(ruleFiles);
        sparqlRules.getRules().forEach(remoteStoreClient::executeUpdate);
    }

    private String generateMessage(String namespace, BaseCmd command, String serviceUrl) {
        return "Failed to " + command.getText() + " namespace '" + namespace + "' at endpoint '" + serviceUrl
                + "'.";
    }

    private abstract static class BaseCmd {

        private final String namespace;
        private final String text;

        BaseCmd(String namespace, String text) {
            this.namespace = namespace;
            this.text = text;
        }

        String getNamespace() {
            return namespace;
        }

        String getText() {
            return text;
        }

        abstract ClassicHttpRequest getRequest(String serviceUrl) throws IOException;
    }

    private static class CreateRepositoryCmd extends BaseCmd {

        private final Properties properties;

        CreateRepositoryCmd(String namespace, Properties properties) {
            super(namespace, "create new");
            this.properties = properties;
        }

        ClassicHttpRequest getRequest(String serviceUrl) throws IOException {
            OutputStream os = new ByteArrayOutputStream();
            properties.put("com.bigdata.rdf.sail.namespace", getNamespace());
            properties.storeToXML(os, null, StandardCharsets.UTF_8);
            return ClassicRequestBuilder.post(serviceUrl + "/namespace")
                    .setEntity(os.toString(), ContentType.APPLICATION_XML)
                    .build();
        }
    }

    private static class RemoveRepositoryCmd extends BaseCmd {

        RemoveRepositoryCmd(String namespace) {
            super(namespace, "remove");
        }

        ClassicHttpRequest getRequest(String serviceUrl) {
            return ClassicRequestBuilder.delete(serviceUrl + "/namespace/" + getNamespace())
                    .build();
        }
    }

    public void uploadRDFFiles(Path dirPath, String namespace) {
        RemoteStoreClient remoteStoreClient = getRemoteStoreClient(namespace);
        try (Stream<Path> files = Files.list(dirPath)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> null != remoteStoreClient
                            .getRDFContentType(FilenameUtils.getExtension(file.toString())))
                    .forEach(path -> remoteStoreClient.uploadFile(path.toFile()));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load RDF files stored in the directory '" + dirPath + "'.", ex);
        }
    }

    public RemoteStoreClient getRemoteStoreClient(String namespace) {
        BlazegraphEndpointConfig endpointConfig = readEndpointConfig();
        String url = endpointConfig.getUrl(namespace);
        return new RemoteStoreClient(url, url,
                endpointConfig.getUsername(),
                endpointConfig.getPassword());
    }

    /**
     * Method for replacing placeholders with real values
     * This is currently restricted to replacing endpoint names with their URL in
     * SPARQL SERVICE patterns, and specifically just for Ontop endpoints.
     * 
     * @param query query to be filtered
     * @return the query after the appropriate substitutions have been made
     */
    public String filterQuery(String query) {
        return filterQuery(query, readEndpointConfig("ontop", OntopEndpointConfig.class).getUrl());
    }

    public String filterQuery(String query, String ontopEndpoint) {
        Matcher matcher = SERVICE_IRI_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.replaceAll("SERVICE <" + ontopEndpoint + ">");
        } else {
            return query;
        }
    }

    public void cloneDatasets(String targetNamespace, Collection<String> datasetNames, String catalogNamespace) {
        String catalogServiceURL = readEndpointConfig().getUrl(catalogNamespace);
        ModifyQuery query = CopyDatasetQuery.getInsertQuery(datasetNames, Rdf.iri(catalogServiceURL));
        getRemoteStoreClient(targetNamespace).executeUpdate(query.getQueryString());
    }
}
