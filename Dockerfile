FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
COPY backend backend
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/target/mn-check-1.6.2.jar app.jar
COPY frontend frontend

ENV PORT=4173

EXPOSE 4173
CMD ["java", "-jar", "app.jar"]
