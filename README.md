<p align="left">
  <!-- Build -->
  <a href="https://github.com/waabox/domain-mcp-server/actions">
    <img src="https://github.com/waabox/domain-mcp-server/actions/workflows/ci.yml/badge.svg" alt="Build Status" />
  </a>

  <!-- Java -->
  <img src="https://img.shields.io/badge/Java-21-orange?logo=java" alt="Java 21" />

  <!-- Spring Boot -->
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?logo=springboot" alt="Spring Boot" />

  <!-- MCP -->
  <img src="https://img.shields.io/badge/MCP-Model%20Context%20Protocol-blue" alt="MCP" />

  <!-- Datadog MCP compatibility -->
  <img src="https://img.shields.io/badge/Works%20with-Datadog%20MCP-purple" alt="Works with Datadog MCP" />

  <!-- Docker -->
  <a href="https://github.com/waabox/domain-mcp-server/pkgs/container/domain-mcp-server">
    <img src="https://img.shields.io/badge/Docker-available-blue?logo=docker" alt="Docker image" />
  </a>

  <!-- Release -->
  <a href="https://github.com/waabox/domain-mcp-server/releases">
    <img src="https://img.shields.io/github/v/release/waabox/domain-mcp-server?color=blue" alt="Latest Release" />
  </a>

  <!-- License -->
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT License" />
</p>

# domain-mcp-server

Domain-aware MCP server that clones and analyzes your microservices
(code + docs) to extract business logic, APIs, and data models,
storing everything in PostgreSQL for fast, structured querying.
Designed to work standalone or in concert with other MCP servers such
as Datadog MCP.

## Motivation

Modern backends age into a zoo of microservices and monorepos.
domain-mcp-server centralizes business/domain knowledge, linking code,
docs, APIs, and DB models into a single structured repository for LLMs
and tooling.

## What this MCP does

-   Git cloning & repository indexing
-   Documentation + code analysis
-   LLM-assisted domain extraction
-   API & DB model mapping
-   PostgreSQL-backed catalog
-   Fast semantic queries via MCP

## Architecture

Java + Spring (MCP-enabled)\
Git SSH clone + caching\
LLM enrichment (Claude-compatible)\
PostgreSQL persistence (JDBI)\
Minimal HTTP healthcheck (no Actuator)

## MCP Tools

-   register_service
-   describe_service_domain
-   list_service_apis
-   list_service_models
-   query_domain

## Installation

### Requirements

Java 21+, Maven, PostgreSQL, SSH key, LLM API key.

### Build

    mvn clean package

## Running

## Configuration

The server is configured entirely through environment variables referenced in `application.yml`.  
Below are only the variables actually supported by this project.

### Server

### Claude / LLM

| Variable            | Default   | Description                                                                |
|---------------------|-----------|----------------------------------------------------------------------------|
| `ANTHROPIC_API_KEY` | *(empty)* | API key for Claude domain analysis.                                        |


### Database (PostgreSQL)

| Variable            | Default                                                   | Description                |
|---------------------|-----------------------------------------------------------|----------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://host:port/db?currentSchema=domain_mcp` | JDBC URL including schema. |
| `DATABASE_USERNAME` | `postgres`                                                | PostgreSQL username.       |
| `DATABASE_PASSWORD` | `postgres`                                                | PostgreSQL password.       |

### Git / Repository Access

| Variable               | Default                  | Description                                              |
|------------------------|--------------------------|----------------------------------------------------------|
| `GIT_SSH_KEY_PATH`     | *(empty)*                | Path to SSH private key for cloning repositories.        |
| `GIT_CLONE_BASE_PATH`  | `/tmp/domain-mcp-repos`  | Directory where Git repositories are cloned & cached.    |


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

Stored in PostgreSQL: - services
- domain_concepts
- apis
- models/entities

## Healthcheck

Minimal `"ok"` / `"up"` HTTP endpoint for probes.

## Roadmap

Incremental indexing, improved cross-service linking, better LLM patterns.

## Contributing

PRs welcome.

## License

MIT
