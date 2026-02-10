# Domain MCP Server - Container-Based Analysis

## New Approach
Use TestContainers to spin up Docker containers with Claude Code installed, clone repos inside, and run analysis.

## Architecture

```
┌──────────────────────────────────────────────────┐
│              Domain MCP Server                    │
│         (Spring Boot + PostgreSQL)                │
├──────────────────────────────────────────────────┤
│  ContainerAnalysisService                         │
│  - Detects project language                       │
│  - Spins up appropriate container                 │
│  - Executes Claude Code analysis                  │
│  - Captures and stores results                    │
└────────────────────┬─────────────────────────────┘
                     │
     ┌───────────────┼───────────────┐
     ▼               ▼               ▼
┌──────────┐   ┌──────────┐   ┌──────────┐
│  Java    │   │  Node    │   │  Python  │
│Container │   │Container │   │Container │
│+ Claude  │   │+ Claude  │   │+ Claude  │
│  Code    │   │  Code    │   │  Code    │
└──────────┘   └──────────┘   └──────────┘
```

## Tasks

### Phase 1: Simplify Existing Code
- [x] Remove complex MCP tool implementations
- [x] Remove Claude API direct integration
- [ ] Keep: Project, Analysis domain models
- [ ] Keep: PostgreSQL/JDBI setup
- [ ] Keep: Health endpoints

### Phase 2: Container Infrastructure
- [ ] Create base Dockerfile for analysis containers
- [ ] Create ContainerImage enum (JAVA, NODE, PYTHON, GO, etc.)
- [ ] Create LanguageDetector service
- [ ] Create ContainerManager using TestContainers

### Phase 3: Analysis Execution
- [ ] Create ClaudeCodeExecutor - runs commands in container
- [ ] Create AnalysisPromptBuilder - builds prompts for Claude Code
- [ ] Create OutputParser - parses Claude Code output
- [ ] Create ContainerAnalysisService - orchestrates everything

### Phase 4: API Layer
- [ ] REST endpoint: POST /api/projects/analyze
- [ ] REST endpoint: GET /api/projects/{id}/analysis
- [ ] REST endpoint: GET /api/projects/{id}/endpoints
- [ ] MCP tools that call REST endpoints

## Container Images Needed

Each image needs:
- Base language runtime
- Git
- Claude Code CLI (npm install -g @anthropic-ai/claude-code)
- Node.js (for Claude Code)

```dockerfile
# Example: Java analysis container
FROM eclipse-temurin:21-jdk

# Install Node.js for Claude Code
RUN apt-get update && apt-get install -y curl git
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
RUN apt-get install -y nodejs

# Install Claude Code
RUN npm install -g @anthropic-ai/claude-code

WORKDIR /workspace
```

## Analysis Flow

1. Receive repository URL
2. Quick clone to detect language (or use GitHub API)
3. Select appropriate container image
4. Start container with:
   - CLAUDE_API_KEY env var
   - Volume mount for output
5. Inside container:
   - git clone <repo>
   - cd <repo>
   - claude --init (or run with prompt directly)
   - claude "Analyze this codebase and describe all API endpoints..."
6. Capture stdout/output file
7. Parse JSON response
8. Store in PostgreSQL
9. Stop and remove container
