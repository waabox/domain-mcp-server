package co.fanki.domainmcp.analysis.domain.nodejs;

import co.fanki.domainmcp.analysis.domain.ClassType;
import co.fanki.domainmcp.analysis.domain.ProjectGraph;
import co.fanki.domainmcp.analysis.domain.StaticMethodInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // -- extractMethodParameters() --

    @Test
    void whenExtractingParams_givenTypedTsParam_shouldReturnIt(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "user.service.ts", """
                import { UserRepository } from './user.repository';

                export class UserService {
                    findUser(repo: UserRepository) {
                        return repo.find();
                    }
                }
                """);

        writeSourceFile(sourceRoot, "services", "user.repository.ts", """
                export class UserRepository {
                    find() { return null; }
                }
                """);

        final Path file = sourceRoot
                .resolve("services/user.service.ts");
        final Set<String> known = Set.of(
                "services.user.service",
                "services.user.repository");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("findUser"));
        assertEquals(List.of("services.user.repository"),
                result.get("findUser"));
    }

    @Test
    void whenExtractingParams_givenMultipleTypedParams_shouldReturnAll(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "order.service.ts", """
                import { OrderRepo } from './order.repo';
                import { Customer } from './customer';

                export class OrderService {
                    placeOrder(repo: OrderRepo, customer: Customer, note: string) {
                    }
                }
                """);

        writeSourceFile(sourceRoot, "services", "order.repo.ts", """
                export class OrderRepo {}
                """);

        writeSourceFile(sourceRoot, "services", "customer.ts", """
                export class Customer {}
                """);

        final Path file = sourceRoot
                .resolve("services/order.service.ts");
        final Set<String> known = Set.of(
                "services.order.service",
                "services.order.repo",
                "services.customer");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("placeOrder"));
        final List<String> params = result.get("placeOrder");
        assertEquals(2, params.size());
        assertTrue(params.contains("services.order.repo"));
        assertTrue(params.contains("services.customer"));
    }

    @Test
    void whenExtractingParams_givenNoTypeAnnotations_shouldReturnEmpty(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "lib", "processor.js", """
                module.exports = {
                    process(data, count) {
                        return data;
                    }
                };
                """);

        final Path file = sourceRoot.resolve("lib/processor.js");
        final Set<String> known = Set.of("lib.processor");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.isEmpty());
    }

    @Test
    void whenExtractingParams_givenOnlyPrimitiveTypes_shouldReturnEmpty(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "calc.ts", """
                export class Calculator {
                    add(a: number, b: number): number {
                        return a + b;
                    }
                }
                """);

        final Path file = sourceRoot.resolve("services/calc.ts");
        final Set<String> known = Set.of("services.calc");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.isEmpty());
    }

    @Test
    void whenExtractingParams_givenAsyncFunction_shouldExtractParams(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);

        writeSourceFile(sourceRoot, "services", "api.service.ts", """
                import { HttpClient } from './http.client';

                export class ApiService {
                    async fetchData(client: HttpClient) {
                        return client.get('/data');
                    }
                }
                """);

        writeSourceFile(sourceRoot, "services", "http.client.ts", """
                export class HttpClient {
                    get(url: string) { return null; }
                }
                """);

        final Path file = sourceRoot
                .resolve("services/api.service.ts");
        final Set<String> known = Set.of(
                "services.api.service",
                "services.http.client");

        final Map<String, List<String>> result =
                parser.extractMethodParameters(file, sourceRoot, known);

        assertTrue(result.containsKey("fetchData"));
        assertEquals(List.of("services.http.client"),
                result.get("fetchData"));
    }

    // -- inferClassType() --

    @Test
    void whenInferringClassType_givenNestJsController_shouldReturnController(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "controllers",
                "user.controller.ts", """
                import { Controller, Get } from '@nestjs/common';

                @Controller('/users')
                export class UserController {
                    @Get()
                    findAll() { return []; }
                }
                """);

        assertEquals(ClassType.CONTROLLER, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenNestJsInjectable_shouldReturnService(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "services",
                "user.service.ts", """
                import { Injectable } from '@nestjs/common';

                @Injectable()
                export class UserService {
                    findAll() { return []; }
                }
                """);

        assertEquals(ClassType.SERVICE, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenExpressRoutes_shouldReturnController(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "routes",
                "users.ts", """
                import { Router } from 'express';
                const router = Router();
                router.get('/users', (req, res) => res.json([]));
                export default router;
                """);

        assertEquals(ClassType.CONTROLLER, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenControllerFilename_shouldReturnController(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "order.controller.ts", """
                export class OrderController {
                    create() { return {}; }
                }
                """);

        assertEquals(ClassType.CONTROLLER, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenServiceFilename_shouldReturnService(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "order.service.ts", """
                export class OrderService {
                    findAll() { return []; }
                }
                """);

        assertEquals(ClassType.SERVICE, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenRepositoryFilename_shouldReturnRepository(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "order.repository.ts", """
                export class OrderRepository {
                    findAll() { return []; }
                }
                """);

        assertEquals(ClassType.REPOSITORY, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenEntityFilename_shouldReturnEntity(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "order.entity.ts", """
                export class Order {
                    id: string;
                    total: number;
                }
                """);

        assertEquals(ClassType.ENTITY, parser.inferClassType(file));
    }

    @Test
    void whenInferringClassType_givenPlainFile_shouldReturnOther(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "utils.ts", """
                export function helper() {
                    return 42;
                }
                """);

        assertEquals(ClassType.OTHER, parser.inferClassType(file));
    }

    // -- extractMethods() --

    @Test
    void whenExtractingMethods_givenSimpleFunctions_shouldReturnWithLineNumbers(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "service.ts", """
                export class UserService {
                    findById(id: string) {
                        return null;
                    }

                    createUser(name: string) {
                        return { name };
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(2, methods.size());
        assertEquals("findById", methods.get(0).methodName());
        assertEquals(2, methods.get(0).lineNumber());
        assertEquals("createUser", methods.get(1).methodName());
        assertEquals(6, methods.get(1).lineNumber());
    }

    @Test
    void whenExtractingMethods_givenNestJsDecorators_shouldExtractHttpInfo(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "user.controller.ts", """
                import { Controller, Get, Post } from '@nestjs/common';

                @Controller('/users')
                export class UserController {

                    @Get('/all')
                    findAll() { return []; }

                    @Post('/create')
                    create(body: any) { return body; }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(2, methods.size());

        assertEquals("findAll", methods.get(0).methodName());
        assertEquals("GET", methods.get(0).httpMethod());
        assertEquals("/all", methods.get(0).httpPath());

        assertEquals("create", methods.get(1).methodName());
        assertEquals("POST", methods.get(1).httpMethod());
        assertEquals("/create", methods.get(1).httpPath());
    }

    @Test
    void whenExtractingMethods_givenAsyncFunction_shouldExtractIt(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "api.service.ts", """
                export class ApiService {
                    async fetchData(url: string) {
                        return fetch(url);
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(1, methods.size());
        assertEquals("fetchData", methods.get(0).methodName());
        assertNull(methods.get(0).httpMethod());
        assertTrue(methods.get(0).exceptions().isEmpty());
    }

    @Test
    void whenExtractingMethods_givenKeywords_shouldExcludeThem(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "processor.ts", """
                export class Processor {
                    process(data: string) {
                        if (data) {
                            for (const item of []) {
                                while (true) {
                                    break;
                                }
                            }
                        }
                    }
                }
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertEquals(1, methods.size());
        assertEquals("process", methods.get(0).methodName());
    }

    @Test
    void whenExtractingMethods_givenNoMethods_shouldReturnEmpty(
            @TempDir final Path projectRoot) throws IOException {

        final Path sourceRoot = createSourceRoot(projectRoot);
        final Path file = writeSourceFile(sourceRoot, "",
                "constants.ts", """
                export const API_URL = 'https://api.example.com';
                export const MAX_RETRIES = 3;
                """);

        final List<StaticMethodInfo> methods = parser.extractMethods(file);

        assertTrue(methods.isEmpty());
    }

    // -- Helper methods --

    private Path createSourceRoot(final Path projectRoot) throws IOException {
        final Path sourceRoot = projectRoot.resolve("src");
        Files.createDirectories(sourceRoot);
        return sourceRoot;
    }

    private Path writeSourceFile(final Path sourceRoot,
            final String subPath, final String fileName,
            final String content) throws IOException {

        final Path dir = subPath.isEmpty()
                ? sourceRoot
                : sourceRoot.resolve(subPath);
        Files.createDirectories(dir);
        final Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

}
