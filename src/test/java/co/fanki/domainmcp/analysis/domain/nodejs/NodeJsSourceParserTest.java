package co.fanki.domainmcp.analysis.domain.nodejs;

import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeJsSourceParser}.
 *
 * <p>Tests file discovery, identifier extraction, dependency resolution,
 * entry point detection, and full graph building using temporary
 * directories with sample TypeScript/JavaScript source files.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class NodeJsSourceParserTest {

    private final NodeJsSourceParser parser = new NodeJsSourceParser();

    // -- language() --

    @Test
    void whenGettingLanguage_shouldReturnTypescript() {
        assertEquals("typescript", parser.language());
    }

    // -- parse(): full graph building --

    @Test
    void whenParsing_givenProjectWithMultipleFiles_shouldBuildCorrectGraph(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "controllers",
                "user.controller.ts", """
                import { UserService } from '../services/user.service';

                @Controller('/users')
                export class UserController {
                    constructor(private userService: UserService) {}
                }
                """);

        writeSourceFile(sourceRoot, "services",
                "user.service.ts", """
                import { UserRepository } from '../repositories/user.repository';

                export class UserService {
                    constructor(private userRepo: UserRepository) {}
                }
                """);

        writeSourceFile(sourceRoot, "repositories",
                "user.repository.ts", """
                export class UserRepository {
                    findById(id: string) { return null; }
                }
                """);

        writeSourceFile(sourceRoot, "", "main.ts", """
                import { NestFactory } from '@nestjs/core';
                import { AppModule } from './app.module';

                async function bootstrap() {
                    const app = await NestFactory.create(AppModule);
                    await app.listen(3000);
                }
                bootstrap();
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(4, graph.nodeCount());
        assertTrue(graph.contains("controllers.user.controller"));
        assertTrue(graph.contains("services.user.service"));
        assertTrue(graph.contains("repositories.user.repository"));
        assertTrue(graph.contains("main"));
    }

    // -- File discovery --

    @Test
    void whenParsing_givenTsAndJsFiles_shouldDiscoverAll(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "app.ts", "export const a = 1;");
        writeSourceFile(sourceRoot, "", "utils.js", "module.exports = {};");
        writeSourceFile(sourceRoot, "", "Component.tsx",
                "export default () => <div/>;");
        writeSourceFile(sourceRoot, "", "Legacy.jsx",
                "module.exports = () => <div/>;");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(4, graph.nodeCount());
        assertTrue(graph.contains("app"));
        assertTrue(graph.contains("utils"));
        assertTrue(graph.contains("Component"));
        assertTrue(graph.contains("Legacy"));
    }

    @Test
    void whenParsing_givenTestFiles_shouldExcludeThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "service.ts",
                "export class Service {}");
        writeSourceFile(sourceRoot, "", "service.spec.ts",
                "describe('Service', () => {});");
        writeSourceFile(sourceRoot, "", "service.test.ts",
                "test('service', () => {});");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.nodeCount());
        assertTrue(graph.contains("service"));
    }

    @Test
    void whenParsing_givenDeclarationFiles_shouldExcludeThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "service.ts",
                "export class Service {}");
        writeSourceFile(sourceRoot, "", "types.d.ts",
                "declare module 'foo';");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.nodeCount());
        assertTrue(graph.contains("service"));
    }

    @Test
    void whenParsing_givenExcludedDirectories_shouldExcludeThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "app.ts",
                "export const app = true;");
        writeSourceFile(sourceRoot, "node_modules/lodash", "index.ts",
                "export default {};");
        writeSourceFile(sourceRoot, "dist", "bundle.js",
                "var x = 1;");
        writeSourceFile(sourceRoot, "__tests__", "app.test.ts",
                "test('app', () => {});");
        writeSourceFile(sourceRoot, "__mocks__", "mock.ts",
                "export default {};");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.nodeCount());
        assertTrue(graph.contains("app"));
    }

    @Test
    void whenParsing_givenEmptySourceDirectory_shouldReturnEmptyGraph(
            @TempDir final Path projectRoot) throws IOException {

        createSourceRoot(projectRoot);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.entryPointCount());
        assertTrue(graph.identifiers().isEmpty());
    }

    @Test
    void whenParsing_givenNoSourceRootDirectory_shouldReturnEmptyGraph(
            @TempDir final Path projectRoot) throws IOException {

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenNullProjectRoot_shouldThrowException() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(null));
    }

    // -- Identifier extraction --

    @Test
    void whenParsing_givenNestedPath_shouldProduceDottedIdentifier(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services/user", "user.service.ts",
                "export class UserService {}");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertTrue(graph.contains("services.user.user.service"));
    }

    @Test
    void whenParsing_givenRootFile_shouldProduceSimpleIdentifier(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "config.ts",
                "export const config = {};");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertTrue(graph.contains("config"));
    }

    @Test
    void whenParsing_givenTsxFile_shouldStripExtension(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "components", "Button.tsx",
                "export const Button = () => <button/>;");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertTrue(graph.contains("components.Button"));
    }

    @Test
    void whenParsing_givenSourceFilePaths_shouldStoreRelativePaths(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "auth.service.ts",
                "export class AuthService {}");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals("src/services/auth.service.ts",
                graph.sourceFile("services.auth.service"));
    }

    // -- Dependency resolution: ES6 imports --

    @Test
    void whenParsing_givenEs6RelativeImport_shouldResolveDependency(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "order.service.ts", """
                import { OrderRepository } from './order.repository';

                export class OrderService {
                    constructor(private repo: OrderRepository) {}
                }
                """);

        writeSourceFile(sourceRoot, "services", "order.repository.ts", """
                export class OrderRepository {}
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> neighbors =
                graph.resolve("services.order.service");
        assertTrue(neighbors.contains("services.order.repository"));
    }

    @Test
    void whenParsing_givenEs6ParentDirImport_shouldResolveDependency(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "controllers",
                "order.controller.ts", """
                import { OrderService } from '../services/order.service';

                export class OrderController {}
                """);

        writeSourceFile(sourceRoot, "services", "order.service.ts", """
                export class OrderService {}
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> neighbors =
                graph.resolve("controllers.order.controller");
        assertTrue(neighbors.contains("services.order.service"));
    }

    @Test
    void whenParsing_givenEs6IndexImport_shouldResolveDependency(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "utils", "index.ts", """
                export function helper() {}
                """);

        writeSourceFile(sourceRoot, "services", "my.service.ts", """
                import { helper } from '../utils';

                export class MyService {
                    run() { helper(); }
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> neighbors =
                graph.resolve("services.my.service");
        assertTrue(neighbors.contains("utils.index"));
    }

    @Test
    void whenParsing_givenExternalImports_shouldFilterThemOut(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "api.service.ts", """
                import axios from 'axios';
                import { Injectable } from '@nestjs/common';
                import { Config } from './config';

                export class ApiService {}
                """);

        writeSourceFile(sourceRoot, "services", "config.ts", """
                export const Config = {};
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> neighbors =
                graph.resolve("services.api.service");
        assertEquals(1, neighbors.size());
        assertTrue(neighbors.contains("services.config"));
    }

    // -- Dependency resolution: CommonJS require --

    @Test
    void whenParsing_givenRequireRelativeImport_shouldResolveDependency(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "lib", "processor.js", """
                const helper = require('./helper');

                module.exports = { process: () => helper.run() };
                """);

        writeSourceFile(sourceRoot, "lib", "helper.js", """
                module.exports = { run: () => {} };
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        final Set<String> neighbors =
                graph.resolve("lib.processor");
        assertTrue(neighbors.contains("lib.helper"));
    }

    // -- Entry point detection --

    @Test
    void whenParsing_givenMainTsFile_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "main.ts", """
                async function bootstrap() {}
                bootstrap();
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenIndexJsFile_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "index.js", """
                const app = require('express')();
                app.listen(3000);
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenAppTsFile_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "app.ts", """
                export class App {}
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenServerJsFile_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "server.js", """
                const http = require('http');
                http.createServer().listen(3000);
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenNestJsController_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "controllers",
                "user.controller.ts", """
                import { Controller, Get } from '@nestjs/common';

                @Controller('/users')
                export class UserController {
                    @Get()
                    findAll() { return []; }
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenExpressRoutes_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "routes", "users.ts", """
                import { Router } from 'express';
                const router = Router();

                router.get('/users', (req, res) => {
                    res.json([]);
                });

                router.post('/users', (req, res) => {
                    res.status(201).json({});
                });

                export default router;
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenAppUseRoute_shouldDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "middleware", "logger.ts", """
                const express = require('express');
                const app = express();

                app.use('/api', (req, res, next) => {
                    console.log('Request:', req.method, req.url);
                    next();
                });
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.entryPointCount());
    }

    @Test
    void whenParsing_givenPlainService_shouldNotDetectEntryPoint(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "plain.service.ts", """
                export class PlainService {
                    doSomething() { return 42; }
                }
                """);

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(0, graph.entryPointCount());
    }

    // -- Analysis order with BFS from entry points --

    @Test
    void whenGettingAnalysisOrder_givenEntryPointWithDeps_shouldBfsOrder(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "controllers",
                "order.controller.ts", """
                import { OrderService } from '../services/order.service';

                @Controller('/orders')
                export class OrderController {}
                """);

        writeSourceFile(sourceRoot, "services",
                "order.service.ts", """
                import { OrderRepo } from '../repositories/order.repo';

                export class OrderService {}
                """);

        writeSourceFile(sourceRoot, "repositories",
                "order.repo.ts", """
                export class OrderRepo {}
                """);

        final ProjectGraph graph = parser.parse(projectRoot);
        final List<String> order = graph.analysisOrder();

        assertEquals(3, order.size());
        assertEquals("controllers.order.controller", order.get(0));

        final int serviceIdx = order.indexOf("services.order.service");
        final int repoIdx = order.indexOf("repositories.order.repo");
        assertTrue(serviceIdx < repoIdx,
                "Service should come before Repository in BFS order");
    }

    // -- Non-source files ignored --

    @Test
    void whenParsing_givenNonSourceFiles_shouldIgnoreThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "", "valid.ts",
                "export const x = 1;");

        final Path jsonFile = sourceRoot.resolve("config.json");
        Files.writeString(jsonFile, "{}");

        final Path mdFile = sourceRoot.resolve("README.md");
        Files.writeString(mdFile, "# Hello");

        final ProjectGraph graph = parser.parse(projectRoot);

        assertEquals(1, graph.nodeCount());
        assertTrue(graph.contains("valid"));
    }

    // -- Identifier produces correct simpleName/packageName split --

    @Test
    void whenParsing_givenNestedIdentifier_shouldWorkWithSourceClass(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services/user", "user.service.ts",
                "export class UserService {}");

        final ProjectGraph graph = parser.parse(projectRoot);

        final String identifier = "services.user.user.service";
        assertTrue(graph.contains(identifier));

        // Verify the identifier splits correctly on last dot
        final int lastDot = identifier.lastIndexOf('.');
        final String simpleName = identifier.substring(lastDot + 1);
        final String packageName = identifier.substring(0, lastDot);

        assertEquals("service", simpleName);
        assertEquals("services.user.user", packageName);
    }

    // -- Helper methods --

    private Path createSourceRoot(final Path projectRoot) throws IOException {
        final Path sourceRoot = projectRoot.resolve("src");
        Files.createDirectories(sourceRoot);
        return sourceRoot;
    }

    private void writeSourceFile(final Path sourceRoot,
            final String subPath, final String fileName,
            final String content) throws IOException {

        final Path dir = subPath.isEmpty()
                ? sourceRoot
                : sourceRoot.resolve(subPath);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content);
    }

}
