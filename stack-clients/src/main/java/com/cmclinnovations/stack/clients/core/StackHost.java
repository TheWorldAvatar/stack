package com.cmclinnovations.stack.clients.core;

import java.util.Optional;

import com.cmclinnovations.stack.clients.utils.FileUtils;

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
        return proto;
    }

    public Optional<String> getName() {
        return name;
    }

    public Optional<String> getPort() {
        return port;
    }

    public Optional<String> getPath() {
        return path;
    }

}
