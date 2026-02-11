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

## Table of Contents

- [Motivation](#motivation)
- [What this MCP does](#what-this-mcp-does)
- [Supported Languages](#supported-languages)
- [Architecture](#architecture)
- [Interacting with Claude](#interacting-with-claude)
- [MCP Tools](#mcp-tools)
  - [list_projects](#list_projects)
  - [search_project](#search_project)
  - [get_class_context](#get_class_context)
  - [get_method_context](#get_method_context)
  - [get_stack_trace_context](#get_stack_trace_context)
  - [get_class_dependencies](#get_class_dependencies)
  - [get_project_overview](#get_project_overview)
  - [get_service_api](#get_service_api)
  - [REST-Only Endpoints](#rest-only-endpoints)
- [Installation](#installation)
- [Configuration](#configuration)
- [Running](#running)
- [Indexing Projects](#indexing-projects)
- [Claude Code MCP Setup](#claude-code-mcp-setup)
- [Integration with Datadog MCP server](#integration-with-datadog-mcp-server)
- [Data model](#data-model)
- [Healthcheck](#healthcheck)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

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

## Interacting with Claude

Once the MCP server is connected, you can talk to Claude in natural language and it will automatically pick the right tools. Below are example prompts organized by use case.

### Discovering projects

> What projects are indexed?

> Show me all the microservices you know about.

> Is the payment-service already analyzed?

Claude will call `list_projects` and summarize the results.

### Searching within a project

> Find all classes related to "Invoice" in the billing-service.

> Search for anything related to "Kafka" in order-service.

> What classes does payment-service have that deal with refunds?

Claude will call `search_project` with the project name and your keyword.

### Understanding a class

> What does `co.fanki.order.domain.OrderService` do?

> Explain the purpose of PaymentGatewayClient in the payment-service.

> Tell me about the OrderController, specifically in the order-service project.

Claude will call `get_class_context` (with optional `projectName` scoping) and explain the class type, business description, methods, and graph relationships.

### Understanding a method

> What does the `placeOrder` method do in `co.fanki.order.domain.OrderService`?

> Explain the business logic of `processRefund` in the payment-service's RefundService.

> What exceptions can `chargeCustomer` throw?

Claude will call `get_method_context` and return the description, business logic steps, exceptions, HTTP endpoint info, and parameter types.

### Investigating errors from Datadog

> I'm seeing a PaymentDeclinedException in production. Here's the stack trace: [paste stack trace]

> Correlate this Datadog error trace and tell me what went wrong.

> We got an error in `co.fanki.order.domain.OrderService.placeOrder` at line 92. What does that code do and what are its dependencies?

Claude will call `get_stack_trace_context` with the frames and explain each step in the execution path, flag missing context, and include related dependency classes.

### Exploring dependencies

> What does `OrderService` depend on?

> What classes import `PaymentGatewayClient`?

> Show me the full dependency graph around `co.fanki.billing.domain.InvoiceService` in the billing-service.

Claude will call `get_class_dependencies` and show outgoing dependencies, incoming dependents, and method parameter types.

### Getting a project overview

> Give me an overview of the order-service architecture.

> What entry points does payment-service have?

> How many controllers vs services vs repositories does billing-service have?

Claude will call `get_project_overview` and summarize the architecture: entry points, HTTP endpoints, class type breakdown, and project description.

### Integrating with another microservice

> Create a Feign client for the endpoint `getStock` from stock-service.

> I need to call payment-service's `chargeCustomer` endpoint from order-service. Generate the Feign client, the request DTO, and the response DTO.

> Show me the full API surface of notification-service so I can build an integration layer.

> What DTOs does billing-service expect in its `createInvoice` endpoint? Generate them in my project.

Claude will call `get_service_api` and/or `get_method_context` to retrieve the endpoint details (HTTP method, path, parameter types, response), then generate the Feign client interface, DTOs, and any configuration needed to integrate with the target service.

### Combining tools in a single conversation

You can chain multiple questions and Claude will pick the right tools automatically:

> 1. Which projects are indexed?
> 2. Search for "Stock" classes in the stock-service.
> 3. I want to connect to stock-service using the `getStock` method -- what does it do, what parameters does it need, and what endpoint should I call?
> 4. Show me the full API surface of stock-service so I can build a Feign client.

Claude will chain `list_projects` -> `search_project` -> `get_method_context` -> `get_service_api` across the conversation, building up context as it goes.

### Datadog + Domain MCP combined workflow

When both the Datadog MCP and Domain MCP servers are connected:

> Check the last 10 error traces for order-service in the past hour, correlate the logs, and explain what each class in the stack trace does.

Claude will automatically chain Datadog tools (`trace_list_error_traces`, `log_correlate`) with Domain MCP tools (`get_stack_trace_context`, `get_class_context`) to produce a full root cause analysis.

## MCP Tools

The server exposes **8 MCP tools** via stdio transport. All tools return JSON responses.

---

### `list_projects`

List all indexed projects. Use this to check which repositories have been analyzed and are available for Datadog stack trace correlation. Includes project description derived from README.

**Parameters**: none

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Project ID |
| `name` | string | Project name (derived from repository URL) |
| `repositoryUrl` | string | Git repository URL |
| `basePackage` | string | Common base package (e.g., `co.fanki.order`) |
| `description` | string | Project description from README |
| `status` | string | `PENDING`, `ANALYZING`, `ANALYZED`, or `ERROR` |
| `lastAnalyzedAt` | string | ISO-8601 timestamp of last analysis |
| `classCount` | number | Number of indexed classes |
| `endpointCount` | number | Number of HTTP endpoints found |

**Example request**:

```json
{}
```

**Example response**:

```json
[
  {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "order-service",
    "repositoryUrl": "git@github.com:fanki/order-service.git",
    "basePackage": "co.fanki.order",
    "description": "Microservice for order lifecycle management",
    "status": "ANALYZED",
    "lastAnalyzedAt": "2025-06-15T14:30:00Z",
    "classCount": 42,
    "endpointCount": 12
  },
  {
    "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "name": "payment-service",
    "repositoryUrl": "git@github.com:fanki/payment-service.git",
    "basePackage": "co.fanki.payment",
    "description": "Handles payment processing and refunds",
    "status": "ANALYZED",
    "lastAnalyzedAt": "2025-06-14T10:00:00Z",
    "classCount": 28,
    "endpointCount": 8
  }
]
```

---

### `search_project`

Search for classes within a specific project by partial name. Returns matching classes with their type, description, entry point status, and source file. Use this to discover classes in a project when you don't know the exact fully qualified name.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `projectName` | yes | string | The project name as returned by `list_projects` |
| `query` | yes | string | Partial class name or keyword to search for (case-insensitive) |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `found` | boolean | Whether the project was found |
| `projectName` | string | The project name |
| `query` | string | The search query |
| `matches` | array | Matching classes |
| `matches[].className` | string | Fully qualified class name |
| `matches[].classType` | string | Class type (`SERVICE`, `CONTROLLER`, `REPOSITORY`, `DTO`, etc.), null if not enriched |
| `matches[].description` | string | Business description, null if not enriched |
| `matches[].entryPoint` | boolean | Whether the class is an entry point (controller, listener) |
| `matches[].sourceFile` | string | Relative source file path |
| `totalClassesInProject` | number | Total number of classes in the project graph |
| `message` | string | Informational message (set on errors) |

**Example request**:

```json
{
  "projectName": "order-service",
  "query": "Payment"
}
```

**Example response**:

```json
{
  "found": true,
  "projectName": "order-service",
  "query": "Payment",
  "matches": [
    {
      "className": "co.fanki.order.domain.PaymentService",
      "classType": "SERVICE",
      "description": "Orchestrates payment processing for orders",
      "entryPoint": false,
      "sourceFile": "src/main/java/co/fanki/order/domain/PaymentService.java"
    },
    {
      "className": "co.fanki.order.application.PaymentController",
      "classType": "CONTROLLER",
      "description": "REST endpoints for payment operations",
      "entryPoint": true,
      "sourceFile": "src/main/java/co/fanki/order/application/PaymentController.java"
    },
    {
      "className": "co.fanki.order.domain.PaymentResult",
      "classType": "DTO",
      "description": "Result of a payment attempt",
      "entryPoint": false,
      "sourceFile": "src/main/java/co/fanki/order/domain/PaymentResult.java"
    }
  ],
  "totalClassesInProject": 42,
  "message": null
}
```

---

### `get_class_context`

Get business context for a class by its fully qualified name. Returns class type, description, all methods, and project description from README. Use this when Datadog shows an error in a specific class to understand its purpose and behavior.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `className` | yes | string | The fully qualified class name (e.g., `co.fanki.order.OrderService`) |
| `projectName` | no | string | Scope the search to this project (as returned by `list_projects`) |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `found` | boolean | Whether the class was found |
| `className` | string | Fully qualified class name |
| `classType` | string | Class type (`SERVICE`, `CONTROLLER`, `REPOSITORY`, `DTO`, etc.) |
| `description` | string | Business description from Claude enrichment |
| `projectDescription` | string | Project description from README |
| `methods` | array | Methods in this class |
| `methods[].name` | string | Method name |
| `methods[].description` | string | Business description |
| `methods[].businessLogic` | array | Step-by-step business logic |
| `projectUrl` | string | Git repository URL |
| `graphInfo` | object | Graph relationship data (null if no graph) |
| `graphInfo.dependencies` | array | What this class imports (outgoing edges) |
| `graphInfo.dependents` | array | What imports this class (incoming edges) |
| `graphInfo.entryPoint` | boolean | Whether this class is an entry point |
| `knownProjects` | array | Available projects (populated when class not found) |
| `message` | string | Informational message (set when not found) |

**Example request** (global search):

```json
{
  "className": "co.fanki.order.domain.OrderService"
}
```

**Example request** (scoped to a project):

```json
{
  "className": "co.fanki.order.domain.OrderService",
  "projectName": "order-service"
}
```

**Example response**:

```json
{
  "found": true,
  "className": "co.fanki.order.domain.OrderService",
  "classType": "SERVICE",
  "description": "Core domain service for order lifecycle management. Handles order creation, validation, and state transitions.",
  "projectDescription": "Microservice for order lifecycle management",
  "methods": [
    {
      "name": "placeOrder",
      "description": "Creates a new order for a customer after validating stock and payment",
      "businessLogic": [
        "Validate order items against inventory",
        "Calculate total with discounts",
        "Reserve stock",
        "Process payment via PaymentService",
        "Persist order with PENDING status"
      ]
    },
    {
      "name": "cancelOrder",
      "description": "Cancels an existing order and releases reserved stock",
      "businessLogic": [
        "Load order by ID",
        "Verify order is cancellable (not shipped)",
        "Release reserved stock",
        "Initiate refund if paid",
        "Update status to CANCELLED"
      ]
    }
  ],
  "projectUrl": "git@github.com:fanki/order-service.git",
  "graphInfo": {
    "dependencies": [
      "co.fanki.order.domain.OrderRepository",
      "co.fanki.order.domain.PaymentService",
      "co.fanki.order.domain.InventoryClient"
    ],
    "dependents": [
      "co.fanki.order.application.OrderController"
    ],
    "entryPoint": false
  },
  "knownProjects": [],
  "message": null
}
```

---

### `get_method_context`

Get detailed context for a specific method, including business logic, dependencies, exceptions, HTTP endpoint info, and project description from README. Use this when Datadog shows an error in a specific method to understand what it does and why it might fail.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `className` | yes | string | The fully qualified class name |
| `methodName` | yes | string | The method name |
| `projectName` | no | string | Scope the search to this project (as returned by `list_projects`) |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `found` | boolean | Whether the method was found |
| `className` | string | Fully qualified class name |
| `methodName` | string | Method name |
| `httpEndpoint` | string | HTTP endpoint (e.g., `POST /api/orders`), null if not an endpoint |
| `description` | string | Business description |
| `projectDescription` | string | Project description from README |
| `businessLogic` | array | Step-by-step business logic |
| `exceptions` | array | Exceptions this method may throw |
| `sourceFile` | string | Relative source file path |
| `lineNumber` | number | Line number in source file |
| `projectUrl` | string | Git repository URL |
| `parameterTypes` | array | Method parameter types resolved from the project graph |
| `parameterTypes[].position` | number | 0-based parameter position |
| `parameterTypes[].typeName` | string | FQCN of the parameter type |
| `knownProjects` | array | Available projects (populated when not found) |
| `message` | string | Informational message (set when not found) |

**Example request**:

```json
{
  "className": "co.fanki.order.domain.OrderService",
  "methodName": "placeOrder"
}
```

**Example request** (scoped to a project):

```json
{
  "className": "co.fanki.order.domain.OrderService",
  "methodName": "placeOrder",
  "projectName": "order-service"
}
```

**Example response**:

```json
{
  "found": true,
  "className": "co.fanki.order.domain.OrderService",
  "methodName": "placeOrder",
  "httpEndpoint": "POST /api/orders",
  "description": "Creates a new order for a customer after validating stock and payment",
  "projectDescription": "Microservice for order lifecycle management",
  "businessLogic": [
    "Validate order items against inventory",
    "Calculate total with discounts",
    "Reserve stock",
    "Process payment via PaymentService",
    "Persist order with PENDING status"
  ],
  "exceptions": [
    "InsufficientStockException",
    "PaymentDeclinedException"
  ],
  "sourceFile": "src/main/java/co/fanki/order/domain/OrderService.java",
  "lineNumber": 85,
  "projectUrl": "git@github.com:fanki/order-service.git",
  "parameterTypes": [
    {
      "position": 0,
      "typeName": "co.fanki.order.domain.CreateOrderRequest"
    }
  ],
  "knownProjects": [],
  "message": null
}
```

---

### `get_stack_trace_context`

**Primary tool for Datadog error correlation.** Takes a full stack trace (array of className/methodName/lineNumber frames) and returns business context for each frame, plus project description from README. Use this IMMEDIATELY after getting error traces or stack traces from Datadog to understand the execution path and root cause.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `stackTrace` | yes | array | Array of stack trace frames |
| `stackTrace[].className` | yes | string | Fully qualified class name |
| `stackTrace[].methodName` | yes | string | Method name |
| `stackTrace[].lineNumber` | no | integer | Line number in source file |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `executionPath` | array | Ordered entries for each stack frame |
| `executionPath[].order` | number | Position in the execution path |
| `executionPath[].className` | string | Fully qualified class name |
| `executionPath[].methodName` | string | Method name |
| `executionPath[].classType` | string | Class type, null if not found |
| `executionPath[].description` | string | Business description, null if not found |
| `executionPath[].businessLogic` | array | Step-by-step business logic |
| `executionPath[].httpEndpoint` | string | HTTP endpoint, null if not applicable |
| `executionPath[].found` | boolean | Whether business context was resolved |
| `missingContext` | array | Frames that could not be resolved |
| `projectUrl` | string | Git repository URL |
| `projectDescription` | string | Project description from README |
| `relatedDependencies` | array | Graph neighbors of matched classes (depth 1) |

**Example request**:

```json
{
  "stackTrace": [
    {
      "className": "co.fanki.order.application.OrderController",
      "methodName": "createOrder",
      "lineNumber": 45
    },
    {
      "className": "co.fanki.order.domain.OrderService",
      "methodName": "placeOrder",
      "lineNumber": 92
    },
    {
      "className": "co.fanki.order.domain.PaymentService",
      "methodName": "processPayment",
      "lineNumber": 67
    },
    {
      "className": "org.springframework.web.servlet.DispatcherServlet",
      "methodName": "doDispatch",
      "lineNumber": 1067
    }
  ]
}
```

**Example response**:

```json
{
  "executionPath": [
    {
      "order": 1,
      "className": "co.fanki.order.application.OrderController",
      "methodName": "createOrder",
      "classType": "CONTROLLER",
      "description": "Validates input and delegates to OrderService",
      "businessLogic": ["Validate request body", "Delegate to OrderService.placeOrder"],
      "httpEndpoint": "POST /api/orders",
      "found": true
    },
    {
      "order": 2,
      "className": "co.fanki.order.domain.OrderService",
      "methodName": "placeOrder",
      "classType": "SERVICE",
      "description": "Creates a new order after validating stock and payment",
      "businessLogic": ["Validate items", "Calculate total", "Reserve stock", "Process payment", "Persist order"],
      "httpEndpoint": null,
      "found": true
    },
    {
      "order": 3,
      "className": "co.fanki.order.domain.PaymentService",
      "methodName": "processPayment",
      "classType": "SERVICE",
      "description": "Charges the customer via the payment gateway",
      "businessLogic": ["Validate card", "Call payment gateway", "Handle response"],
      "httpEndpoint": null,
      "found": true
    },
    {
      "order": 4,
      "className": "org.springframework.web.servlet.DispatcherServlet",
      "methodName": "doDispatch",
      "classType": null,
      "description": null,
      "businessLogic": [],
      "httpEndpoint": null,
      "found": false
    }
  ],
  "missingContext": [
    {
      "className": "org.springframework.web.servlet.DispatcherServlet",
      "methodName": "doDispatch",
      "lineNumber": 1067
    }
  ],
  "projectUrl": "git@github.com:fanki/order-service.git",
  "projectDescription": "Microservice for order lifecycle management",
  "relatedDependencies": [
    {
      "order": 1,
      "className": "co.fanki.order.domain.OrderRepository",
      "methodName": "save",
      "classType": "REPOSITORY",
      "description": "Persists order entities to the database",
      "businessLogic": ["Insert order into orders table"],
      "httpEndpoint": null,
      "found": true
    }
  ]
}
```

---

### `get_class_dependencies`

Get the dependency graph around a class. Returns what this class imports (dependencies), what imports it (dependents), and method parameter types. Use this to understand how a class connects to the rest of the system.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `className` | yes | string | The fully qualified class name |
| `projectName` | no | string | Scope the search to this project (as returned by `list_projects`) |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `found` | boolean | Whether the class was found |
| `className` | string | Fully qualified class name |
| `entryPoint` | boolean | Whether this class is an entry point |
| `dependencies` | array | Outgoing: what this class imports |
| `dependencies[].className` | string | FQCN of the dependency |
| `dependencies[].classType` | string | Class type, null if not indexed |
| `dependencies[].description` | string | Business description, null if not indexed |
| `dependents` | array | Incoming: what imports this class |
| `dependents[].className` | string | FQCN of the dependent |
| `dependents[].classType` | string | Class type, null if not indexed |
| `dependents[].description` | string | Business description, null if not indexed |
| `methodParameterTypes` | array | Parameter types per method |
| `methodParameterTypes[].methodName` | string | Method name |
| `methodParameterTypes[].parameterTypes` | array | Parameter type summaries |
| `message` | string | Informational message (set when not found) |

**Example request**:

```json
{
  "className": "co.fanki.order.domain.OrderService"
}
```

**Example request** (scoped to a project):

```json
{
  "className": "co.fanki.order.domain.OrderService",
  "projectName": "order-service"
}
```

**Example response**:

```json
{
  "found": true,
  "className": "co.fanki.order.domain.OrderService",
  "entryPoint": false,
  "dependencies": [
    {
      "className": "co.fanki.order.domain.OrderRepository",
      "classType": "REPOSITORY",
      "description": "Persists order entities to the database"
    },
    {
      "className": "co.fanki.order.domain.PaymentService",
      "classType": "SERVICE",
      "description": "Charges the customer via the payment gateway"
    }
  ],
  "dependents": [
    {
      "className": "co.fanki.order.application.OrderController",
      "classType": "CONTROLLER",
      "description": "REST endpoints for order operations"
    }
  ],
  "methodParameterTypes": [
    {
      "methodName": "placeOrder",
      "parameterTypes": [
        {
          "className": "co.fanki.order.domain.CreateOrderRequest",
          "classType": "DTO",
          "description": "Request payload for creating a new order"
        }
      ]
    }
  ],
  "message": null
}
```

---

### `get_project_overview`

Get a structural overview of an indexed project. Returns entry points (controllers, listeners), HTTP endpoints, class type breakdown, and project description. Use this to understand the architecture before drilling into specific classes.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `projectName` | yes | string | The project name as returned by `list_projects` |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `found` | boolean | Whether the project was found |
| `projectName` | string | Project name |
| `repositoryUrl` | string | Git repository URL |
| `description` | string | Project description from README |
| `totalClasses` | number | Total number of indexed classes |
| `totalEntryPoints` | number | Number of entry points in the graph |
| `classTypeBreakdown` | object | Count per class type (e.g., `{"SERVICE": 5, "CONTROLLER": 2}`) |
| `entryPoints` | array | Entry point summaries |
| `entryPoints[].className` | string | FQCN of the entry point |
| `entryPoints[].classType` | string | Class type |
| `entryPoints[].description` | string | Business description |
| `entryPoints[].httpEndpoints` | array | HTTP endpoints (e.g., `["GET /api/users", "POST /api/users"]`) |
| `message` | string | Informational message (set when not found) |

**Example request**:

```json
{
  "projectName": "order-service"
}
```

**Example response**:

```json
{
  "found": true,
  "projectName": "order-service",
  "repositoryUrl": "git@github.com:fanki/order-service.git",
  "description": "Microservice for order lifecycle management",
  "totalClasses": 42,
  "totalEntryPoints": 3,
  "classTypeBreakdown": {
    "CONTROLLER": 3,
    "SERVICE": 8,
    "REPOSITORY": 5,
    "DTO": 12,
    "ENTITY": 6,
    "CONFIGURATION": 2,
    "OTHER": 6
  },
  "entryPoints": [
    {
      "className": "co.fanki.order.application.OrderController",
      "classType": "CONTROLLER",
      "description": "REST endpoints for order operations",
      "httpEndpoints": [
        "POST /api/orders",
        "GET /api/orders/{id}",
        "PUT /api/orders/{id}/cancel"
      ]
    },
    {
      "className": "co.fanki.order.application.PaymentController",
      "classType": "CONTROLLER",
      "description": "REST endpoints for payment operations",
      "httpEndpoints": [
        "POST /api/payments",
        "GET /api/payments/{id}/status"
      ]
    },
    {
      "className": "co.fanki.order.application.OrderEventListener",
      "classType": "CONTROLLER",
      "description": "Kafka listener for order-related events",
      "httpEndpoints": []
    }
  ],
  "message": null
}
```

---

### `get_service_api`

Get the public API surface of an indexed microservice. Returns all HTTP endpoints grouped by controller, with parameter types (DTOs), descriptions, business logic, and exceptions. Use this when you need to integrate with or call another microservice.

**Parameters**:

| Parameter | Required | Type | Description |
|-----------|----------|------|-------------|
| `projectName` | yes | string | The project name as returned by `list_projects` |

**Response fields**:

| Field | Type | Description |
|-------|------|-------------|
| `found` | boolean | Whether the project was found and has graph data |
| `projectName` | string | Project name |
| `repositoryUrl` | string | Git repository URL |
| `description` | string | Project description from README |
| `controllers` | array | Controllers with their HTTP endpoints |
| `controllers[].className` | string | FQCN of the controller |
| `controllers[].description` | string | Business description |
| `controllers[].endpoints` | array | HTTP endpoints |
| `controllers[].endpoints[].methodName` | string | Java method name |
| `controllers[].endpoints[].httpMethod` | string | HTTP verb (`GET`, `POST`, `PUT`, `DELETE`) |
| `controllers[].endpoints[].httpPath` | string | URL path |
| `controllers[].endpoints[].description` | string | Business description |
| `controllers[].endpoints[].businessLogic` | array | Step-by-step business logic |
| `controllers[].endpoints[].exceptions` | array | Exceptions this endpoint may throw |
| `controllers[].endpoints[].parameters` | array | Resolved parameter types |
| `controllers[].endpoints[].parameters[].position` | number | 0-based parameter position |
| `controllers[].endpoints[].parameters[].className` | string | FQCN of the parameter type |
| `controllers[].endpoints[].parameters[].classType` | string | Class type (e.g., `DTO`), null if not indexed |
| `controllers[].endpoints[].parameters[].description` | string | Business description, null if not indexed |
| `message` | string | Informational message (set when not found) |

**Example request**:

```json
{
  "projectName": "order-service"
}
```

**Example response**:

```json
{
  "found": true,
  "projectName": "order-service",
  "repositoryUrl": "git@github.com:fanki/order-service.git",
  "description": "Microservice for order lifecycle management",
  "controllers": [
    {
      "className": "co.fanki.order.application.OrderController",
      "description": "REST endpoints for order operations",
      "endpoints": [
        {
          "methodName": "createOrder",
          "httpMethod": "POST",
          "httpPath": "/api/orders",
          "description": "Creates a new order for a customer",
          "businessLogic": [
            "Validate request body",
            "Delegate to OrderService.placeOrder",
            "Return created order with 201"
          ],
          "exceptions": ["InvalidOrderException"],
          "parameters": [
            {
              "position": 0,
              "className": "co.fanki.order.domain.CreateOrderRequest",
              "classType": "DTO",
              "description": "Request payload for creating a new order"
            }
          ]
        },
        {
          "methodName": "getOrder",
          "httpMethod": "GET",
          "httpPath": "/api/orders/{id}",
          "description": "Retrieves an order by its ID",
          "businessLogic": [
            "Parse order ID from path",
            "Query OrderRepository",
            "Return 404 if not found"
          ],
          "exceptions": ["OrderNotFoundException"],
          "parameters": []
        },
        {
          "methodName": "cancelOrder",
          "httpMethod": "PUT",
          "httpPath": "/api/orders/{id}/cancel",
          "description": "Cancels an existing order and releases reserved stock",
          "businessLogic": [
            "Load order by ID",
            "Verify order is cancellable",
            "Delegate to OrderService.cancelOrder",
            "Return updated order"
          ],
          "exceptions": ["OrderNotFoundException", "OrderNotCancellableException"],
          "parameters": []
        }
      ]
    }
  ],
  "message": null
}
```

---

### REST-Only Endpoints

These operations are available via REST API (port 8080) but not as MCP tools. See the [Indexing Projects](#indexing-projects) section for detailed usage.

## Installation

### Requirements

- Java 21+
- Maven
- PostgreSQL 14+
- SSH key (for private repos)
- Anthropic API key

### Option A: Download the latest release

Download the latest stable JAR from the [Releases page](https://github.com/waabox/domain-mcp-server/releases/latest).

### Option B: Build from source

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

## Indexing Projects

Before the MCP tools can return context about your code, you need to index your repositories through the REST API. The recommended setup is to run the analysis service as a standalone process (or container) with access to the Claude API and SSH keys, and then point the MCP server at the same PostgreSQL database for read-only querying.

### Swagger UI

Once the server is running, the full REST API documentation is available at:

```
http://localhost:8080/swagger-ui/index.html
```

### REST API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/projects/analyze` | POST | Analyze a git repository and index all classes and methods |
| `/api/projects` | GET | List all analyzed projects with metadata |
| `/api/projects/{id}/rebuild-graph` | POST | Rebuild the dependency graph without re-running Claude enrichment |
| `/api/context/class/{className}` | GET | Get class context by fully qualified name |
| `/api/context/class?className=` | GET | Alternative class context endpoint (query param) |
| `/api/context/method?className=&methodName=` | GET | Get method context with business logic and dependencies |
| `/api/context/stack-trace` | POST | Correlate a stack trace with business context |
| `/health` | GET | Health check |

### Analyzing a repository

To index a project, send a POST request to the analysis endpoint:

```bash
curl -X POST http://localhost:8080/api/projects/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "repositoryUrl": "git@github.com:fanki/order-service.git",
    "branch": "main",
    "fixMissed": true
  }'
```

```json
{
  "success": true,
  "projectId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "classesAnalyzed": 42,
  "endpointsFound": 12,
  "message": "Analysis complete"
}
```

The `fixMissed` flag (default `true`) re-analyzes any classes that failed in a previous run.

### Rebuilding the dependency graph

If the parser is updated or you want to refresh the structural graph without re-running Claude enrichment:

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/rebuild-graph
```

```json
{
  "success": true,
  "projectId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "message": "Graph rebuilt successfully"
}
```

### Recommended architecture

The analysis service and the MCP server share the same PostgreSQL database but serve different purposes:

```
                        +-----------------------+
                        |   Analysis Service    |
                        |  (REST API, port 8080)|
                        |  ANTHROPIC_API_KEY    |
                        |  GIT_SSH_KEY_PATH     |
                        +-----------+-----------+
                                    |
                                    | writes
                                    v
                        +-----------------------+
                        |     PostgreSQL        |
                        |   domain_mcp schema   |
                        +-----------+-----------+
                                    |
                                    | reads
                                    v
                        +-----------------------+
                        |   MCP Server (stdio)  |
                        |  Claude Code plugin   |
                        |  No API key needed    |
                        +-----------------------+
```

- **Analysis Service**: runs as a standalone service (or Docker container). Needs `ANTHROPIC_API_KEY` and `GIT_SSH_KEY_PATH` to clone repos and analyze code with Claude. Exposes the REST API on port 8080.
- **MCP Server**: runs as a stdio process launched by Claude Code. Only needs database credentials to read the indexed data. Does not need API keys or SSH access.

This separation means you can run the analysis once (or on a schedule), and the MCP server stays lightweight and fast.

## Claude Code MCP Setup

The server supports **MCP stdio transport** for direct integration with Claude Code. The MCP server only needs database credentials to read indexed data — it does not require an Anthropic API key or SSH keys (see [Recommended architecture](#recommended-architecture)).

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
        "DATABASE_PASSWORD": "postgres"
      }
    }
  }
}
```

Replace `/absolute/path/to/` with the actual path to the project on your machine. Note that only database credentials are needed — the MCP server reads from the same PostgreSQL database populated by the [analysis service](#indexing-projects).

### 4. Verify the connection

Restart Claude Code. The 8 domain-mcp-server tools (`list_projects`, `search_project`, `get_class_context`, `get_method_context`, `get_stack_trace_context`, `get_class_dependencies`, `get_project_overview`, `get_service_api`) should appear in your available tools.

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
