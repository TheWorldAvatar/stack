package com.cmclinnovations.apis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateConfigCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.ListConfigsCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig.Builder;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.RemoteApiVersion;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

public class DockerClient {

    protected static final Logger LOGGER = LoggerFactory.getLogger(DockerClient.class);

    private final String stackName;
    private final com.github.dockerjava.api.DockerClient internalClient;

    public static final String STACK_NAME_KEY = "STACK_NAME";
    public final Map<String, String> stackNameLabelMap;

    public DockerClient() {
        this(URI.create("tcp://host.docker.internal:2375"));
    }

    public DockerClient(URI endpoint) {
        this(endpoint, System.getenv(DockerClient.STACK_NAME_KEY));
    }

    public DockerClient(URI endpoint, String stackName) {
        Builder dockerConfigBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        if (null != endpoint) {
            dockerConfigBuilder.withDockerHost(endpoint.toString());
            // TODO need to set up TLS so that the unsecured Docker port "2375" doesn't need
            // to be opened.
            // dockerConfigBuilder.withDockerTlsVerify(true);
        }

        DockerClientConfig dockerConfig = dockerConfigBuilder
                .withApiVersion(RemoteApiVersion.VERSION_1_40)
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .build();

        internalClient = DockerClientBuilder.getInstance(dockerConfig).withDockerHttpClient(httpClient).build();

        this.stackName = stackName;

        stackNameLabelMap = Map.of(DockerClient.STACK_NAME_KEY, stackName);
    }

    public com.github.dockerjava.api.DockerClient getInternalClient() {
        return internalClient;
    }

    public String getStackName() {
        return stackName;
    }

    public Map<String, String> getStackNameLabelMap() {
        return stackNameLabelMap;
    }

    public String executeSimpleCommand(String containerId, String... cmd) {
        return createComplexCommand(containerId, cmd).exec();
    }

    public ComplexCommand createComplexCommand(String containerId, String... cmd) {
        return new ComplexCommand(containerId, cmd);
    }

    public final class ComplexCommand {

        private final ExecCreateCmd execCreateCmd;

        private String[] cmd;

        private boolean wait = true;

        private InputStream inputStream = null;
        private OutputStream outputStream = null;
        private OutputStream errorStream = null;

        private String hereDocument = null;

        public ComplexCommand(String containerId, String... cmd) {
            execCreateCmd = internalClient.execCreateCmd(containerId);
            this.cmd = cmd;
        }

        public ComplexCommand withWait(boolean wait) {
            this.wait = wait;
            return this;
        }

        public ComplexCommand withInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
            return this;
        }

