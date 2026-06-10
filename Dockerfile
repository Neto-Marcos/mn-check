FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY backend/src backend/src
ADD https://jdbc.postgresql.org/download/postgresql-42.7.5.jar /app/lib/postgresql.jar
RUN javac -encoding UTF-8 -d backend/out backend/src/MmCheckServer.java

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/backend/out backend/out
COPY --from=build /app/lib/postgresql.jar lib/postgresql.jar
COPY frontend frontend
RUN mkdir -p data/uploads

ENV PORT=4173
ENV MMCHECK_DB_PATH=/app/data/java-db.json
ENV MMCHECK_UPLOAD_DIR=/app/data/uploads

EXPOSE 4173
CMD ["java", "-cp", "backend/out:lib/postgresql.jar", "MmCheckServer"]
