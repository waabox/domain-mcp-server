import { parse } from '@babel/parser';
import _traverse from '@babel/traverse';
import * as t from '@babel/types';
import type { ImportInfo, MethodAnalysis } from './types';

// Handle both ESM and CJS default export from @babel/traverse
const traverse = typeof _traverse === 'function'
  ? _traverse
  : (_traverse as any).default as typeof _traverse;

interface ExtractedData {
  methods: MethodAnalysis[];
  imports: ImportInfo[];
  decorators: string[];
  namedExports: string[];
  classType: string;
  entryPoint: boolean;
}

/**
 * Parses a single file content with @babel/parser and extracts
 * methods, imports, decorators, and structural information.
 */
export function extractFileData(
  content: string,
  filePath: string,
  frameworkName: string,
): ExtractedData {
  let ast: ReturnType<typeof parse>;
  try {
    ast = parse(content, {
      sourceType: 'module',
      plugins: [
        'typescript',
        'jsx',
        'decorators-legacy',
        'classProperties',
        'classPrivateProperties',
        'classPrivateMethods',
        'exportDefaultFrom',
        'dynamicImport',
        'optionalChaining',
        'nullishCoalescingOperator',
      ],
    });
  } catch {
    // If parsing fails, return empty data
    return {
      methods: [],
      imports: [],
      decorators: [],
      namedExports: [],
      classType: 'OTHER',
      entryPoint: false,
    };
  }

  const methods: MethodAnalysis[] = [];
  const imports: ImportInfo[] = [];
  const decorators: string[] = [];
  const namedExports: string[] = [];
  let classType = 'OTHER';
  let entryPoint = false;

  traverse(ast, {
    // Extract imports
    ImportDeclaration(path) {
      const source = path.node.source.value;
      for (const specifier of path.node.specifiers) {
        if (t.isImportSpecifier(specifier)) {
          const imported = t.isIdentifier(specifier.imported)
            ? specifier.imported.name
            : specifier.imported.value;
          imports.push({
            importedName: imported,
            localName: specifier.local.name,
            source,
          });
        } else if (t.isImportDefaultSpecifier(specifier)) {
          imports.push({
            importedName: 'default',
            localName: specifier.local.name,
            source,
          });
        } else if (t.isImportNamespaceSpecifier(specifier)) {
          imports.push({
            importedName: '*',
            localName: specifier.local.name,
            source,
          });
        }
      }
    },

    // Extract class declarations with decorators
    ClassDeclaration(path) {
      const classDecorators = extractDecorators(path.node);
      decorators.push(...classDecorators);

      // Detect class type from decorators
      for (const dec of classDecorators) {
        if (dec === 'Controller') {
          classType = 'CONTROLLER';
          entryPoint = true;
        } else if (dec === 'Injectable' && classType === 'OTHER') {
          classType = 'SERVICE';
        } else if (dec === 'Component' || dec === 'Directive'
            || dec === 'Pipe') {
          classType = 'UTILITY';
        } else if (dec === 'NgModule') {
          classType = 'CONFIGURATION';
        }
      }

      // Extract class methods
      for (const member of path.node.body.body) {
        if (t.isClassMethod(member) || t.isClassProperty(member)) {
          const method = extractClassMethod(member, frameworkName);
          if (method) {
            methods.push(method);
            if (method.httpMethod) {
              entryPoint = true;
            }
          }
        }
      }
    },

    // Extract standalone function declarations
    FunctionDeclaration(path) {
      if (!path.node.id) return;
      const name = path.node.id.name;
      const lineNumber = path.node.loc?.start.line ?? 0;
      const params = extractParameterTypes(path.node.params);

      // Check for Next.js App Router HTTP handlers
      const httpMethod = getNextJsHttpMethod(name, filePath, frameworkName);

      methods.push({
        name,
        lineNumber,
        httpMethod,
        httpPath: httpMethod ? extractNextJsRoutePath(filePath) : null,
        parameterTypes: params,
      });

      if (httpMethod) {
        entryPoint = true;
      }
    },

    // Export named declarations (for Next.js route handlers)
    ExportNamedDeclaration(path) {
      const decl = path.node.declaration;
      if (t.isFunctionDeclaration(decl) && decl.id) {
        namedExports.push(decl.id.name);
      }
      if (t.isVariableDeclaration(decl)) {
        for (const declarator of decl.declarations) {
          if (t.isIdentifier(declarator.id)) {
            namedExports.push(declarator.id.name);

            // Handle arrow function / function expression exports
            if (
              t.isArrowFunctionExpression(declarator.init)
              || t.isFunctionExpression(declarator.init)
            ) {
              const name = declarator.id.name;
              const lineNumber = declarator.loc?.start.line ?? 0;
              const params = extractParameterTypes(declarator.init.params);
              const httpMethod = getNextJsHttpMethod(
                name, filePath, frameworkName);

              methods.push({
                name,
                lineNumber,
                httpMethod,
                httpPath: httpMethod
                  ? extractNextJsRoutePath(filePath) : null,
                parameterTypes: params,
              });

              if (httpMethod) {
                entryPoint = true;
              }
            }
          }
        }
      }
    },

    // Extract arrow function / function expression variable declarations
    // Catches at any scope level:
    //   Top-level: const handler = async (req, res) => { ... }
    //   Nested:    const helper = () => { ... } (inside components)
    // Skips exported ones (handled by ExportNamedDeclaration)
    VariableDeclarator(path) {
      const parentDecl = path.parentPath;
      if (!parentDecl || !t.isVariableDeclaration(parentDecl.node)) return;

      // Skip if inside ExportNamedDeclaration (already handled)
      const grandParent = parentDecl.parentPath;
      if (grandParent && t.isExportNamedDeclaration(grandParent.node)) return;

      if (!t.isIdentifier(path.node.id)) return;
      const init = path.node.init;

      // Direct arrow/function: const handler = async () => { ... }
      // Wrapped: const handler = useCallback(() => { ... }, [deps])
      const funcNode = extractFunctionFromInit(init);
      if (!funcNode) return;

      const name = path.node.id.name;
      const lineNumber = path.node.loc?.start.line ?? 0;
      const params = extractParameterTypes(funcNode.params);
      const httpMethod = getNextJsHttpMethod(name, filePath, frameworkName);

      methods.push({
        name,
        lineNumber,
        httpMethod,
        httpPath: httpMethod ? extractNextJsRoutePath(filePath) : null,
        parameterTypes: params,
      });

      if (httpMethod) {
        entryPoint = true;
      }
    },

    // Extract object property arrow functions / function expressions
    // Catches: { email: (value) => { ... }, password: (v) => { ... } }
    ObjectProperty(path) {
      if (!t.isIdentifier(path.node.key)) return;
      const value = path.node.value;
      if (
        !t.isArrowFunctionExpression(value)
        && !t.isFunctionExpression(value)
      ) {
        return;
      }

      const name = path.node.key.name;
      const lineNumber = path.node.loc?.start.line ?? 0;
      const params = extractParameterTypes(value.params);

      methods.push({
        name,
        lineNumber,
        httpMethod: null,
        httpPath: null,
        parameterTypes: params,
      });
    },

    // Extract object shorthand methods
    // Catches: { doSomething() { ... } }
    ObjectMethod(path) {
      if (!t.isIdentifier(path.node.key)) return;
      const name = path.node.key.name;
      const lineNumber = path.node.loc?.start.line ?? 0;
      const params = extractParameterTypes(path.node.params);

      methods.push({
        name,
        lineNumber,
        httpMethod: null,
        httpPath: null,
        parameterTypes: params,
      });
    },

    // Express route registrations
    CallExpression(path) {
      if (!t.isMemberExpression(path.node.callee)) return;

      const obj = path.node.callee.object;
      const prop = path.node.callee.property;

      if (!t.isIdentifier(obj) || !t.isIdentifier(prop)) return;

      const objectName = obj.name;
      const methodName = prop.name;

      const expressPatterns = ['app', 'router'];
      const httpMethods = ['get', 'post', 'put', 'delete', 'patch',
        'all', 'use'];

      if (
        expressPatterns.includes(objectName)
        && httpMethods.includes(methodName)
      ) {
        entryPoint = true;
        if (classType === 'OTHER') {
          classType = 'CONTROLLER';
        }
      }
    },
  });

  // Infer class type from filename if still OTHER
  if (classType === 'OTHER') {
    classType = inferClassTypeFromFilename(filePath);
  }

  // Detect entry point from well-known filenames
  if (!entryPoint) {
    entryPoint = isWellKnownEntryPoint(filePath);
  }

  return {
    methods,
    imports,
    decorators,
    namedExports,
    classType,
    entryPoint,
  };
}

