package analysis

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strings"
	"unicode"
)

// Analyzer performs static analysis on a Go project using the go/ast package.
type Analyzer struct {
	projectRoot string
	modulePath  string
	fset        *token.FileSet
}

// NewAnalyzer creates a new Analyzer for the given project root.
func NewAnalyzer(projectRoot string) *Analyzer {
	return &Analyzer{
		projectRoot: projectRoot,
		fset:        token.NewFileSet(),
	}
}

// Analyze performs full analysis of the Go project and returns the result.
func (a *Analyzer) Analyze() (*ProjectAnalysis, error) {
	modulePath, err := a.readModulePath()
	if err != nil {
		return nil, fmt.Errorf("reading go.mod: %w", err)
	}
	a.modulePath = modulePath

	packages, err := a.discoverPackages()
	if err != nil {
		return nil, fmt.Errorf("discovering packages: %w", err)
	}

	result := &ProjectAnalysis{
		Module:   a.modulePath,
		Packages: packages,
	}

	return result, nil
}

// readModulePath extracts the module path from go.mod.
func (a *Analyzer) readModulePath() (string, error) {
	goModPath := filepath.Join(a.projectRoot, "go.mod")
	data, err := os.ReadFile(goModPath)
	if err != nil {
		return "", fmt.Errorf("reading %s: %w", goModPath, err)
	}

	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "module ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "module ")), nil
		}
	}

	return "", fmt.Errorf("module directive not found in go.mod")
}

// discoverPackages walks the project directory and analyzes each Go package.
func (a *Analyzer) discoverPackages() ([]*PackageAnalysis, error) {
	var packages []*PackageAnalysis
	visited := make(map[string]bool)

	err := filepath.Walk(a.projectRoot, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // skip errors
		}

		// Skip excluded directories
		if info.IsDir() {
			name := info.Name()
			if isExcludedDir(name) {
				return filepath.SkipDir
			}
			return nil
		}

		// Only process .go files (non-test, non-generated)
		if !strings.HasSuffix(info.Name(), ".go") {
			return nil
		}
		if strings.HasSuffix(info.Name(), "_test.go") {
			return nil
		}
		if isGeneratedFile(info.Name()) {
			return nil
		}

		dir := filepath.Dir(path)
		if visited[dir] {
			return nil
		}
		visited[dir] = true

		pkg, err := a.analyzePackage(dir)
		if err != nil {
			// Log and skip packages that fail to parse
			fmt.Fprintf(os.Stderr, "WARN: skipping package %s: %v\n", dir, err)
			return nil
		}
		if pkg != nil {
			packages = append(packages, pkg)
		}

		return nil
	})

	return packages, err
}

// analyzePackage parses and analyzes all Go files in a directory.
func (a *Analyzer) analyzePackage(dir string) (*PackageAnalysis, error) {
	pkgs, err := parser.ParseDir(a.fset, dir, func(info os.FileInfo) bool {
		name := info.Name()
		return strings.HasSuffix(name, ".go") &&
			!strings.HasSuffix(name, "_test.go") &&
			!isGeneratedFile(name)
	}, parser.ParseComments)
	if err != nil {
		return nil, fmt.Errorf("parsing directory %s: %w", dir, err)
	}

	for _, pkg := range pkgs {
		return a.extractPackageInfo(dir, pkg)
	}

	return nil, nil
}

