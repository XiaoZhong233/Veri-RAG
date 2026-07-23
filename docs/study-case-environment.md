# Study Case Local Environment

This environment keeps the case-study demo small: MySQL stores relational application data and Redis 8 provides the former Redis Stack capabilities for vector retrieval, caching, and short-lived conversation state.

## Services

| Service | Purpose | Local endpoint |
| --- | --- | --- |
| MySQL 8.4 | Document metadata, ingestion jobs, durable conversation history, evaluation cases, and feedback | `localhost:3306` |
| Redis 8.2.7 | RedisJSON documents, HNSW vectors, metadata filtering, retrieval cache, and active conversation state | `localhost:6379` |
| Grafana OpenTelemetry LGTM | Local metrics, traces, and logs | Grafana: `http://localhost:3000`, OTLP HTTP: `http://localhost:4318` |

Redis 8 includes the Search, JSON, and vector functionality previously distributed as Redis Stack. OCR for scanned PDFs belongs in the application ingestion image (for example, Apache Tika plus Tesseract), not in a permanent middleware container.

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
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/veri_rag?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
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
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

If the application is later added to this Compose network, replace `localhost` with the service names `mysql`, `redis`, and `observability`.

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
