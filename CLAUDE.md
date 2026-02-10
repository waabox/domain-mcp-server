# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Domain MCP Server - a Model Context Protocol server that exposes domain-specific tools and resources to AI assistants.

## Development Commands

```bash
# Install dependencies
pnpm install

# Development with hot reload
pnpm dev

# Build for production
pnpm build

# Run production build
pnpm start

# Run tests
pnpm test

# Run a single test file
pnpm test -- <path-to-test-file>

# Lint code
pnpm lint

# Format code
pnpm format
```

## Architecture

This MCP server follows the Model Context Protocol specification to expose domain tools and resources:

- **Tools**: Executable functions that AI assistants can invoke
- **Resources**: Data sources that AI assistants can read
- **Prompts**: Pre-defined prompt templates for common operations

### Key Directories

```
src/
├── tools/           # MCP tool implementations
├── resources/       # MCP resource providers
├── prompts/         # MCP prompt templates
├── lib/             # Shared utilities and helpers
└── index.ts         # Server entry point
```

## MCP Server Guidelines

- Each tool should have a clear, focused purpose
- Tool parameters must be well-documented with descriptions
- Use Zod schemas for input validation
- Return structured JSON responses from tools
- Handle errors gracefully and return meaningful error messages