// extractPackageInfo extracts all structural information from a parsed package.
func (a *Analyzer) extractPackageInfo(
	dir string,
	pkg *ast.Package,
) (*PackageAnalysis, error) {

	relDir, _ := filepath.Rel(a.projectRoot, dir)
	if relDir == "." {
		relDir = ""
	}

	pkgPath := a.modulePath
	if relDir != "" {
		pkgPath = a.modulePath + "/" + filepath.ToSlash(relDir)
	}

	pa := &PackageAnalysis{
		Path: pkgPath,
		Dir:  filepath.ToSlash(relDir),
	}

	importSet := make(map[string]bool)
	var allStructs []*StructInfo
	var allInterfaces []*InterfaceInfo
	var allFunctions []*FunctionInfo

	for filename, file := range pkg.Files {
		baseName := filepath.Base(filename)
		pa.Files = append(pa.Files, baseName)

		// Extract imports (internal only)
		for _, imp := range file.Imports {
			impPath := strings.Trim(imp.Path.Value, `"`)
			if strings.HasPrefix(impPath, a.modulePath) {
				importSet[impPath] = true
			}
		}

		// Extract type declarations
		structs, interfaces := a.extractTypes(file, baseName)
		allStructs = append(allStructs, structs...)
		allInterfaces = append(allInterfaces, interfaces...)

		// Extract package-level functions
		funcs := a.extractFunctions(file, baseName)
		allFunctions = append(allFunctions, funcs...)
	}

	// Assign methods to their receiver structs
	a.bindMethodsToStructs(allStructs, allFunctions)

	// Remove bound methods from the function list
	boundMethods := make(map[string]bool)
	for _, s := range allStructs {
		for _, m := range s.Methods {
			key := m.Receiver + "." + m.Name
			boundMethods[key] = true
		}
	}

	var freeFunctions []*FunctionInfo
	for _, f := range allFunctions {
		if f.Receiver == "" {
			freeFunctions = append(freeFunctions, f)
		} else {
			key := f.Receiver + "." + f.Name
			if !boundMethods[key] {
				freeFunctions = append(freeFunctions, f)
			}
		}
	}

	for imp := range importSet {
		pa.Imports = append(pa.Imports, imp)
	}

	pa.Structs = allStructs
	pa.Interfaces = allInterfaces
	pa.Functions = freeFunctions
	pa.IsEntryPoint = a.detectEntryPoint(pkg, pa)
	pa.ClassType = a.inferClassType(dir, pa)

	return pa, nil
}

// extractTypes extracts struct and interface declarations from a file.
func (a *Analyzer) extractTypes(
	file *ast.File,
	baseName string,
) ([]*StructInfo, []*InterfaceInfo) {

	var structs []*StructInfo
	var interfaces []*InterfaceInfo

	for _, decl := range file.Decls {
		genDecl, ok := decl.(*ast.GenDecl)
		if !ok || genDecl.Tok != token.TYPE {
			continue
		}

		for _, spec := range genDecl.Specs {
			typeSpec, ok := spec.(*ast.TypeSpec)
			if !ok {
				continue
			}

			pos := a.fset.Position(typeSpec.Pos())

			switch t := typeSpec.Type.(type) {
			case *ast.StructType:
				si := &StructInfo{
					Name: typeSpec.Name.Name,
					File: baseName,
					Line: pos.Line,
				}
				si.Fields, si.EmbeddedTypes = a.extractFields(t)
				structs = append(structs, si)

			case *ast.InterfaceType:
				ii := &InterfaceInfo{
					Name: typeSpec.Name.Name,
					File: baseName,
					Line: pos.Line,
				}
				ii.Methods, ii.EmbeddedInterfaces = a.extractInterfaceMethods(t)
				interfaces = append(interfaces, ii)
			}
		}
	}

	return structs, interfaces
}

// extractFields extracts field declarations from a struct type.
func (a *Analyzer) extractFields(
	st *ast.StructType,
) ([]*FieldInfo, []string) {

	var fields []*FieldInfo
	var embedded []string

	if st.Fields == nil {
		return fields, embedded
	}

	for _, field := range st.Fields.List {
		typeName := exprToString(field.Type)
		isPtr := false
		if _, ok := field.Type.(*ast.StarExpr); ok {
			isPtr = true
		}

		tag := ""
		if field.Tag != nil {
			tag = field.Tag.Value
		}

		if len(field.Names) == 0 {
			// Embedded field
			embedded = append(embedded, typeName)
			fields = append(fields, &FieldInfo{
				Type:       typeName,
				IsExported: isExportedType(typeName),
				Tag:        tag,
			})
		} else {
			for _, name := range field.Names {
				fields = append(fields, &FieldInfo{
					Name:       name.Name,
					Type:       typeName,
					IsExported: name.IsExported(),
					Tag:        tag,
				})
				_ = isPtr // could set on field if needed
			}
		}
	}

	return fields, embedded
}

