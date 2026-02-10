# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

Domain MCP Server - Analyzes git repositories using Claude Code in Docker containers and extracts business information (classes, methods, endpoints) for Datadog stack trace correlation.

## Technology Stack

- **Language**: Java 21
- **Framework**: Spring Boot 3.3.6
- **Database**: PostgreSQL with JDBI3
- **Migrations**: Flyway
- **Containers**: TestContainers for running Claude Code CLI

## Development Commands

```bash
# Build
mvn clean compile

# Run tests
mvn test

# Run application
mvn spring-boot:run

# Package
mvn package -DskipTests
```

## Architecture

```
src/main/java/co/fanki/domainmcp/
├── analysis/          # Code context and class/method extraction
│   ├── domain/        # SourceClass, SourceMethod, repositories
│   └── application/   # CodeContextService, controllers
├── container/         # Docker container management
│   ├── domain/        # AnalysisContainer, ContainerImage
│   └── application/   # ContainerAnalysisService
├── project/           # Project management
│   ├── domain/        # Project entity, RepositoryUrl
│   └── application/   # ProjectController
├── config/            # Spring configuration
└── shared/            # Shared utilities, exceptions
```

## Key Endpoints

- `POST /api/projects/analyze` - Analyze a repository and save to DB
- `GET /api/context/class/{className}` - Get class context for Datadog correlation
- `GET /api/context/method` - Get method context
- `GET /api/projects` - List analyzed projects

## Environment Variables

- `ANTHROPIC_API_KEY` - Claude API key (required)
- `GIT_SSH_KEY_PATH` - SSH key for private repos (optional)
- `DATABASE_URL` - PostgreSQL connection URL

## Datadog + Domain MCP Correlation Workflow

When investigating errors from Datadog, ALWAYS follow this workflow:

1. **Get error traces from Datadog** using `trace_list_error_traces` or `trace_inspect_error_trace`
2. **Correlate logs** with `log_correlate` to get the full stack trace
3. **IMMEDIATELY call `get_stack_trace_context`** with the stack trace frames (className, methodName, lineNumber) extracted from step 2
4. If `get_stack_trace_context` returns `missingContext` frames, the project may not be indexed yet — use `list_projects` to check, and `analyze_project` to index it
5. For deeper investigation on a specific class or method, use `get_class_context` or `get_method_context`

This correlation gives you business context (what the code does, why it exists, its dependencies) on top of the raw Datadog error data, enabling root cause analysis.
