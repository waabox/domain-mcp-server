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

-   Git cloning via JGit (shallow clone, branch selection)
-   Auto-detection of project language (Java, Node.js/TypeScript)
-   Import-based dependency graph building (no LLM)
-   Per-class/module Claude API analysis (language-aware prompts)
-   PostgreSQL-backed catalog of classes, methods, and endpoints
-   Stack trace correlation with graph-enhanced neighbor resolution
-   MCP stdio + REST dual transport

## Supported Languages

| Language | Parser | Source Root | Entry Points |
|----------|--------|------------|--------------|
| Java | `JavaSourceParser` | `src/main/java` | `@RestController`, `@Controller`, `@KafkaListener`, `@Scheduled`, `@EventListener`, `@SpringBootApplication` |
| Node.js / TypeScript | `NodeJsSourceParser` | `src` | NestJS `@Controller`, Express routes (`app.get`, `router.post`, etc.), well-known files (`main.ts`, `index.ts`, `app.ts`, `server.ts`) |

Language is auto-detected from project marker files: `pom.xml` / `build.gradle` for Java, `package.json` for Node.js/TypeScript.

## Architecture

Java 21 + Spring Boot 3.3 (MCP-enabled)\
JGit for repository cloning\
Per-language source parsers (Java, Node.js/TypeScript)\
Claude API (Sonnet 4.5) for per-class business analysis (language-aware prompts)\
Import-based dependency graph (no LLM needed for graph)\
PostgreSQL persistence (JDBI3)\
MCP stdio transport for Claude Code integration

## MCP Tools

| Tool | Description |
|------|-------------|
| `analyze_project` | Analyze a git repository and index its classes/methods. Must be run before querying context. |
| `list_projects` | List all indexed projects with status and statistics. |
| `get_class_context` | Get business context for a class by fully qualified name (type, description, methods). |
| `get_method_context` | Get detailed method context (business logic, dependencies, exceptions, HTTP endpoint). |
| `get_stack_trace_context` | **Primary Datadog correlation tool.** Takes a stack trace and returns business context for each frame. |

## Installation

### Requirements

- Java 21+
- Maven
- PostgreSQL 14+
- SSH key (for private repos)
- Anthropic API key

### Build

```bash
mvn clean package -DskipTests
```

## Configuration

The server is configured through environment variables referenced in `application.yml`.

### Claude / LLM

| Variable            | Default   | Description                         |
|---------------------|-----------|-------------------------------------|
| `ANTHROPIC_API_KEY` | *(empty)* | API key for Claude domain analysis. |

### Database (PostgreSQL)

| Variable            | Default                                                   | Description                |
|---------------------|-----------------------------------------------------------|----------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://host:port/db?currentSchema=domain_mcp` | JDBC URL including schema. |
| `DATABASE_USERNAME` | `postgres`                                                | PostgreSQL username.       |
| `DATABASE_PASSWORD` | `postgres`                                                | PostgreSQL password.       |

### Git / Repository Access

| Variable              | Default                 | Description                                           |
|-----------------------|-------------------------|-------------------------------------------------------|
| `GIT_SSH_KEY_PATH`    | *(empty)*               | Path to SSH private key for cloning repositories.     |
| `GIT_CLONE_BASE_PATH` | `/tmp/domain-mcp-repos` | Directory where Git repositories are cloned & cached. |

## Running

### REST API (standalone)

```bash
mvn spring-boot:run
```

Starts the web server on port 8080 with REST endpoints for managing projects and querying context.

### Docker

```bash
docker build -t domain-mcp-server .
docker run -p 8080:8080 domain-mcp-server
```

### Kubernetes

Deploy with env vars for DB, SSH key, LLM key.

## Claude Code MCP Setup

The server supports **MCP stdio transport** for direct integration with Claude Code. When running in MCP mode, the server communicates via JSON-RPC over stdin/stdout while keeping REST endpoints available on port 8080.

### 1. Build the JAR

```bash
mvn clean package -DskipTests
```

### 2. Ensure PostgreSQL is running

The MCP server connects to the same database as the REST API. Make sure PostgreSQL is running and the `domain_mcp` schema exists.

### 3. Add to Claude Code MCP config

Add this to your Claude Code MCP settings (`~/.claude/settings.json` or via `/settings` in Claude Code):

```json
{
  "mcpServers": {
    "domain-mcp-server": {
      "command": "java",
      "args": [
        "--enable-preview",
        "-Dspring.profiles.active=mcp",
        "-jar",
        "/absolute/path/to/domain-mcp-server/target/domain-mcp-server-1.0.1.jar"
      ],
      "env": {
        "DATABASE_URL": "jdbc:postgresql://localhost:5432/domain_mcp?currentSchema=domain_mcp",
        "DATABASE_USERNAME": "postgres",
        "DATABASE_PASSWORD": "postgres",
        "ANTHROPIC_API_KEY": "sk-ant-...",
        "GIT_SSH_KEY_PATH" : "/Users/<user>/.ssh/id_rsa"
      }
    }
  }
}
```

Replace `/absolute/path/to/` with the actual path to the project on your machine.

### 4. Verify the connection

Restart Claude Code. The domain-mcp-server tools (`analyze_project`, `list_projects`, `get_class_context`, `get_method_context`, `get_stack_trace_context`) should appear in your available tools.

### How it works

- The `mcp` Spring profile activates stdio transport and redirects all logs to stderr
- stdout is reserved exclusively for JSON-RPC messages
- REST endpoints remain available on port 8080 for populating data via HTTP
- Both transports share the same database and service layer

## Integration with Datadog MCP server

Works seamlessly with the [Datadog MCP server](https://github.com/waabox/datadog-mcp-server).

### Correlation workflow

When investigating production errors, Claude chains both MCP servers automatically:

1. **Datadog MCP** `trace_list_error_traces` — find error traces for a service
2. **Datadog MCP** `log_correlate` — get the full stack trace with log context
3. **Domain MCP** `get_stack_trace_context` — enrich each frame with business context (what the code does, why it exists, its dependencies)
4. If frames are missing context, `list_projects` / `analyze_project` to index the repository first
5. **Domain MCP** `get_class_context` / `get_method_context` — drill deeper into specific classes or methods

This gives you **root cause analysis** that combines runtime observability (Datadog) with domain knowledge (domain-mcp-server).

## Data model

Stored in PostgreSQL (`domain_mcp` schema):

- `projects` — git repositories with status, graph data (JSON), and analysis metadata
- `source_classes` — extracted classes with FQCN, type, description, source file
- `source_methods` — extracted methods with business logic, dependencies, exceptions, HTTP endpoints

## Healthcheck

Minimal `"ok"` / `"up"` HTTP endpoint for probes.

## Roadmap

Additional language parsers (Python, Go), incremental indexing, improved cross-service linking, better LLM patterns.

## Contributing

PRs welcome.

## License

MIT