// extractInterfaceMethods extracts method signatures from an interface type.
func (a *Analyzer) extractInterfaceMethods(
	iface *ast.InterfaceType,
) ([]*MethodSignature, []string) {

	var methods []*MethodSignature
	var embedded []string

	if iface.Methods == nil {
		return methods, embedded
	}

	for _, method := range iface.Methods.List {
		switch t := method.Type.(type) {
		case *ast.FuncType:
			if len(method.Names) > 0 {
				ms := &MethodSignature{
					Name:   method.Names[0].Name,
					Params: a.extractParamList(t.Params),
				}
				methods = append(methods, ms)
			}
		case *ast.Ident:
			embedded = append(embedded, t.Name)
		case *ast.SelectorExpr:
			embedded = append(embedded, exprToString(t))
		}
	}

	return methods, embedded
}

// extractFunctions extracts all function declarations from a file.
func (a *Analyzer) extractFunctions(
	file *ast.File,
	baseName string,
) []*FunctionInfo {

	var funcs []*FunctionInfo

	for _, decl := range file.Decls {
		funcDecl, ok := decl.(*ast.FuncDecl)
		if !ok {
			continue
		}

		pos := a.fset.Position(funcDecl.Pos())

		fi := &FunctionInfo{
			Name:   funcDecl.Name.Name,
			File:   baseName,
			Line:   pos.Line,
			Params: a.extractParamList(funcDecl.Type.Params),
		}

		// Extract receiver
		if funcDecl.Recv != nil && len(funcDecl.Recv.List) > 0 {
			fi.Receiver = exprToString(funcDecl.Recv.List[0].Type)
		}

		// Extract return types
		if funcDecl.Type.Results != nil {
			for _, result := range funcDecl.Type.Results.List {
				fi.Returns = append(fi.Returns, exprToString(result.Type))
			}
		}

		// Extract doc comment (first line)
		if funcDecl.Doc != nil && len(funcDecl.Doc.List) > 0 {
			fi.Doc = strings.TrimPrefix(funcDecl.Doc.List[0].Text, "// ")
		}

		// Check for panic in body
		if funcDecl.Body != nil {
			fi.HasPanic = containsPanic(funcDecl.Body)
		}

		// Detect HTTP handler patterns from params
		fi.HTTPMethod, fi.HTTPPath = detectHTTPHandler(fi)

		funcs = append(funcs, fi)
	}

	return funcs
}

// extractParamList extracts parameter info from a field list.
func (a *Analyzer) extractParamList(fields *ast.FieldList) []*ParamInfo {
	if fields == nil {
		return nil
	}

	var params []*ParamInfo

	for _, field := range fields.List {
		typeName := exprToString(field.Type)
		isPtr := false
		isSlice := false
		isVariadic := false

		// Detect pointer, slice, variadic
		switch field.Type.(type) {
		case *ast.StarExpr:
			isPtr = true
		case *ast.ArrayType:
			isSlice = true
		case *ast.Ellipsis:
			isVariadic = true
		}

		// Resolve package path for internal types
		pkgPath := a.resolveTypePackage(field.Type)

		if len(field.Names) == 0 {
			// Unnamed parameter
			params = append(params, &ParamInfo{
				Type:       typeName,
				Package:    pkgPath,
				IsPointer:  isPtr,
				IsSlice:    isSlice,
				IsVariadic: isVariadic,
			})
		} else {
			for _, name := range field.Names {
				params = append(params, &ParamInfo{
					Name:       name.Name,
					Type:       typeName,
					Package:    pkgPath,
					IsPointer:  isPtr,
					IsSlice:    isSlice,
					IsVariadic: isVariadic,
				})
			}
		}
	}

	return params
}

