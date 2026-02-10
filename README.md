# domain-mcp-server

> Domain-aware MCP server that clones and analyzes your microservices
> (code + docs) to extract business logic, APIs, and data models,
> storing everything in PostgreSQL for fast, structured querying.
> Designed to work standalone or in concert with other MCP servers such
> as Datadog MCP.

## Table of Contents

-   Motivation
-   What this MCP does
-   Architecture
-   MCP Tools / Capabilities
-   Installation
-   Running
-   Using from an MCP client
-   Integration with Datadog MCP server
-   Data model
-   Healthcheck & observability
-   Status / Roadmap
-   Contributing
-   License

## Motivation

Modern backends age into a zoo of microservices and monorepos.
domain-mcp-server centralizes business/domain knowledge, linking code,
docs, APIs, and DB models into a single structured repository for LLMs
and tooling.

## What this MCP does

-   Git cloning & repository indexing\
-   Documentation + code analysis\
-   LLM-assisted domain extraction\
-   API & DB model mapping\
-   PostgreSQL-backed catalog\
-   Fast semantic queries via MCP

## Architecture

Java + Spring (MCP-enabled)\
Git SSH clone + caching\
LLM enrichment (Claude-compatible)\
PostgreSQL persistence (JDBI)\
Minimal HTTP healthcheck (no Actuator)

## MCP Tools

-   register_service\
-   describe_service_domain\
-   list_service_apis\
-   list_service_models\
-   query_domain

## Installation

### Requirements

Java 21+, Maven, PostgreSQL, SSH key, LLM API key.

### Build

    mvn clean package

## Running

### Local

    mvn spring-boot:run

### Docker

    docker build -t domain-mcp-server .
    docker run -p 8080:8080 domain-mcp-server

### Kubernetes

Deploy with env vars for DB, SSH key, LLM key.

## Integration with Datadog MCP server

Works seamlessly with:\
https://github.com/waabox/datadog-mcp-server

Combine runtime data (traces, logs, metrics) with domain semantics
(APIs, models, business rules). Ideal for debugging, root-cause
analysis, and cross-service reasoning.

## Data model

Stored in PostgreSQL: - services\
- domain_concepts\
- apis\
- models/entities

## Healthcheck

Minimal `"ok"` / `"up"` HTTP endpoint for probes.

## Roadmap

Incremental indexing, improved cross-service linking, better LLM patterns.

## Contributing

PRs welcome.

## License

MIT
