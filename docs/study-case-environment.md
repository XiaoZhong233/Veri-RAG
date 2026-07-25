# Study Case Local Environment

This environment keeps the case-study demo small: MySQL stores relational application data and Redis 8 provides the former Redis Stack capabilities for vector retrieval, caching, and short-lived conversation state.

## Services

| Service | Purpose | Local endpoint |
| --- | --- | --- |
| MySQL 8.4 | Document metadata, ingestion jobs, durable conversation history, evaluation cases, and feedback | `localhost:3306` |
| Redis 8.2.7 | RedisJSON documents, HNSW vectors, metadata filtering, retrieval cache, and active conversation state | `localhost:6379` |
| Grafana OpenTelemetry LGTM | Local metrics, traces, and logs | Grafana: `http://localhost:3000`, OTLP HTTP: `http://localhost:4318` |

Redis 8 includes the Search, JSON, and vector functionality previously distributed as Redis Stack. OCR for scanned PDFs belongs in the application ingestion image (for example, Apache Tika plus Tesseract), not in a permanent middleware container.

## Scanned PDF OCR

The application first uses the PDF text layer. If the extracted text averages fewer than
`RAG_OCR_MIN_CHARS_PER_PAGE` visible characters per page, it falls back to Apache Tika's
Tesseract OCR integration.

Install Tesseract and both Chinese/English language data before uploading scanned PDFs:

```bash
# macOS (Homebrew)
brew install tesseract tesseract-lang

# Ubuntu/Debian
sudo apt-get install tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng

tesseract --list-langs
```

The language list must include `chi_sim` and `eng`. OCR defaults to 300 DPI, a 50-page
limit, and a 120-second per-page Tesseract timeout. Override these values with the
`RAG_OCR_*` settings in `.env.example`. If a scanned PDF needs OCR but Tesseract is
missing, ingestion fails explicitly instead of marking an empty document as successful.

On Apple Silicon Homebrew, Tesseract may be installed under `/opt/homebrew/opt/tesseract/bin`
without being visible to applications started from an IDE. Start the app with that location on
`PATH` when `command -v tesseract` is empty:

```bash
export PATH="/opt/homebrew/opt/tesseract/bin:$PATH"
```

## Start and stop

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

Stop containers without deleting data:

```bash
docker compose stop
```

Remove containers while retaining named volumes:

```bash
docker compose down
```

## Connection values for the Spring Boot application

When the application runs directly on the host:

```text
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/rag?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=veri_rag
SPRING_DATASOURCE_PASSWORD=veri_rag_dev
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=veri_rag_dev
SPRING_AI_VECTORSTORE_REDIS_INITIALIZE_SCHEMA=true
SPRING_AI_VECTORSTORE_REDIS_INDEX_NAME=veri-rag-index
SPRING_AI_VECTORSTORE_REDIS_PREFIX=veri-rag:embedding:
SPRING_AI_VECTORSTORE_REDIS_DISTANCE_METRIC=COSINE
SPRING_AI_VECTORSTORE_REDIS_VECTOR_ALGORITHM=HNSW
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://localhost:4318/v1/metrics
```

If the application is later added to this Compose network, replace `localhost` with the service names `mysql`, `redis`, and `observability`.

## Linux container deployment

`Dockerfile` uses a multi-stage Java 21 build and installs `tesseract-ocr`,
`tesseract-ocr-chi-sim`, and `tesseract-ocr-eng` in the runtime image. The OCR executable
and language data therefore travel with the application image; no Tesseract installation is
required on the Linux host.

Build the application image from the repository root:

```bash
docker build -t veri-rag:latest .
docker run --rm --entrypoint tesseract veri-rag:latest --list-langs
```

The second command should show `chi_sim` and `eng`. In production, inject secrets and
service endpoints as environment variables rather than copying the development `.env` file
into the image. Mount persistent storage for uploaded source files:

```bash
docker run -d --name veri-rag-app -p 8080:8080 \
  --network veri-rag_default \
  --env-file .env.production \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://mysql:3306/rag?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  -e SPRING_DATA_REDIS_HOST=redis \
  -e OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://observability:4318/v1/traces \
  -e OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://observability:4318/v1/metrics \
  -v veri-rag-files:/app/file \
  veri-rag:latest
```

The database, Redis, and observability hostnames above assume the app container shares their
Docker network. Keep `RAG_OCR_MAX_PAGES` and `RAG_OCR_TIMEOUT_SECONDS` bounded in production,
because OCR is CPU-intensive.

## Quick checks

```bash
docker compose exec mysql mysqladmin ping -h localhost -u root -proot_dev
docker compose exec redis redis-cli -a veri_rag_dev ping
docker compose exec redis redis-cli -a veri_rag_dev FT._LIST
```

Expected results are `mysqld is alive`, `PONG`, and a Redis search-index list (initially empty).

## Spring AI dependency to add during application integration

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-redis</artifactId>
</dependency>
```

MySQL also needs its JDBC driver and either Spring Data JDBC/JPA or plain JDBC. Those application dependencies are intentionally separate from the middleware-only Compose setup.