        public ComplexCommand withOutputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
            return this;
        }

        public ComplexCommand withErrorStream(OutputStream errorStream) {
            this.errorStream = errorStream;
            return this;
        }

        public ComplexCommand withHereDocument(String hereDocument) {
            this.hereDocument = hereDocument;
            return this;
        }

        public String exec() {
            boolean attachStdin = null != inputStream;
            boolean attachStdout = null != outputStream;
            boolean attachStderr = null != errorStream;

            if (null != hereDocument) {
                if (attachStdin) {
                    throw new IllegalArgumentException("Can't specify both 'inputStream' and 'inputStream'.");
                }
                cmd = List.of("sh", "-c",
                        Arrays.stream(cmd).collect(Collectors.joining("' '", "'", "'"))
                                + "<< '\04\04\04'\n" + hereDocument + "\04\04\04")
                        .toArray(new String[] {});
            }

            String execId = execCreateCmd.withCmd(cmd)
                    .withAttachStdin(attachStdin)
                    .withAttachStdout(attachStdout)
                    .withAttachStderr(attachStderr)
                    .exec().getId();

            try (ExecStartCmd execStartCmd = internalClient.execStartCmd(execId)) {

                if (attachStdin) {
                    execStartCmd.withStdIn(inputStream);
                }

                // ExecStartResultCallback is marked deprecated but seems to do exactly what we
                // want and without knowing why it is deprecated any issues with it can't be
                // overcome anyway.
                try (ExecStartResultCallback result = execStartCmd
                        .exec(new ExecStartResultCallback(outputStream, errorStream))) {
                    if (wait) {
                        result.awaitCompletion(60, TimeUnit.SECONDS);
                    } else {
                        result.awaitStarted(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Docker exec command '" + Arrays.toString(cmd) + "' interupted", ex);
                } catch (IOException ex) {
                    throw new RuntimeException("Docker exec command '" + Arrays.toString(cmd) + "' failed", ex);
                }
            }
            return execId;
        }
    }

    public long getCommandErrorCode(String execId) {
        try (InspectExecCmd inspectExecCmd = internalClient.inspectExecCmd(execId)) {
            InspectExecResponse inspectExecResponce = inspectExecCmd.exec();
            Long exitCode = inspectExecResponce.getExitCodeLong();
            return (null != exitCode) ? exitCode : 1;
        }
    }

    public boolean fileExists(String containerId, String filePath) {
        return 0 == getCommandErrorCode(executeSimpleCommand(containerId, "test", "-f", filePath));
    }

    public void makeDir(String containerId, String directoryPath) {
        executeSimpleCommand(containerId, "mkdir", "-p", directoryPath);
    }

    public void deleteFile(String containerId, String filePath) {
        executeSimpleCommand(containerId, "rm", filePath);
    }

    public boolean directoryExists(String containerId, String directoryPath) {
        return 0 == getCommandErrorCode(executeSimpleCommand(containerId, "test", "-d", directoryPath));
    }

    public void deleteDirectory(String containerId, String directoryPath) {
        executeSimpleCommand(containerId, "rm", "-r", directoryPath);
    }

    public void sendFiles(String containerId, Map<String, byte[]> files, String remotePath) throws IOException {

        makeDir(containerId, remotePath);

        byte[] byteArray;

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                GzipCompressorOutputStream gzo = new GzipCompressorOutputStream(bos);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzo)) {
            for (Entry<String, byte[]> file : files.entrySet()) {
                String filePath = file.getKey().replace('\\', '/');
                byte[] fileContent = file.getValue();
                TarArchiveEntry entry = new TarArchiveEntry(filePath);
                entry.setSize(fileContent.length);
                entry.setMode(0755);
                tar.putArchiveEntry(entry);
                tar.write(fileContent);
                tar.closeArchiveEntry();
            }
            tar.finish();
            gzo.finish();
            byteArray = bos.toByteArray();
        }

        try (InputStream is = new ByteArrayInputStream(byteArray);
                CopyArchiveToContainerCmd copyArchiveToContainerCmd = internalClient
                        .copyArchiveToContainerCmd(containerId)) {
            copyArchiveToContainerCmd.withTarInputStream(is)
                    .withRemotePath(remotePath).exec();

        }
    }

    public Optional<Container> getContainer(String containerName) {
        try (ListContainersCmd listContainersCmd = internalClient.listContainersCmd()) {
            // Setting "showAll" to "true" ensures non-running containers are also returned
            return listContainersCmd.withNameFilter(List.of(containerName))
                    .withShowAll(true).exec()
                    .stream().findAny();
        }
    }

    public boolean isContainerUp(String containerName) {
        try (ListContainersCmd listContainersCmd = internalClient.listContainersCmd()) {
            // Don't need to filter for "running" state as this is the default setting
            return !listContainersCmd.withNameFilter(List.of(containerName)).exec().isEmpty();
        }
    }

    public String getContainerId(String containerName) {
        return getContainer(containerName).map(Container::getId).orElseThrow();
    }

    public Map<String, List<String>> convertToConfigFilterMap(String name, Map<String, String> labelMap) {
        Map<String, List<String>> result = labelMap.entrySet().stream().collect(Collectors.toMap(
                entry -> "label",
                entry -> List.of(entry.getKey() + "=" + entry.getValue())));
        if (null != name) {
            result.put("name", List.of(name));
        }
        return result;
    }

    public boolean configExists(String name) {
        try (ListConfigsCmd listConfigsCmd = internalClient.listConfigsCmd()) {
            return !listConfigsCmd
                    .withFilters(convertToConfigFilterMap(name, getStackNameLabelMap()))
                    .exec()
                    .isEmpty();
        }
    }

    public void addConfig(String name, String data) {
        try (CreateConfigCmd createConfigCmd = internalClient.createConfigCmd()) {
            createConfigCmd
                    .withName(name)
                    .withData(data.getBytes())
                    .withLabels(stackNameLabelMap)
                    .exec();
        }
    }

}
