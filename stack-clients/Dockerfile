# syntax=docker/dockerfile:1.4

# First stage: download the Java dependencies and build
#==================================================================================================
FROM maven:3.8.3-adoptopenjdk-11 AS builder

# Use BuildKit secret for settings.xml if available, else use local credentials
# Copy in Maven settings templates and credentials for local builds

# COPY docker/credentials /root/credentials
# COPY docker/.m2 /root/.m2

# If a secret settings.xml is provided, use it (for CI); otherwise, use the local one
RUN --mount=type=secret,id=maven_settings,required=false \
    if [ -f /run/secrets/maven_settings ]; then \
      cp /run/secrets/maven_settings /root/.m2/settings.xml; \
    fi

# Populate settings templates with credentials
WORKDIR /root/.m2
# (Note that | rather than / is used as the sed delimiter, since encrypted passwords can contain the former, but not the latter
RUN --mount=type=secret,id=maven_settings,required=false \
    if [ ! -f /root/.m2/settings.xml ]; then \
      sed -i "s|MASTER_PASSWORD|$(mvn --encrypt-master-password master_password)|" settings-security.xml && \
      sed -i "s|REPO_USERNAME|$(cat ../credentials/repo_username.txt)|;s|REPO_PASSWORD|$(cat ../credentials/repo_password.txt|xargs mvn --encrypt-password)|" settings.xml; \
    fi

# Copy in Java source and build
WORKDIR /root/code

# Copy just the pom.xml file
COPY pom.xml ./pom.xml

# Retrieve all of the dependencies 
RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository,sharing=locked mvn clean dependency:resolve --update-snapshots

# Copy in the code
COPY src ./src/

# Ensure that the latest versions of SNAPSHOT dependencies are always used
ARG CACHEBUST=1

RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository,sharing=locked mvn package --update-snapshots -DskipTests

FROM builder AS test

RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository2,sharing=locked mkdir -p /root/.m2/repository && cp -r /root/.m2/repository2/* /root/.m2/repository

ENV TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
ENV TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal

CMD ["mvn", "test"]

FROM builder AS deploy

RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository,sharing=locked mvn deploy -DskipTests

#==================================================================================================

# Second stage: copy the output into a clean image
#==================================================================================================
FROM adoptopenjdk/openjdk11:jre-11.0.13_8 AS agent

# Copy in the entrypoint script
COPY docker/entrypoint.sh /entrypoint.sh

WORKDIR /app

# Copy the downloaded dependencies from the builder
COPY --from=deploy /root/code/target/*.jar /app
# Copy the downloaded dependencies from the builder
COPY --from=deploy /root/code/target/lib /app/lib

# Copy the main jar from the builder
COPY --from=builder /root/code/target/stack-clients*.jar /app

# Port for Java debugging
EXPOSE 5005

# Run the entrypoint script
ENTRYPOINT ["/entrypoint.sh"]
