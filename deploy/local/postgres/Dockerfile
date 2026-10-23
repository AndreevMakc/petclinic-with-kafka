ARG SERVICE_IMAGE
FROM ${SERVICE_IMAGE}
ADD --checksum=sha256:73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c https://jdbc.postgresql.org/download/postgresql-42.7.14.jar /application/BOOT-INF/lib/postgresql-42.7.14.jar
COPY application-postgresql.yml /application/BOOT-INF/classes/application-postgresql.yml
RUN if [ -f BOOT-INF/classpath.idx ]; then printf '%s\n' '- "BOOT-INF/lib/postgresql-42.7.14.jar"' >> BOOT-INF/classpath.idx; fi
