# syntax=docker/dockerfile:1

# Build the Spring Boot executable jar in an isolated, reproducible stage.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package


# Tesseract must be in the same runtime image as the application: scanned PDFs are
# processed by Apache Tika in the Spring Boot process, not by the database containers.
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        tesseract-ocr-chi-sim \
        tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --create-home spring

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    RAG_OCR_ENABLED=true \
    RAG_OCR_LANGUAGE=chi_sim+eng \
    FILE_UPLOAD_PATH=/app/file

RUN mkdir -p /app/file && chown -R spring:spring /app

USER spring
VOLUME ["/app/file"]
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
