package co.fanki.domainmcp.analysis.domain.golang;

import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoFieldInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoFunctionInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoInterfaceInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoMethodSignature;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoPackageInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoParamInfo;
import co.fanki.domainmcp.analysis.domain.golang.GoAnalysisResult.GoStructInfo;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GoAnalysisResult} JSON deserialization.
 *
 * <p>Verifies that the JSON output of the Go AST analyzer tool
 * correctly maps to the Java record structure.</p>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GoAnalysisResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void whenDeserializing_givenFullProjectJson_shouldMapAllFields()
            throws Exception {

        final String json = """
                {
                  "module": "github.com/user/myapp",
                  "packages": [
                    {
                      "path": "github.com/user/myapp/internal/handler",
                      "dir": "internal/handler",
                      "files": ["order_handler.go"],
                      "imports": ["github.com/user/myapp/internal/service"],
                      "structs": [
                        {
                          "name": "OrderHandler",
                          "file": "order_handler.go",
                          "line": 15,
                          "fields": [
                            {
                              "name": "service",
                              "type": "*service.OrderService",
                              "package": "github.com/user/myapp/internal/service",
                              "isExported": false,
                              "tag": ""
                            }
                          ],
                          "methods": [
                            {
                              "name": "Create",
                              "file": "order_handler.go",
                              "line": 25,
                              "receiver": "*OrderHandler",
                              "params": [
                                {
                                  "name": "c",
                                  "type": "*gin.Context",
                                  "package": "",
                                  "isPointer": true,
                                  "isSlice": false,
                                  "isVariadic": false
                                }
                              ],
                              "returns": [],
                              "httpMethod": "GET",
                              "httpPath": "",
                              "hasPanic": false,
                              "doc": "Create handles order creation."
                            }
                          ],
                          "embeddedTypes": [],
                          "implements": ["Handler"]
                        }
                      ],
                      "interfaces": [
                        {
                          "name": "Handler",
                          "file": "order_handler.go",
                          "line": 10,
                          "methods": [
                            {
                              "name": "Create",
                              "params": [
                                {
                                  "name": "c",
                                  "type": "*gin.Context",
                                  "package": "",
                                  "isPointer": true,
                                  "isSlice": false,
                                  "isVariadic": false
                                }
                              ]
                            }
                          ],
                          "embeddedInterfaces": []
                        }
                      ],
                      "functions": [
                        {
                          "name": "NewOrderHandler",
                          "file": "order_handler.go",
                          "line": 20,
                          "receiver": "",
                          "params": [
                            {
                              "name": "svc",
                              "type": "*service.OrderService",
                              "package": "github.com/user/myapp/internal/service",
                              "isPointer": true,
                              "isSlice": false,
                              "isVariadic": false
                            }
                          ],
                          "returns": ["*OrderHandler"],
                          "httpMethod": "",
                          "httpPath": "",
                          "hasPanic": false,
                          "doc": "NewOrderHandler creates a new handler."
                        }
                      ],
                      "isEntryPoint": true,
                      "classType": "CONTROLLER"
                    }
                  ]
                }
                """;

        final GoAnalysisResult result = MAPPER.readValue(
                json, GoAnalysisResult.class);

        // Module
        assertEquals("github.com/user/myapp", result.module());

        // Packages
        assertNotNull(result.packages());
        assertEquals(1, result.packages().size());

        final GoPackageInfo pkg = result.packages().get(0);
        assertEquals("github.com/user/myapp/internal/handler", pkg.path());
        assertEquals("internal/handler", pkg.dir());
        assertEquals(1, pkg.files().size());
        assertEquals("order_handler.go", pkg.files().get(0));
        assertTrue(pkg.isEntryPoint());
        assertEquals("CONTROLLER", pkg.classType());

        // Imports
        assertEquals(1, pkg.imports().size());
        assertEquals("github.com/user/myapp/internal/service",
                pkg.imports().get(0));

        // Structs
        assertEquals(1, pkg.structs().size());
        final GoStructInfo struct = pkg.structs().get(0);
        assertEquals("OrderHandler", struct.name());
        assertEquals(15, struct.line());
        assertEquals(1, struct.fields().size());
        assertEquals(1, struct.methods().size());
        assertEquals(1, struct.implements_().size());
        assertEquals("Handler", struct.implements_().get(0));

        // Struct fields
        final GoFieldInfo field = struct.fields().get(0);
        assertEquals("service", field.name());
        assertEquals("*service.OrderService", field.typeName());
        assertEquals("github.com/user/myapp/internal/service",
                field.packagePath());
        assertFalse(field.isExported());

        // Struct methods
        final GoFunctionInfo method = struct.methods().get(0);
        assertEquals("Create", method.name());
        assertEquals("*OrderHandler", method.receiver());
        assertEquals(25, method.line());
        assertEquals("GET", method.httpMethod());
        assertEquals("Create handles order creation.", method.doc());

        // Method params
        assertEquals(1, method.params().size());
        final GoParamInfo param = method.params().get(0);
        assertEquals("c", param.name());
        assertEquals("*gin.Context", param.typeName());
        assertTrue(param.isPointer());

        // Interfaces
        assertEquals(1, pkg.interfaces().size());
        final GoInterfaceInfo iface = pkg.interfaces().get(0);
        assertEquals("Handler", iface.name());
        assertEquals(1, iface.methods().size());

        final GoMethodSignature sig = iface.methods().get(0);
        assertEquals("Create", sig.name());

        // Functions
        assertEquals(1, pkg.functions().size());
        final GoFunctionInfo func = pkg.functions().get(0);
        assertEquals("NewOrderHandler", func.name());
        assertEquals("", func.receiver());
        assertFalse(func.hasPanic());
    }

    @Test
    void whenDeserializing_givenMinimalJson_shouldHandleNulls()
            throws Exception {

        final String json = """
                {
                  "module": "example.com/minimal",
                  "packages": [
                    {
                      "path": "example.com/minimal",
                      "dir": "",
                      "files": ["main.go"],
                      "imports": [],
                      "structs": [],
                      "interfaces": [],
                      "functions": [
                        {
                          "name": "main",
                          "file": "main.go",
                          "line": 5,
                          "receiver": "",
                          "params": [],
                          "returns": [],
                          "httpMethod": "",
                          "httpPath": "",
                          "hasPanic": false,
                          "doc": ""
                        }
                      ],
                      "isEntryPoint": true,
                      "classType": "OTHER"
                    }
                  ]
                }
                """;

        final GoAnalysisResult result = MAPPER.readValue(
                json, GoAnalysisResult.class);

        assertEquals("example.com/minimal", result.module());
        assertEquals(1, result.packages().size());

        final GoPackageInfo pkg = result.packages().get(0);
        assertTrue(pkg.structs().isEmpty());
        assertTrue(pkg.interfaces().isEmpty());
        assertEquals(1, pkg.functions().size());
        assertEquals("main", pkg.functions().get(0).name());
        assertTrue(pkg.isEntryPoint());
    }

    @Test
    void whenDeserializing_givenUnknownFields_shouldIgnoreThem()
            throws Exception {

        final String json = """
                {
                  "module": "example.com/test",
                  "packages": [],
                  "unknownField": "should be ignored",
                  "anotherUnknown": 42
                }
                """;

        final GoAnalysisResult result = MAPPER.readValue(
                json, GoAnalysisResult.class);

        assertEquals("example.com/test", result.module());
        assertNotNull(result.packages());
        assertTrue(result.packages().isEmpty());
    }

    @Test
    void whenDeserializing_givenStructWithEmbeddedTypes_shouldParseThem()
            throws Exception {

        final String json = """
                {
                  "module": "example.com/embedded",
                  "packages": [
                    {
                      "path": "example.com/embedded",
                      "dir": "",
                      "files": ["model.go"],
                      "imports": [],
                      "structs": [
                        {
                          "name": "Admin",
                          "file": "model.go",
                          "line": 10,
                          "fields": [],
                          "methods": [],
                          "embeddedTypes": ["User", "sync.Mutex"],
                          "implements": []
                        }
                      ],
                      "interfaces": [],
                      "functions": [],
                      "isEntryPoint": false,
                      "classType": "ENTITY"
                    }
                  ]
                }
                """;

        final GoAnalysisResult result = MAPPER.readValue(
                json, GoAnalysisResult.class);

        final GoStructInfo struct = result.packages().get(0).structs().get(0);
        assertEquals("Admin", struct.name());
        assertEquals(2, struct.embeddedTypes().size());
        assertTrue(struct.embeddedTypes().contains("User"));
        assertTrue(struct.embeddedTypes().contains("sync.Mutex"));
    }

    @Test
    void whenDeserializing_givenFunctionWithPanic_shouldSetFlag()
            throws Exception {

        final String json = """
                {
                  "module": "example.com/panic",
                  "packages": [
                    {
                      "path": "example.com/panic",
                      "dir": "",
                      "files": ["validator.go"],
                      "imports": [],
                      "structs": [],
                      "interfaces": [],
                      "functions": [
                        {
                          "name": "MustParse",
                          "file": "validator.go",
                          "line": 8,
                          "receiver": "",
                          "params": [
                            {
                              "name": "input",
                              "type": "string",
                              "package": "",
                              "isPointer": false,
                              "isSlice": false,
                              "isVariadic": false
                            }
                          ],
                          "returns": ["Config"],
                          "httpMethod": "",
                          "httpPath": "",
                          "hasPanic": true,
                          "doc": "MustParse panics if input is invalid."
                        }
                      ],
                      "isEntryPoint": false,
                      "classType": "UTILITY"
                    }
                  ]
                }
                """;

        final GoAnalysisResult result = MAPPER.readValue(
                json, GoAnalysisResult.class);

        final GoFunctionInfo func =
                result.packages().get(0).functions().get(0);
        assertEquals("MustParse", func.name());
        assertTrue(func.hasPanic());
        assertEquals(1, func.params().size());
        assertFalse(func.params().get(0).isPointer());
    }

}