function extractDecorators(
  node: t.ClassDeclaration | t.ClassMethod | t.ClassProperty,
): string[] {
  const result: string[] = [];
  const decs = (node as any).decorators as t.Decorator[] | undefined;
  if (!decs) return result;

  for (const dec of decs) {
    if (t.isCallExpression(dec.expression)
        && t.isIdentifier(dec.expression.callee)) {
      result.push(dec.expression.callee.name);
    } else if (t.isIdentifier(dec.expression)) {
      result.push(dec.expression.name);
    }
  }
  return result;
}

function extractClassMethod(
  member: t.ClassMethod | t.ClassProperty,
  frameworkName: string,
): MethodAnalysis | null {
  if (t.isClassProperty(member)) {
    // Arrow function class property: name = async (args) => {}
    if (
      !t.isArrowFunctionExpression(member.value)
      && !t.isFunctionExpression(member.value)
    ) {
      return null;
    }

    const key = member.key;
    if (!t.isIdentifier(key)) return null;
    const name = key.name;
    const lineNumber = member.loc?.start.line ?? 0;
    const params = extractParameterTypes(member.value.params);

    let httpMethod: string | null = null;
    let httpPath: string | null = null;

    if (frameworkName === 'nestjs') {
      const methodDecs = extractDecorators(member);
      const httpInfo = extractNestJsHttpInfo(methodDecs, member);
      httpMethod = httpInfo.httpMethod;
      httpPath = httpInfo.httpPath;
    }

    return { name, lineNumber, httpMethod, httpPath, parameterTypes: params };
  }

  if (!t.isClassMethod(member)) return null;

  // Skip constructor
  if (member.kind === 'constructor') return null;

  const key = member.key;
  if (!t.isIdentifier(key)) return null;

  const name = key.name;
  const lineNumber = member.loc?.start.line ?? 0;
  const params = extractParameterTypes(member.params);

  let httpMethod: string | null = null;
  let httpPath: string | null = null;

  if (frameworkName === 'nestjs') {
    const methodDecs = extractDecorators(member);
    const httpInfo = extractNestJsHttpInfo(methodDecs, member);
    httpMethod = httpInfo.httpMethod;
    httpPath = httpInfo.httpPath;
  }

  return { name, lineNumber, httpMethod, httpPath, parameterTypes: params };
}

