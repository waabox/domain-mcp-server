package co.fanki.domainmcp.analysis.domain.nodejs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GraalJsAnalyzerEngine}.
 *
 * <p>Tests that the engine correctly loads the Babel AST analyzer
 * bundle from the classpath and produces accurate per-file analysis
 * results for various TypeScript/JavaScript patterns.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GraalJsAnalyzerEngineTest {

    private GraalJsAnalyzerEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        engine = new GraalJsAnalyzerEngine();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // -- Engine loading --

    @Test
    void whenCreating_shouldLoadBundleSuccessfully() {
        assertNotNull(engine);
    }

    // -- Framework detection --

    @Test
    void whenDetecting_givenNestJsProject_shouldDetectFramework()
            throws IOException {

        final String packageJson = """
                { "dependencies": { "@nestjs/core": "10.0.0" } }""";

        final FrameworkInfo framework = engine.detectFramework(packageJson);

        assertEquals("nestjs", framework.name());
        assertEquals("src", framework.sourceRoot());
    }

    @Test
    void whenDetecting_givenNextJsProject_shouldDetectFramework()
            throws IOException {

        final String packageJson = """
                { "dependencies": { "next": "14.0.0" } }""";

        final FrameworkInfo framework = engine.detectFramework(packageJson);

        assertEquals("nextjs", framework.name());
    }

    @Test
    void whenDetecting_givenExpressProject_shouldDetectFramework()
            throws IOException {

        final String packageJson = """
                { "dependencies": { "express": "4.18.0" } }""";

        final FrameworkInfo framework = engine.detectFramework(packageJson);

        assertEquals("express", framework.name());
    }

    @Test
    void whenDetecting_givenVueProject_shouldDetectFramework()
            throws IOException {

        final String packageJson = """
                { "dependencies": { "vue": "3.0.0" } }""";

        final FrameworkInfo framework = engine.detectFramework(packageJson);

        assertEquals("vue", framework.name());
    }

    @Test
    void whenDetecting_givenAngularProject_shouldDetectFramework()
            throws IOException {

        final String packageJson = """
                { "dependencies": { "@angular/core": "17.0.0" } }""";

        final FrameworkInfo framework = engine.detectFramework(packageJson);

        assertEquals("angular", framework.name());
    }

    // -- Per-file analysis: method extraction --

    @Test
    void whenAnalyzingFile_givenClassWithMethods_shouldExtractAllMethods()
            throws IOException {

        final String content = """
                export class UserService {
                    findById(id: string) {
                        return null;
                    }

                    createUser(name: string) {
                        return { name };
                    }
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/services/user.service.ts", "unknown");

        assertEquals(2, result.methods().size());
        assertEquals("findById", result.methods().get(0).name());
        assertEquals("createUser", result.methods().get(1).name());
    }

    // -- NestJS decorators --

    @Test
    void whenAnalyzingFile_givenNestJsController_shouldExtractHttpInfo()
            throws IOException {

        final String content = """
                import { Controller, Get, Post } from '@nestjs/common';

                @Controller('/users')
                export class UserController {
                    @Get('/all')
                    findAll() { return []; }

                    @Post('/create')
                    create(body: any) { return body; }
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/user.controller.ts", "nestjs");

        assertEquals("CONTROLLER", result.classType());
        assertTrue(result.entryPoint());

        assertEquals(2, result.methods().size());
        assertEquals("GET", result.methods().get(0).httpMethod());
        assertEquals("/all", result.methods().get(0).httpPath());
        assertEquals("POST", result.methods().get(1).httpMethod());
        assertEquals("/create", result.methods().get(1).httpPath());
    }

    // -- Class type inference --

    @Test
    void whenAnalyzingFile_givenInjectableService_shouldInferServiceType()
            throws IOException {

        final String content = """
                import { Injectable } from '@nestjs/common';

                @Injectable()
                export class UserService {
                    findAll() { return []; }
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/user.service.ts", "nestjs");

        assertEquals("SERVICE", result.classType());
    }

    @Test
    void whenAnalyzingFile_givenServiceFilename_shouldInferServiceType()
            throws IOException {

        final String content = """
                export class OrderService {
                    findAll() { return []; }
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/order.service.ts", "unknown");

        assertEquals("SERVICE", result.classType());
    }

    // -- Import extraction --

    @Test
    void whenAnalyzingFile_givenRelativeImports_shouldExtractRawImports()
            throws IOException {

        final String content = """
                import { UserService } from '../services/user.service';
                import { Config } from './config';
                import axios from 'axios';

                export class UserController {
                    constructor(private userService: UserService) {}
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/controllers/user.controller.ts", "unknown");

        assertEquals(3, result.rawImports().size());

        final RawImport relativeImport = result.rawImports().stream()
                .filter(i -> i.source().equals("../services/user.service"))
                .findFirst().orElseThrow();
        assertEquals("UserService", relativeImport.importedName());
        assertEquals("UserService", relativeImport.localName());

        final RawImport configImport = result.rawImports().stream()
                .filter(i -> i.source().equals("./config"))
                .findFirst().orElseThrow();
        assertEquals("Config", configImport.importedName());

        final RawImport externalImport = result.rawImports().stream()
                .filter(i -> i.source().equals("axios"))
                .findFirst().orElseThrow();
        assertEquals("default", externalImport.importedName());
        assertEquals("axios", externalImport.localName());
    }

    // -- Parameter type extraction --

    @Test
    void whenAnalyzingFile_givenTypedParams_shouldExtractParameterTypes()
            throws IOException {

        final String content = """
                import { UserRepository } from './user.repository';

                export class UserService {
                    findUser(repo: UserRepository) {
                        return repo.find();
                    }
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/services/user.service.ts", "unknown");

        assertEquals(1, result.methods().size());
        assertEquals("findUser", result.methods().get(0).name());
        assertTrue(result.methods().get(0).parameterTypes()
                .contains("UserRepository"));
    }

    // -- Entry point detection --

    @Test
    void whenAnalyzingFile_givenMainFile_shouldDetectEntryPoint()
            throws IOException {

        final String content = """
                async function bootstrap() {}
                bootstrap();
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/main.ts", "unknown");

        assertTrue(result.entryPoint());
    }

    @Test
    void whenAnalyzingFile_givenExpressRoutes_shouldDetectEntryPoint()
            throws IOException {

        final String content = """
                import { Router } from 'express';
                const router = Router();
                router.get('/users', (req, res) => res.json([]));
                export default router;
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/routes/users.ts", "unknown");

        assertTrue(result.entryPoint());
        assertEquals("CONTROLLER", result.classType());
    }

    // -- Top-level arrow function variables --

    @Test
    void whenAnalyzingFile_givenTopLevelArrowFunction_shouldExtractMethod()
            throws IOException {

        final String content = """
                import { NextApiRequest, NextApiResponse } from 'next';

                const eventHandler = async (
                    req: NextApiRequest,
                    res: NextApiResponse
                ) => {
                    res.json({ ok: true });
                };

                export default eventHandler;
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "pages/api/event/check/index.ts", "nextjs");

        assertFalse(result.methods().isEmpty());
        final MethodAnalysisResult method = result.methods().stream()
                .filter(m -> m.name().equals("eventHandler"))
                .findFirst().orElseThrow();
        assertEquals("eventHandler", method.name());
        assertTrue(method.parameterTypes().contains("NextApiRequest"));
        assertTrue(method.parameterTypes().contains("NextApiResponse"));
    }

    @Test
    void whenAnalyzingFile_givenMultipleTopLevelArrowFunctions_shouldExtractAll()
            throws IOException {

        final String content = """
                const handler = async (req: Request) => {
                    return Response.json({});
                };

                const helper = (data: string) => {
                    return data.toUpperCase();
                };

                export default handler;
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/api/handler.ts", "unknown");

        assertEquals(2, result.methods().size());
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("handler")));
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("helper")));
    }

    // -- Object property functions --

    @Test
    void whenAnalyzingFile_givenObjectWithArrowFunctions_shouldExtractMethods()
            throws IOException {

        final String content = """
                export const validations = {
                    email: (value: string) => {
                        return value.includes('@');
                    },
                    password: (value: string) => {
                        return value.length >= 8;
                    },
                };
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/auth/validations.ts", "unknown");

        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("email")));
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("password")));
    }

    @Test
    void whenAnalyzingFile_givenObjectShorthandMethods_shouldExtractMethods()
            throws IOException {

        final String content = """
                const api = {
                    fetchUsers() {
                        return fetch('/users');
                    },
                    createUser(data: UserDto) {
                        return fetch('/users', { method: 'POST' });
                    },
                };
                export default api;
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/lib/api.ts", "unknown");

        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("fetchUsers")));
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("createUser")));
    }

    // -- Nested arrow functions inside components --

    @Test
    void whenAnalyzingFile_givenNestedArrowFunctions_shouldExtractThem()
            throws IOException {

        final String content = """
                export default function FormEditShape() {
                    const isSectionSeletable = (section: Section) => {
                        return section.type === 'selectable';
                    };

                    const getOptionsByFieldName = (fieldName: string) => {
                        return options[fieldName] || [];
                    };

                    return null;
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/components/FormEditShape/index.tsx", "unknown");

        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("FormEditShape")));
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("isSectionSeletable")));
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("getOptionsByFieldName")));
    }

    // -- useCallback / useMemo wrapped functions --

    @Test
    void whenAnalyzingFile_givenUseCallbackWrapped_shouldExtractMethod()
            throws IOException {

        final String content = """
                import { useCallback } from 'react';

                export default function LoginContainer() {
                    const handleLogin = useCallback(async (values: LoginValues) => {
                        await loginApi(values);
                    }, [loginApi]);

                    const handleForgotPassword = useCallback(() => {
                        navigate('/forgot-password');
                    }, [navigate]);

                    return null;
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/components/LoginContainer.tsx", "unknown");

        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("handleLogin")));
        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("handleForgotPassword")));
    }

    @Test
    void whenAnalyzingFile_givenUseMemoWrapped_shouldExtractMethod()
            throws IOException {

        final String content = """
                import { useMemo } from 'react';

                export default function Component() {
                    const computeTotal = useMemo(() => {
                        return items.reduce((sum, i) => sum + i.price, 0);
                    }, [items]);

                    return null;
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/components/Component.tsx", "unknown");

        assertTrue(result.methods().stream()
                .anyMatch(m -> m.name().equals("computeTotal")));
    }

    // -- Error handling --

    @Test
    void whenAnalyzingFile_givenInvalidSyntax_shouldNotCrash()
            throws IOException {

        final String content = "this is not {{ valid syntax !!!";

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/broken.ts", "unknown");

        assertNotNull(result);
        assertTrue(result.methods().isEmpty());
    }

    // -- Multiple files reusing same engine --

    @Test
    void whenAnalyzingMultipleFiles_shouldReuseEngine()
            throws IOException {

        final String serviceContent = """
                export class UserService {
                    findAll() { return []; }
                }
                """;

        final String controllerContent = """
                export class UserController {
                    index() { return []; }
                }
                """;

        final FileAnalysisResult serviceResult = engine.analyzeFile(
                serviceContent, "src/user.service.ts", "unknown");

        final FileAnalysisResult controllerResult = engine.analyzeFile(
                controllerContent, "src/user.controller.ts", "unknown");

        assertEquals("SERVICE", serviceResult.classType());
        assertEquals(1, serviceResult.methods().size());

        assertEquals("CONTROLLER", controllerResult.classType());
        assertEquals(1, controllerResult.methods().size());
    }

    // -- Standalone function extraction --

    @Test
    void whenAnalyzingFile_givenStandaloneFunctions_shouldExtractThem()
            throws IOException {

        final String content = """
                export function processOrder(orderId: string) {
                    return orderId;
                }

                function internalHelper(data: any) {
                    return data;
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/lib/orders.ts", "unknown");

        assertEquals(2, result.methods().size());
        assertEquals("processOrder", result.methods().get(0).name());
        assertEquals("internalHelper", result.methods().get(1).name());
    }

    // -- Next.js App Router --

    @Test
    void whenAnalyzingFile_givenNextJsRouteHandler_shouldExtractHttpInfo()
            throws IOException {

        final String content = """
                export async function GET(request: Request) {
                    return Response.json({ users: [] });
                }

                export async function POST(request: Request) {
                    return Response.json({ created: true });
                }
                """;

        final FileAnalysisResult result = engine.analyzeFile(
                content, "src/app/api/users/route.ts", "nextjs");

        assertTrue(result.entryPoint());
        assertFalse(result.methods().isEmpty());

        final MethodAnalysisResult getMethod = result.methods().stream()
                .filter(m -> m.name().equals("GET"))
                .findFirst().orElseThrow();
        assertEquals("GET", getMethod.httpMethod());
        assertEquals("/api/users", getMethod.httpPath());

        final MethodAnalysisResult postMethod = result.methods().stream()
                .filter(m -> m.name().equals("POST"))
                .findFirst().orElseThrow();
        assertEquals("POST", postMethod.httpMethod());
    }
}
