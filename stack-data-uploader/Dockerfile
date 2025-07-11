# syntax=docker/dockerfile:1.4
# Second stage: copy the output into a clean image
#==================================================================================================
FROM adoptopenjdk/openjdk11:jre-11.0.13_8

WORKDIR /app

COPY target/*.jar /app/
COPY target/lib /app/lib

COPY docker/entrypoint.sh /entrypoint.sh

EXPOSE 5005

ENTRYPOINT ["/entrypoint.sh"]
