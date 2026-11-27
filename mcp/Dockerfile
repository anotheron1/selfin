FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/selfin-mcp.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["http"]
