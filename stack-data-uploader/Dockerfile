# syntax=docker/dockerfile:1.4
# Second stage: copy the output into a clean image
#==================================================================================================
FROM eclipse-temurin:11-jre
WORKDIR /app

COPY target/*.jar /app/
COPY target/lib /app/lib

COPY docker/entrypoint.sh /entrypoint.sh

EXPOSE 5005

ENTRYPOINT ["/entrypoint.sh"]
