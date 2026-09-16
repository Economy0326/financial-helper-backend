# Render Web Service image for the Java 21 Spring Boot backend.
FROM gradle:9.5.1-jdk21 AS build

WORKDIR /workspace
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_OPTS=""
EXPOSE 10000

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
