FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

WORKDIR /deployments

COPY target/*.jar /deployments/application.jar

EXPOSE 8080

USER 185

ENTRYPOINT ["java", "-jar", "/deployments/application.jar"]