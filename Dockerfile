# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (for better caching)
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy the rest of the source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# ---- Run stage ----
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/jdbc.explorer-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]