// resolveTypePackage attempts to resolve the package path for a type expression.
// Returns the full import path if the type references an internal package.
func (a *Analyzer) resolveTypePackage(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.StarExpr:
		return a.resolveTypePackage(t.X)
	case *ast.ArrayType:
		return a.resolveTypePackage(t.Elt)
	case *ast.Ellipsis:
		return a.resolveTypePackage(t.Elt)
	case *ast.SelectorExpr:
		// pkg.Type - the package name is the selector's X
		if ident, ok := t.X.(*ast.Ident); ok {
			// We can't resolve the full path without import info at this level,
			// but the caller has import context. Return the alias for now.
			return ident.Name
		}
	}
	return ""
}

// bindMethodsToStructs assigns method declarations to their receiver structs.
func (a *Analyzer) bindMethodsToStructs(
	structs []*StructInfo,
	funcs []*FunctionInfo,
) {
	structMap := make(map[string]*StructInfo)
	for _, s := range structs {
		structMap[s.Name] = s
	}

	for _, f := range funcs {
		if f.Receiver == "" {
			continue
		}

		// Strip pointer prefix from receiver
		receiverName := strings.TrimPrefix(f.Receiver, "*")

		if s, ok := structMap[receiverName]; ok {
			s.Methods = append(s.Methods, f)
		}
	}
}

// detectEntryPoint checks if a package is an entry point.
func (a *Analyzer) detectEntryPoint(
	pkg *ast.Package,
	pa *PackageAnalysis,
) bool {

	// package main with func main()
	if pkg.Name == "main" {
		for _, f := range pa.Functions {
			if f.Name == "main" {
				return true
			}
		}
	}

	// Check for HTTP handler registration patterns in source
	for _, file := range pkg.Files {
		for _, decl := range file.Decls {
			funcDecl, ok := decl.(*ast.FuncDecl)
			if !ok || funcDecl.Body == nil {
				continue
			}

			if containsHTTPRegistration(funcDecl.Body) {
				return true
			}
		}
	}

	return false
}

// inferClassType determines the architectural role of a package.
func (a *Analyzer) inferClassType(dir string, pa *PackageAnalysis) string {
	dirName := strings.ToLower(filepath.Base(dir))

	// Check for HTTP handler patterns in functions
	for _, f := range pa.Functions {
		if f.HTTPMethod != "" {
			return "CONTROLLER"
		}
	}
	for _, s := range pa.Structs {
		for _, m := range s.Methods {
			if m.HTTPMethod != "" {
				return "CONTROLLER"
			}
		}
	}

	// Package naming conventions
	controllerNames := []string{
		"handler", "controller", "api", "transport",
		"http", "rest", "grpc", "endpoint",
	}
	for _, name := range controllerNames {
		if strings.Contains(dirName, name) {
			return "CONTROLLER"
		}
	}

	serviceNames := []string{"service", "usecase", "application"}
	for _, name := range serviceNames {
		if strings.Contains(dirName, name) {
			return "SERVICE"
		}
	}

	repoNames := []string{
		"repository", "repo", "store", "storage",
		"dao", "persistence", "database",
	}
	for _, name := range repoNames {
		if strings.Contains(dirName, name) {
			return "REPOSITORY"
		}
	}

	entityNames := []string{"model", "entity", "domain"}
	for _, name := range entityNames {
		if strings.Contains(dirName, name) {
			return "ENTITY"
		}
	}

	dtoNames := []string{"dto", "request", "response", "payload", "schema"}
	for _, name := range dtoNames {
		if strings.Contains(dirName, name) {
			return "DTO"
		}
	}

	configNames := []string{"config", "cfg", "configuration"}
	for _, name := range configNames {
		if strings.Contains(dirName, name) {
			return "CONFIGURATION"
		}
	}

	listenerNames := []string{
		"listener", "consumer", "subscriber", "worker", "queue",
	}
	for _, name := range listenerNames {
		if strings.Contains(dirName, name) {
			return "LISTENER"
		}
	}

	utilNames := []string{
		"util", "utils", "helper", "helpers",
		"middleware", "interceptor", "pkg",
	}
	for _, name := range utilNames {
		if strings.Contains(dirName, name) {
			return "UTILITY"
		}
	}

	return "OTHER"
}

