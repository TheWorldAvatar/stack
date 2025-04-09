#!/bin/bash

MAIN_VERSION=$(curl -s "https://raw.githubusercontent.com/TheWorldAvatar/stack/main/VERSION")

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

echo "Version on main: $MAIN_VERSION"
echo "Version set in this PR: $VERSION"

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

echo -e "\e[32mVersion update is valid\e[0m"

exit 0
