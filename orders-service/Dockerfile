FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY auth-service/pom.xml auth-service/pom.xml
COPY orders-service/pom.xml orders-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY orders-service/src orders-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl orders-service -am package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/orders-service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]