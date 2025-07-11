# Second stage: copy the output into a clean image
#==================================================================================================
FROM adoptopenjdk/openjdk11:jre-11.0.13_8

WORKDIR /app

# Copy the built JAR and lib directory from the Maven build
COPY target/*.jar /app/
COPY target/lib /app/lib

# Copy the entrypoint script
COPY docker/entrypoint.sh /entrypoint.sh

# Port for Java debugging
EXPOSE 5005

# Run the entrypoint script
ENTRYPOINT ["/entrypoint.sh"]
