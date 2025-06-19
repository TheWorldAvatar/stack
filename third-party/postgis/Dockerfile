# Copied from https://github.com/3dcitydb/3dcitydb/blob/c7ca7df2f953e7b4a4a1d1b88f3fdceeb9d3247e/postgresql/Dockerfile
# 3DCityDB PostGIS Dockerfile #################################################
#   Official website    https://www.3dcitydb.net
#   GitHub              https://github.com/3dcitydb
###############################################################################

# Base image changed to pgrouting-extra
FROM pgrouting/pgrouting-extra:15-3.5-3.8.0

# Set 3DCityDB version
ARG CITYDB_VERSION='4.4.0'
ENV CITYDBVERSION=${CITYDB_VERSION}

# Copy SQL scripts
WORKDIR /3dcitydb
COPY SQLScripts .
# This script needs to be executed
COPY --chmod=500 --chown=postgres DockerScripts/3dcitydb-initdb.sh /docker-entrypoint-initdb.d/
# This script needs to be sourced
COPY --chmod=400 --chown=postgres DockerScripts/10_postgis.sh /docker-entrypoint-initdb.d/

# Set labels
LABEL maintainer="Bruno Willenborg"
LABEL maintainer.email="b.willenborg(at)tum.de"
LABEL maintainer.organization="Chair of Geoinformatics, Technical University of Munich (TUM)"
LABEL source.repo="https://github.com/3dcitydb/3dcitydb"