function extractNestJsHttpInfo(
  decoratorNames: string[],
  node: t.ClassMethod | t.ClassProperty,
): { httpMethod: string | null; httpPath: string | null } {
  const methodMap: Record<string, string> = {
    Get: 'GET',
    Post: 'POST',
    Put: 'PUT',
    Delete: 'DELETE',
    Patch: 'PATCH',
  };

  const decs = (node as any).decorators as t.Decorator[] | undefined;
  if (!decs) return { httpMethod: null, httpPath: null };

  for (const dec of decs) {
    if (!t.isCallExpression(dec.expression)) continue;
    if (!t.isIdentifier(dec.expression.callee)) continue;

    const decName = dec.expression.callee.name;
    const httpMethod = methodMap[decName];
    if (!httpMethod) continue;

    let httpPath: string | null = null;
    if (dec.expression.arguments.length > 0) {
      const arg = dec.expression.arguments[0];
      if (t.isStringLiteral(arg)) {
        httpPath = arg.value;
      }
    }

    return { httpMethod, httpPath };
  }

  return { httpMethod: null, httpPath: null };
}

function extractParameterTypes(
  params: (t.Identifier | t.Pattern | t.RestElement | t.TSParameterProperty)[],
): string[] {
  const types: string[] = [];
  for (const param of params) {
    let annotation: t.TSTypeAnnotation | null | undefined = null;

    if (t.isIdentifier(param)) {
      annotation = param.typeAnnotation as t.TSTypeAnnotation | undefined;
    } else if (t.isAssignmentPattern(param) && t.isIdentifier(param.left)) {
      annotation = param.left.typeAnnotation as
        t.TSTypeAnnotation | undefined;
    } else if (t.isTSParameterProperty(param)) {
      if (t.isIdentifier(param.parameter)) {
        annotation = param.parameter.typeAnnotation as
          t.TSTypeAnnotation | undefined;
      }
    }

    if (annotation && t.isTSTypeAnnotation(annotation)) {
      const typeNode = annotation.typeAnnotation;
      if (t.isTSTypeReference(typeNode) && t.isIdentifier(typeNode.typeName)) {
        types.push(typeNode.typeName.name);
      }
    }
  }
  return types;
}

