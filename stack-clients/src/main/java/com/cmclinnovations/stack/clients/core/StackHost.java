package com.cmclinnovations.stack.clients.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public final class StackHost {

    private final Optional<String> proto;
    private final Optional<String> name;
    private final Optional<String> port;
    private final Optional<String> path;

    public StackHost() {
        this(null);
    }

    public StackHost(String hostName) {
        proto = Optional.empty();
        name = Optional.ofNullable(hostName);
        port = Optional.empty();
        path = Optional.empty();
    }

    public Optional<String> getProto() {
        return proto.filter(p -> !p.isBlank());
    }

    public Optional<String> getName() {
        return name.filter(n -> !n.isBlank());
    }

    public Optional<String> getPort() {
        return port.filter(p -> !p.isBlank());
    }

    public Optional<String> getPath() {
        return path.filter(p -> !p.isBlank());
    }

    public Builder getStringBuilder() {
        return new Builder();
    }

    public class Builder {

        private String protoOut = null;
        private String nameOut = null;
        private String portOut = null;
        private String pathOut = null;
        private String extraPathOut = null;
        private boolean finalSlash = false;

        public Builder withProto() {
            getProto().ifPresent(p -> protoOut = p);
            return this;
        }

        public Builder withProto(String defaultProto) {
            protoOut = getProto().orElse(defaultProto);
            return this;
        }

        public Builder withName() {
            getName().ifPresent(n -> nameOut = n);
            return this;
        }

        public Builder withName(String defaultName) {
            nameOut = getName().orElse(defaultName);
            return this;
        }

        public Builder withPort() {
            getPort().ifPresent(p -> portOut = p);
            return this;
        }

        public Builder withPort(String defaultPort) {
            portOut = getPort().orElse(defaultPort);
            return this;
        }

        public Builder withPath() {
            getPath().ifPresent(p -> pathOut = p);
            return this;
        }

        public Builder withPath(String defaultPath) {
            pathOut = getPath().orElse(defaultPath);
            return this;
        }

        public Builder withExtraPath(String extraPath) {
            extraPathOut = extraPath;
            return this;
        }

        public Builder withFinalSlash() {
            finalSlash = true;
            return this;
        }

        public String build() {
            return toString();
        }

        @Override
        public String toString() {
            try {
                // Build the path
                String fullPath = null;
                if (null != pathOut) {
                    fullPath = pathOut;
                    if (null != extraPathOut)
                        fullPath += "/" + extraPathOut;
                } else if (null != extraPathOut) {
                    fullPath = extraPathOut;
                }
                if (null != fullPath) {
                    // Append leading slash
                    fullPath = "/" + fullPath;
                    // Remove duplicate slashes
                    fullPath = fullPath.replaceAll("/+", "/");
                }

                // Create formal URI
                URI uri = (null != portOut)
                        ? new URI(protoOut, null, nameOut, Integer.parseInt(portOut), fullPath, null, null)
                        : new URI(protoOut, nameOut, fullPath, null);

                // Remove leading double slashes if no protocol is specified
                String cleanedString = uri.toString().replaceFirst("^//", "");
                // Append final slash if requested
                return finalSlash
                        ? cleanedString.replaceFirst("([^/])$", "$1/")
                        : cleanedString.replaceFirst("/$", "");

            } catch (URISyntaxException e) {
                throw new RuntimeException("Invalid URI syntax: " + e.getMessage(), e);
            }
        }

    }

}
