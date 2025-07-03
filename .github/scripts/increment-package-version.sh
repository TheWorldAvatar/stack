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

for pom in "${POM_FILES[@]}"; do
    if [ -f "$pom" ]; then
        sed -i.bak -E "s|(<version>).+(</version>)|\1$VERSION\2|" "$pom" && rm "$pom.bak"
        echo "Updated version in $pom to $VERSION"
    else
        echo -e "\e[31mError\e[0m: $pom not found"
        exit 1
    fi
done

echo -e "\e[32mVersion incremented\e[0m, compose file updated. Next step in this action will commit the changes"