const NEXT_JS_HTTP_METHODS = new Set([
  'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS',
]);

/**
 * Extracts the function node from a variable initializer.
 * Handles direct arrow/function expressions and common wrappers
 * like useCallback, useMemo, debounce, throttle.
 */
function extractFunctionFromInit(
  init: t.Expression | null | undefined,
): t.ArrowFunctionExpression | t.FunctionExpression | null {
  if (!init) return null;

  // Direct: const handler = () => { ... }
  if (t.isArrowFunctionExpression(init) || t.isFunctionExpression(init)) {
    return init;
  }

  // Wrapped: const handler = useCallback(() => { ... }, [deps])
  if (t.isCallExpression(init) && init.arguments.length > 0) {
    const firstArg = init.arguments[0];
    if (
      t.isArrowFunctionExpression(firstArg)
      || t.isFunctionExpression(firstArg)
    ) {
      return firstArg;
    }
  }

  return null;
}

function getNextJsHttpMethod(
  functionName: string,
  filePath: string,
  frameworkName: string,
): string | null {
  if (frameworkName !== 'nextjs') return null;
  if (!filePath.includes('route.')) return null;
  if (NEXT_JS_HTTP_METHODS.has(functionName)) {
    return functionName;
  }
  return null;
}

function extractNextJsRoutePath(filePath: string): string | null {
  // Extract route from path: app/api/users/route.ts -> /api/users
  const appIndex = filePath.indexOf('app/');
  if (appIndex < 0) return null;

  const afterApp = filePath.substring(appIndex + 4);
  const routeIndex = afterApp.lastIndexOf('/route.');
  if (routeIndex < 0) return null;

  const routePath = '/' + afterApp.substring(0, routeIndex);
  // Convert [param] to :param
  return routePath.replace(/\[([^\]]+)\]/g, ':$1');
}

function inferClassTypeFromFilename(filePath: string): string {
  const filename = filePath.split('/').pop()?.toLowerCase() ?? '';

  if (filename.includes('.controller.')) return 'CONTROLLER';
  if (filename.includes('.service.')) return 'SERVICE';
  if (filename.includes('.repository.')) return 'REPOSITORY';
  if (filename.includes('.entity.')) return 'ENTITY';
  if (filename.includes('.dto.')) return 'DTO';
  if (filename.includes('.config.')) return 'CONFIGURATION';
  if (filename.includes('.middleware.')) return 'UTILITY';
  if (filename.includes('.guard.')) return 'UTILITY';
  if (filename.includes('.interceptor.')) return 'UTILITY';
  if (filename.includes('.pipe.')) return 'UTILITY';
  if (filename.includes('.filter.')) return 'UTILITY';
  if (filename.includes('.exception.')) return 'EXCEPTION';
  if (filename.includes('.listener.')) return 'LISTENER';
  if (filename.includes('.module.')) return 'CONFIGURATION';

  return 'OTHER';
}

const ENTRY_POINT_FILENAMES = new Set([
  'main.ts', 'main.js',
  'index.ts', 'index.js',
  'app.ts', 'app.js',
  'server.ts', 'server.js',
]);

function isWellKnownEntryPoint(filePath: string): boolean {
  const filename = filePath.split('/').pop() ?? '';
  return ENTRY_POINT_FILENAMES.has(filename);
}
