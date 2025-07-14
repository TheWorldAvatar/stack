# syntax=docker/dockerfile:1.4
# First stage: download the Java dependencies and build
#==================================================================================================
FROM maven:3.8.3-adoptopenjdk-11 AS builder

# Copy in Maven settings templates and credentials for local builds
COPY docker/credentials /root/credentials
COPY docker/.m2 /root/.m2

# Use BuildKit secret for settings.xml if available, else use local credentials
RUN --mount=type=secret,id=maven_settings,required=false \
    if [ -f /run/secrets/maven_settings ]; then \
      cp /run/secrets/maven_settings /root/.m2/settings.xml; \
    fi

WORKDIR /root/.m2
RUN --mount=type=secret,id=maven_settings,required=false \
    if [ ! -f /root/.m2/settings.xml ]; then \
      sed -i "s|MASTER_PASSWORD|$(mvn --encrypt-master-password master_password)|" settings-security.xml && \
      sed -i "s|REPO_USERNAME|$(cat ../credentials/repo_username.txt)|;s|REPO_PASSWORD|$(cat ../credentials/repo_password.txt|xargs mvn --encrypt-password)|" settings.xml; \
    fi

WORKDIR /root/code
# Copy just the pom.xml file
COPY pom.xml ./pom.xml

# Retrieve all of the dependencies 
RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository,sharing=locked mvn clean dependency:resolve --update-snapshots

# Copy in the code
COPY src ./src/

# Ensure that the latest versions of SNAPSHOT dependencies are always used
ARG CACHEBUST=1

RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository,sharing=locked mvn package --update-snapshots

FROM builder AS test

RUN --mount=type=cache,id=stack-mvn,target=/root/.m2/repository2,sharing=locked mkdir -p /root/.m2/repository && cp -r /root/.m2/repository2/* /root/.m2/repository

CMD ["mvn", "test"]

#==================================================================================================

# Second stage: copy the output into a clean image
#==================================================================================================
FROM eclipse-temurin:11-jre
WORKDIR /app

COPY target/*.jar /app/
COPY target/lib /app/lib

COPY docker/entrypoint.sh /entrypoint.sh

EXPOSE 5005

ENTRYPOINT ["/entrypoint.sh"]
