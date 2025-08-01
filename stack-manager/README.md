# The Stack Manager

<img align="right" width="250" height="250" src="./img/twa-stack-logo-padded.svg">

In the commands below placeholders are shown as `<STACK NAME>`, you will need to substitute in the required value when running the command.

## Prerequisites

### Hardware

* A total RAM size of 32GB is recommended for smooth execution, particularly in Microsoft Windows.

### Software

* Building and running a stack has been tested in Microsoft Windows and to some degree Linux, it has not been tested within a MacOS environment.
* Install [Git](https://git-scm.com/downloads).
* Install [Docker Desktop, or the Docker Engine and the Docker Compose plugin](https://docs.docker.com/engine/install). On Linux systems where Docker is not available, [podman](https://podman.io/) can also be used. See [here](./README-podman.md) for further details. Other container systems are currently not supported.
* Preferably also install [VSCode](https://code.visualstudio.com/Download), required for development.

#### For development

* Install a [Java 11+ SDK](https://adoptium.net).
* Optionally, install [Python](https://www.python.org/downloads).

### Accounts

* A [GitHub account](https://github.com), with an appropriate `read:packages` (or `write:packages` if developing) [access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token).

## Spinning up a Stack

To spin up the stack (with default settings) please follow the instructions below:

1. If you haven't already, test your access to the CMCL Docker registry, simply run

    ```console
    docker login ghcr.io
    ```

    If you are not already logged in then, when prompted, enter your GitHub username and an access token with scope to read (and write to, if developing) the container repository.

2. Open the Workspace in the `Deploy/stacks/dynamic` directory in VSCode (or go to the `stack-manager` subdirectory within it in a `bash` terminal).

3. Create two files called `postgis_password` and `geoserver_password` in the `stack-manager/inputs/secrets/` directory. Populate the files with the intended passwords for PostGIS and GeoServer, respectively.
    It is also possible to add a `blazegraph_password` file to initialise the Blazegraph container with authentication enabled but this is currently incompatible with most agents, a future update to the `stack-client` library will help resolve this issue.

4. From a terminal in the `stack-manager` directory, start the `stack-manager` container by running the following:

    ```console
    ./stack.sh start <STACK NAME>
    ```

    This will pull the required Docker images and start the core stack containers.
    This should bring up 7 containers, i.e. gdal, ontop, adminer, postgis, blazegraph, nginx, and geoserver.
    In case not all containers start up successfully, try running the command again or check the logs for the `stack-manager` container.
5. Accessing the GUI webpages for the containers:
    * The default exposed port number exposed by Docker is `3838`. To check the exposed port number, run

        ```console
        docker service ls --filter name=<STACK NAME>-nginx
        ```

    * The Geoserver GUI should be available at <http://localhost:3838/geoserver/>. Log in using the username `admin` and the password specified in the `geoserver_password` file.
    * The Adminer (PostgreSQL GUI) at <http://localhost:3838/adminer/ui/?username=postgres&pgsql=>. Enter `<STACK NAME>-postgis:5432` as the `Server` and the value from the `postgis_password` file as the `Password`. The `Database` slot can be left blank if you don't know what it should be.
    * The Ontop GUI should be available at <http://localhost:3838/ontop/ui>.
    * The Blazegraph Workbench should be available at <http://localhost:3838/blazegraph/ui>.

## Built-in containers

There are several containers built into the stack-manager that perform common tasks such as uploading, storing, and visualising data.
Their service config files can be found in the stack-client [resources directory].

By default the services listed in the [defaults.txt](./src/main/resources/com/cmclinnovations/stack/defaults.txt) resource file.
The other, optional, services can be started after the default ones by specifying them in the appropriate stack config file, as described in the [Custom and optional containers](#custom-and-optional-containers) section.

## Specifying custom containers

It is possible to spin up other containers in the stack using the stack-manager.
This is particularly useful for adding agents into a stack.

The stack-manager will handle creating the containers so there is no need to create the containers using `docker` or `docker compose` before running the stack-manager.

If the configuration file for a container is present when a stack is initially spun up then it will be added then.
To add a container after a stack has been spun up just add the configuration file and run the stack-manager again, the previously started containers will be unaffected.

> :warning: **Warning:** The stack-manager does not attempt to build a container's image so all images need to be built prior to running the stack-manager.

### Benefits

Spinning a container up via the stack-manager provides the following benefits:

* The container is added to the stack's Docker network, this allows the agent to connect to the other stack containers using their internal URLs.
* The URLs, usernames and passwords of other containers in the stack can be retrieved using the `ContainerClient::readEndpointConfig` method at runtime, rather than having to provide them through environment variables or `.properties` files.
* Allows the classes and methods available through the stack-clients library to be used to add new data (particularly geospatial data) into the stack in a clean an consistent way.

### Service configuration files

To add custom containers put a `.json` file for each container into the [stack-manager/inputs/config/services](./inputs/config/services/) directory.
An example of the structure of this file, the one for the Ontop container, is as follows:

```json
{
    "ServiceSpec": {
        "Name": "adminer",
        "TaskTemplate": {
            "ContainerSpec": {
                "Image": "adminer:latest"
            }
        }
    },
    "endpoints": {
        "ui": {
            "url": "http://localhost:8080",
            "externalPath": "/adminer/ui"
        }
    }
}
```

The three top-level nodes are:

* `"type"`(not used in the example above): This is used to run container specific Java code when the container is started and should be ignored for user-specified containers.
* `"ServiceSpec"`: This is based on the Docker API container creation request format documented [here][ServiceSpec].
  To specification of `"Configs"` and `"Secrets"` has been simplified so that only the name is required.
* `"endpoints"`: This is where mappings between the internal URLs and the externally accessible paths can be specified.
  The internal URL should be the one you would use if you were logged into the container and the external path is appended to `http://localhost:3838`

Other, more complex, examples of configuration files can be seen in the stack-client's [resources directory].

### Mounting data into containers

Some containers need application specific configuration files. There are several ways to mount these files into containers. These are summarised in the following table and described in more detail below.

| Type       | Description                                                                               | Use case                                                                                                         | Location                                                                                                                                                                       |
| ---------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Secret     | A single file that is stored encrypted in the stack and can be mounted into any container | Adding files that contain sensitive data like usernames and passwords                                            | [`stack-manager/inputs/secrets`](./inputs/secrets/)                                                                                                                            |
| Volume     | A directory that is stored unencrypted in the stack and can be mounted into any container | Adding files to one or more containers that will persist if the containers are stopped                           | [`stack-manager/inputs/data`](./inputs/data/)                                                                                                                                  |
| Bind mount | A file or directory that is directly mounted into a container from the host               | Useful for testing configurations when the container doesn't need to be restarted for the changes to take effect | Any absolute path, as seen in WSL (not Git Bash, CMD or PowerShell) when running in Windows, or a path relative to the [`stack-manager/inputs/data`](./inputs/data/) directory |

#### Secrets

Secrets are the best way to store sensitive information like usernames and passwords in a stack.

The secret is added to the stack the first time the stack-manager is run.
To modify the value of a secret the stack needs to be stopped and restarted.

The source files should be put in the [`stack-manager/inputs/secrets`](./inputs/secrets/) directory.
By default secrets are mounted inside the container in the `/run/secrets/` directory in a file with the same name as the secret.
It is possible to specify a custom target path by adding the `File.Name` nodes.

The example below shows a snippet of a service config file with a secret named `secret_with_default_path` that specifies that the content of the file `stack-manager/inputs/secrets/secret_with_default_path` should be mounted into the container at `/run/secrets/secret_with_default_path`.
The other secret named `secret_with_custom_path` specifies that the file `stack-manager/inputs/secrets/secret_with_custom_path` should be mounted into the container at `/custom/secret/path`.

```json5
{
    "ServiceSpec": {
        ...
        "TaskTemplate": {
            "ContainerSpec": {
                ...
                "Secrets": [
                    {
                        "SecretName": "secret_with_default_path"
                    },
                    {
                        "SecretName": "secret_with_custom_path",
                        "File": {
                            "Name":"/custom/secret/path"
                        }
                    }
                ]
                ...
```

The corresponding `stack-manager/inputs` directory would then need the following files:

``` bash
secrets/
    secret_with_default_path
    secret_with_custom_path
```

For more information on how secrets work in Docker see [here](https://docs.docker.com/engine/swarm/secrets).

#### Volumes

Volumes can be mounted into containers so that their code can access the files container within them.
This is the preferred method for mounting non-sensitive data in production environments.

See the [Stack configuration](#stack-configuration) section for instructions on how to automatically copy files into a volume when the stack-manager is run.

The example below shows a snippet of a service config file with a volume named `vis-files` being mounted into the container at `/var/www/html`.
The stack-manager will have copied the contents of the `stack-manager/inputs/data/vis-files` directory into that volume before starting the containers.

```json5
{
    "ServiceSpec": {
       ...
        "TaskTemplate": {
            "ContainerSpec": {
               ...
                "Mounts": [
                    {
                        "Type": "volume",
                        "Source": "vis-files",
                        "Target": "/var/www/html"
                    }
                ]
                ...
```

The corresponding `stack-manager/inputs` directory would then need the following directories and the volume would need to be specified in the stack config file as described [here](#file-format) :

``` bash
data/
    vis-files/
```

For more information on how volumes work in Docker see [here](https://docs.docker.com/storage/volumes).

#### Bind mounts

Bind mounts allow you to directly mount a file or directory on the host machine into a container.
This is often useful when developing and debugging a container that doesn't need to be restarted if the file(s) change.

The example below shows a snippet of a service config file where the contents of the `stack-manager/inputs/data/fia-queries` directory is mounted into the container at `/app/queries`.

```json5
{
    "ServiceSpec": {
       ...
        "TaskTemplate": {
            "ContainerSpec": {
               ...
                "Mounts": [
                    {
                        "Type": "bind",
                        "Source": "fia-queries",
                        "Target": "/app/queries"
                    }
                ]
                ...
```

For more information on how bind mounts work in Docker see [here](https://docs.docker.com/storage/bind-mounts).

## Stack configuration

A stack config file, with the same name as the stack being spun up, can be placed in the [stack-manager/inputs/config](./inputs/config/) directory to control what is include when the stack-manager is run.

For example a stack called "test" could be configured by providing a file with the path `stack-manager/inputs/config/test.json`.

### Custom and optional containers

By default the stack will start the built-in default services and then all of the user-supplied custom services.
There are also several optional built-in services.

The list of services that are started can be modified by adding them to either the `"includes"` or `"excludes"` lists under a `"services"` node in the stack configuration file.

> :memo: **Note**: When adding services to the `includes` and `excludes` sections, use the file name (excluding the `.json` file extension) from the stack config files, rather than the name specified in `ServiceSpec`.

### Volumes

The stack config file also allows you to specify that the contents of certain directories get copied into volumes when the stack-manager is run.
Changes to those files are copied into the volume each time the stack-manager is run.

See the [Mounting data into containers](#mounting-data-into-containers) section for instructions on how to mount a volume into a container.

To remove files or directories from a volume you either need to stop any container that are using it and then remove the volume, or attach a shell to a container that has the volume mounted and delete the files directly.

### Running on isolated systems

Sometimes a stack needs to be run on a system that cannot make outgoing requests, usually for reasons of security.

By default the stack-manager attempts to pull the latest version of each container image that matches the specified tag, which can lead to long waits for the calls to timeout.
This can be avoided by starting the stack in "isolated" mode by adding `"isolated": true` to the stack configuration file.
The default value is `false`.

This option doesn't affect `docker compose` commands so it will still attempt to pull the stack-manager and stack-data-uploader images if they are missing.

If an image tag starts with `localhost/` then it will be assumed to be only present locally and so the stack-manager will not try to pull it.

### File format

The format of the stack configuration file is as follows:

```json5
{
    // Tell the stack-manager not to try to pull images. (default: 'false')
    "isolated": true,
    "services": {
        "includes": [
            // List of non-default services to start in addition to the default ones. (Optional)
        ],
        "excludes": [
            // List of default and/or explicitly included services that should not be spun up.
            // This will cause issues if another service requires one of the excluded ones. (Optional)
        ]
    },
    "volumes": {
        // Key-value pairs of volume name and source directory in the 'stack-manager/inputs/data' directory. (Optional)
        "<volume name>": "<source directory>"
    }
}
```

## Federated SPARQL repositories

An RDF4J server is added to each stack and allows for SPARQL federation across all sparql endpoints within a TWA Stack as well as external endpoints and other TWA Stacks.

### Repositories

#### Dataset repository

A dataset repository is one that federates across each endpoint related to a dataset in a stack.
When a new dataset is added to this stack a new federated dataset repository will be added.
This repository will persist for long as the dataset does.

#### Incoming stack repository

An incoming stack repository federates across each [dataset repository](#dataset-repository) in a stack.
This repository will be used by external clients querying data in this stack.
When a new dataset is added to this stack its dataset repository will be added to this federation.
This repository will persist for as long as stack does but changes as datasets are added and removed.

This repository can be accessed from outside the stack at `http://localhost:<PORT>/sparql/query`.

#### Outgoing stack endpoint

An outgoing stack endpoint is a federation between internal and external endpoints.
This endpoint should be used by agents internal to this stack when accessing internal and external data.
It should not be used by external clients in case loops are created between stacks.
This repository will persist for as long as the stack does but will change if any external endpoints are added or removed.

This repository can be accessed from inside the stack at `http://<STACK NAME>-rdf4j:8080/rdf4j-server/repositories/stack-outgoing/`.

External endpoints can be registered in a stack by specifying an ID, display name, and the URL of the endpoint in a JSON file in the directory `stack-manager/inputs/config/external_endpoints/`.
As mentioned above, when referencing another stack it is recommended to point to its incoming endpoint as pointing to its outgoing one might cause a loop.

For example to include a stack called "test2" in the outgoing federation you would add this file:

```json
{
    "id": "test2",
    "name": "Second test stack",
    "url": "https://test2.theworldavatar.io/sparql/query"
}
```

To include the OpenStreetMap SPARQL endpoint in the outgoing federation you could add a file like this one:

```json
{
    "id": "openstreetmap-qlever",
    "name": "OSM QLever",
    "url": "https://qlever.cs.uni-freiburg.de/api/osm-planet"
}
```

### Design

The diagram below illustrates how each of the federated repositories are related to each other.

```mermaid
graph TB
    subgraph Stack 2
        subgraph Access Agent
            outgoing-2(Outgoing) -.-> incoming-2(Incoming)
            incoming-2 -.-> DC(Dataset C)
        end
        DC -.-> DCO([Dataset C Ontop])
        agent-2{Agent} --> outgoing-2
    end
    
    subgraph Stack 1
        subgraph Access Agent
            outgoing-1(Outgoing) -.-> incoming-1(Incoming)
            incoming-1 -.-> DA(Dataset A)
            incoming-1 -.-> DB(Dataset B)
        end
        DA -.-> DAB([Dataset A Blazegraph])
        DB -.-> DBB([Dataset B Blazegraph])
        DB -.-> DBO([Dataset B Ontop])
        agent-1{Agent} --> outgoing-1
    end

    outgoing-1:::fed -.-> incoming-2:::fed
    outgoing-2:::fed -.-> incoming-1:::fed

    ex_non_fed(["External e.g. osm"]):::nonfed
    outgoing-1 -.-> ex_non_fed([External])
    outgoing-2 -.-> ex_non_fed

    classDef fed fill:#0e7e44, stroke:#ffffff, color:#fff
    class DA,DB,DC fed
    
    classDef nonfed fill:#0d6c7e, stroke:#ffffff, color:#fff
    class DAB,DBB,DBO,DCO nonfed

```

With the following key

```mermaid
graph TB
    classDef fed fill:#0e7e44, stroke:#ffffff, color:#fff
    
    classDef nonfed fill:#0d6c7e, stroke:#ffffff, color:#fff

    fed("Federated SPARQL repository"):::fed
    non_fed(["Non-federated SPARQL endpoint"]):::nonfed

    fed -."Federation".-> non_fed
    non_fed --"SPARQL query"--> fed

```

## Example - including a visualisation

This example explains how to spin up a TWA-VF based visualisation container within a stack. The visualisation container requires a volume called `vis-files` to be populated and secrets `mapbox_username`, and `mapbox_api_key` to be created.
The steps to configure the stack are as follows:

* Enable the visualisation container by adding it to the `services` `includes` list in the stack config file.
* Specify the sub-directory of the `stack-manager/inputs/data/` folder from which the custom files that configure the visualisation should be copied, in this example the sub-directory is called `webspace`.
* Copy the custom files that configure the visualisation in to that directory, in this example `stack-manager/inputs/data/webspace`.
* Create `mapbox_username` and `mapbox_api_key` files in the `stack-manager/inputs/secrets` and populate with the relevant credentials.

The final stack config file should contain the following content:

```json
{
    "services": {
        "includes": [
            "visualisation"
        ]
    },
    "volumes": {
        "vis-files": "webspace"
    }
}
```

### Adding the Feature Info Agent

To also include a Feature Info Agent you should modify the stack config to something like the following:

```json
{
    "services": {
        "includes": [
            "visualisation",
            "feature-info-agent"
        ]
    },
    "volumes": {
        "vis-files": "webspace",
        "fia-queries": "fia-queries"
    }
}
```

Which specifies that the Feature Info Agent query files will be expected to be placed in the `stack-manager/inputs/data/fia-queries` directory.

## Example - running without external dependencies

This is a work in progress so some external calls may still be made.

### Docker/Podman images

TBC

### GeoServer plugins

The geoserver plugin(s) are now cached in a volume.
This makes it easier to pre-downloaded them so that they are already present when the GeoServer container is started.
To do this just copy the plugin `.zip` files into the `inputs\data\geoserver_plugins` directory and then add the following volume entry to the relevant stack config file:

```json
{
    "volumes": {
        "geoserver_plugins": "geoserver_plugins"
    }
}
```

## Debugging the Stack Manager in VSCode

1. Add the following entry into top level node the JSON file `stack-manager/.vscode/settings.json`, creating the file if it doesn't exist.

    ```json
    "debug.port": "<DEBUG PORT>"
    ```

    A value around `5005` for `<DEBUG PORT>` should be appropriate.

2. In the `Run and Debug` side panel of VSCode run the `Debug (stack-manager)` configuration.

## Developing the Stack Manager in VSCode

You will need permission to push to the CMCL package repository to be able to build the stack-manager project

1. Follow the instructions in step 1. of [Debugging the Stack Manager in VSCode](#debugging-the-stack-manager-in-vscode)

2. Create two files called `repo_username.txt` and `repo_password.txt` in the `stack-manager/docker/credentials` directory. Populate the files with your GitHub username and access token (with scope to write packages), respectively.

3. In the `Run and Debug` side panel of VSCode run the `Build and Debug (stack-manager)` configuration.

## Further remarks

* In case any of the endpoints is not resolvable after spinning up the stack, try exploring whether the specified ports might already be assigned to other program.

* To remove an Docker Swarm service (e.g. geoserver), run

    ```console
    docker service rm <STACK NAME>-<SERVICE NAME>
    ```

* To remove a single Docker Swarm stack, run

    ```console
    docker stack rm <STACK NAME>
    ```

* To (permanently) remove all Docker Swarm services, run

    ```console
    docker swarm leave --force
    ```

<!-- Links -->
[resources directory]: ../stack-clients/src/main/resources/com/cmclinnovations/stack/services/built-ins/
[ServiceSpec]: https://docs.docker.com/reference/api/engine/latest/#tag/Service/operation/ServiceCreate
