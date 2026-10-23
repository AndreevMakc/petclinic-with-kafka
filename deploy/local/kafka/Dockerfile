FROM eclipse-temurin:17-jre
ARG SERVICE
WORKDIR /application
COPY spring-petclinic-${SERVICE}/target/spring-petclinic-${SERVICE}-3.4.1.jar petclinic.jar
ENTRYPOINT ["java", "-jar", "/application/petclinic.jar"]
