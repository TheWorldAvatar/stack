#!/bin/bash

MAIN_VERSION=$(curl -s "https://raw.githubusercontent.com/TheWorldAvatar/stack/main/VERSION")
MAIN_VERSION=1.49.0

# Check if MAIN_VERSION is a semantic version number
if ! [[ "$MAIN_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "No version set on main, skipping version check"
    exit 0
fi

VERSION=$(cat -s "VERSION" 2>/dev/null)

if [ "$VERSION" == "" ]; then
    echo -e "\e[31mError\e[0m: VERSION file is empty. Please ensure the correct version number is written here. Version currently on main is: $MAIN_VERSION"
    exit 1
fi
echo "Version set in this PR: $VERSION"
echo "Version on main: $MAIN_VERSION"

# Get the VERSION file from the main branch of the repo, check that this new version is updated ie does not match
if [ "$VERSION" == "$MAIN_VERSION" ]; then
    echo -e "\e[31mError\e[0m: VERSION specified on this branch matches that on main. Update the VERSION file before merging."
    exit 1
fi

# Check that VERSION follows the semantic versioning pattern
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo -e "\e[31mError\e[0m: VERSION must follow the semantic versioning pattern x.y.z where x, y, and z are numbers"
    exit 1
fi

# Check that the new version is incremented correctly
IFS='.' read -r -a MAIN_VERSION_PARTS <<<"$MAIN_VERSION"
IFS='.' read -r -a VERSION_PARTS <<<"$VERSION"

# Check for valid patch increment (x.y.z+1)
PATCH_INCREMENT=$((MAIN_VERSION_PARTS[2] + 1))
if [ "${VERSION_PARTS[0]}" -eq "${MAIN_VERSION_PARTS[0]}" ] &&
    [ "${VERSION_PARTS[1]}" -eq "${MAIN_VERSION_PARTS[1]}" ] &&
    [ "${VERSION_PARTS[2]}" -eq "$PATCH_INCREMENT" ]; then
    VALID_INCREMENT=true
fi

# Check for valid minor increment (x.y+1.0)
MINOR_INCREMENT=$((MAIN_VERSION_PARTS[1] + 1))
if [ "${VERSION_PARTS[0]}" -eq "${MAIN_VERSION_PARTS[0]}" ] &&
    [ "${VERSION_PARTS[1]}" -eq "$MINOR_INCREMENT" ] &&
    [ "${VERSION_PARTS[2]}" -eq 0 ]; then
    VALID_INCREMENT=true
fi

# Check for valid major increment (x+1.0.0)
MAJOR_INCREMENT=$((MAIN_VERSION_PARTS[0] + 1))
if [ "${VERSION_PARTS[0]}" -eq "$MAJOR_INCREMENT" ] &&
    [ "${VERSION_PARTS[1]}" -eq 0 ] &&
    [ "${VERSION_PARTS[2]}" -eq 0 ]; then
    VALID_INCREMENT=true
fi

if [ "$VALID_INCREMENT" != true ]; then
    echo -e "\e[31mError\e[0m: VERSION must be properly incremented. Valid increments are: patch (x.y.z+1), minor (x.y+1.0), or major (x+1.0.0)"
    exit 1
fi

# Update version in POM files
POM_FILES=(
    "stack-clients/pom.xml"
    "stack-manager/pom.xml"
    "stack-data-uploader/pom.xml"
)

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

# Update image version in docker-compose.yml files
DOCKER_COMPOSE_FILES=(
    "stack-clients/docker-compose.yml"
    "stack-manager/docker-compose.yml"
    "stack-data-uploader/docker-compose.yml"
)

for DOCKER_COMPOSE in "${DOCKER_COMPOSE_FILES[@]}"; do
    if [ -f "$DOCKER_COMPOSE" ]; then
        sed -i.bak -E "s|(image: .+:).+|\1$VERSION|" "$DOCKER_COMPOSE" && rm "$DOCKER_COMPOSE.bak"
        echo "Updated image version in $DOCKER_COMPOSE to $VERSION"
    else
        echo -e "\e[31mError\e[0m: $DOCKER_COMPOSE not found"
        exit 1
    fi
done

echo -e "\e[32mVersion incremented\e[0m, compose file and package.json updated. Next step in this action will commit the changes"

exit 0