// ---------- helper functions ----------

// containsPanic checks if a block statement contains panic() calls.
func containsPanic(block *ast.BlockStmt) bool {
	found := false
	ast.Inspect(block, func(n ast.Node) bool {
		if found {
			return false
		}
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		if ident, ok := call.Fun.(*ast.Ident); ok {
			if ident.Name == "panic" {
				found = true
				return false
			}
		}
		return true
	})
	return found
}

// containsHTTPRegistration checks if a function body contains HTTP route
// registration calls (e.g., router.GET, http.HandleFunc).
func containsHTTPRegistration(block *ast.BlockStmt) bool {
	found := false
	httpMethods := map[string]bool{
		"GET": true, "POST": true, "PUT": true,
		"DELETE": true, "PATCH": true, "HEAD": true, "OPTIONS": true,
		"Get": true, "Post": true, "Put": true,
		"Delete": true, "Patch": true,
		"Handle": true, "HandleFunc": true,
		"Group": true, "Route": true, "Any": true,
	}

	ast.Inspect(block, func(n ast.Node) bool {
		if found {
			return false
		}
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		sel, ok := call.Fun.(*ast.SelectorExpr)
		if !ok {
			return true
		}
		if httpMethods[sel.Sel.Name] {
			found = true
			return false
		}
		return true
	})
	return found
}

// detectHTTPHandler detects if a function is an HTTP handler based on its
// parameter types. Returns the HTTP method and path (if detectable).
func detectHTTPHandler(fi *FunctionInfo) (string, string) {
	if fi.Params == nil {
		return "", ""
	}

	for _, p := range fi.Params {
		typeName := p.Type
		switch {
		case strings.Contains(typeName, "http.ResponseWriter"),
			strings.Contains(typeName, "http.Request"):
			return "GET", "" // default to GET, path unknown
		case strings.Contains(typeName, "gin.Context"):
			return "GET", ""
		case strings.Contains(typeName, "echo.Context"):
			return "GET", ""
		case strings.Contains(typeName, "fiber.Ctx"):
			return "GET", ""
		}
	}

	return "", ""
}

// exprToString converts an AST expression to its string representation.
func exprToString(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.Ident:
		return t.Name
	case *ast.StarExpr:
		return "*" + exprToString(t.X)
	case *ast.SelectorExpr:
		return exprToString(t.X) + "." + t.Sel.Name
	case *ast.ArrayType:
		return "[]" + exprToString(t.Elt)
	case *ast.MapType:
		return "map[" + exprToString(t.Key) + "]" + exprToString(t.Value)
	case *ast.InterfaceType:
		return "interface{}"
	case *ast.ChanType:
		return "chan " + exprToString(t.Value)
	case *ast.FuncType:
		return "func()"
	case *ast.Ellipsis:
		return "..." + exprToString(t.Elt)
	default:
		return fmt.Sprintf("%T", expr)
	}
}

// isExcludedDir checks if a directory should be skipped during discovery.
func isExcludedDir(name string) bool {
	excluded := map[string]bool{
		"vendor": true, "testdata": true, ".git": true,
		"node_modules": true, "third_party": true, "tools": true,
		".idea": true, ".vscode": true,
	}
	return excluded[name] || strings.HasPrefix(name, ".")
}

// isGeneratedFile checks if a file is generated code.
func isGeneratedFile(name string) bool {
	return strings.HasSuffix(name, ".pb.go") ||
		strings.HasSuffix(name, "_generated.go") ||
		strings.HasSuffix(name, "_gen.go") ||
		name == "wire_gen.go" ||
		name == "mock_gen.go"
}

// isExportedType checks if a type name starts with an uppercase letter
// (after stripping pointer/slice prefixes).
func isExportedType(name string) bool {
	clean := strings.TrimLeft(name, "*[]")
	if dot := strings.LastIndex(clean, "."); dot >= 0 {
		clean = clean[dot+1:]
	}
	if len(clean) == 0 {
		return false
	}
	return unicode.IsUpper(rune(clean[0]))
}
