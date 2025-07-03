#!/bin/bash

# Update image version in docker-compose.yml
DOCKER_COMPOSE_CLIENT="stack-clients/docker-compose.yml"
DOCKER_COMPOSE_MANAGER="stack-manager/docker-compose.yml"
DOCKER_COMPOSE_UPLOADER="stack-data-uploader/docker-compose.yml"

POM_CLIENT="stack-clients/pom.xml"
POM_MANAGER="stack-manager/pom.xml"
POM_UPLOADER="stack-data-uploader/pom.xml"

POM_FILES=("$POM_CLIENT" "$POM_MANAGER" "$POM_UPLOADER")
COMPOSE_FILES=("$DOCKER_COMPOSE_CLIENT" "$DOCKER_COMPOSE_MANAGER" "$DOCKER_COMPOSE_UPLOADER")

for compose in "${COMPOSE_FILES[@]}"; do
    if [ -f "$compose" ]; then
        sed -i.bak -E "s|(image: .+:).+|\1$VERSION|" "$compose" && rm "$compose.bak"
        echo "Updated image version in $compose to $VERSION"
    else
        echo -e "\e[31mError\e[0m: $compose not found"
        exit 1
    fi
done

for POM in "${POM_FILES[@]}"; do
    if [ -f "$POM" ]; then
        ARTIFACT_ID=$(basename "$(dirname "$POM")")
        # Update the main <version> for the artifact
        awk -v ver="$VERSION" -v aid="$ARTIFACT_ID" '
            BEGIN { found=0 }
            /<artifactId>/ {
                if ($0 ~ "<artifactId>" aid "</artifactId>") found=1
                else found=0
            }
            found && /<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/ && !done[aid] {
                sub(/<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/, "<version>" ver "</version>")
                done[aid]=1
            }
            { print }
        ' "$POM" > "$POM.tmp" && mv "$POM.tmp" "$POM"

        # If this is stack-manager or stack-data-uploader, update stack-clients dependency version robustly
        if [[ "$ARTIFACT_ID" == "stack-manager" || "$ARTIFACT_ID" == "stack-data-uploader" ]]; then
            awk -v ver="$VERSION" '
                BEGIN { in_dep=0; found=0 }
                /<dependency>/ { in_dep=1; found=0 }
                in_dep && /<artifactId>stack-clients<\/artifactId>/ { found=1 }
                in_dep && found && /<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/ {
                    sub(/<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/, "<version>" ver "</version>")
                    found=0
                }
                /<\/dependency>/ { in_dep=0; found=0 }
                { print }
            ' "$POM" > "$POM.tmp" && mv "$POM.tmp" "$POM"
        fi

        echo "Updated version in $POM to $VERSION"
    else
        echo -e "\e[31mError\e[0m: $POM not found"
        exit 1
    fi
done

echo -e "\e[32mVersion incremented\e[0m, compose file updated. Next step in this action will commit the changes"
