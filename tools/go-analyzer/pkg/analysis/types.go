// Package analysis provides Go source code analysis using the go/ast package.
//
// It extracts structural information from Go projects including packages,
// structs, interfaces, functions, methods, and their relationships.
// The output is a JSON structure consumed by the Java-side GoSourceParser.
package analysis

// ProjectAnalysis is the top-level result of analyzing a Go project.
// It contains the module path and all analyzed packages.
type ProjectAnalysis struct {
	Module   string             `json:"module"`
	Packages []*PackageAnalysis `json:"packages"`
}

// PackageAnalysis represents a single Go package with all its types,
// functions, and import relationships.
type PackageAnalysis struct {
	// Path is the fully-qualified import path
	// (e.g., "github.com/user/repo/internal/service").
	Path string `json:"path"`

	// Dir is the directory path relative to the project root.
	Dir string `json:"dir"`

	// Files lists the .go source files in this package (basenames only).
	Files []string `json:"files"`

	// Imports lists the fully-qualified import paths used by this package.
	// Only internal (same module) imports are included.
	Imports []string `json:"imports"`

	// Structs contains all struct type declarations in the package.
	Structs []*StructInfo `json:"structs"`

	// Interfaces contains all interface type declarations.
	Interfaces []*InterfaceInfo `json:"interfaces"`

	// Functions contains all package-level function declarations
	// (functions without a receiver).
	Functions []*FunctionInfo `json:"functions"`

	// IsEntryPoint indicates this package contains an entry point
	// (main function, HTTP handler registration, gRPC registration).
	IsEntryPoint bool `json:"isEntryPoint"`

	// ClassType is the inferred role of this package in the architecture.
	ClassType string `json:"classType"`
}

// StructInfo represents a Go struct type declaration.
type StructInfo struct {
	// Name is the struct type name (e.g., "OrderService").
	Name string `json:"name"`

	// File is the source file where the struct is declared (basename).
	File string `json:"file"`

	// Line is the 1-based line number of the type declaration.
	Line int `json:"line"`

	// Fields contains the struct's field declarations.
	Fields []*FieldInfo `json:"fields"`

	// Methods contains methods declared on this struct (with receiver).
	Methods []*FunctionInfo `json:"methods"`

	// EmbeddedTypes lists the names of embedded (anonymous) types.
	EmbeddedTypes []string `json:"embeddedTypes"`

	// Implements lists interface names this struct implements
	// (within the same module).
	Implements []string `json:"implements"`
}

// InterfaceInfo represents a Go interface type declaration.
type InterfaceInfo struct {
	// Name is the interface type name (e.g., "OrderRepository").
	Name string `json:"name"`

	// File is the source file where the interface is declared.
	File string `json:"file"`

	// Line is the 1-based line number of the type declaration.
	Line int `json:"line"`

	// Methods contains the method signatures defined in the interface.
	Methods []*MethodSignature `json:"methods"`

	// EmbeddedInterfaces lists embedded interface names.
	EmbeddedInterfaces []string `json:"embeddedInterfaces"`
}

// MethodSignature represents a method signature in an interface.
type MethodSignature struct {
	Name   string       `json:"name"`
	Params []*ParamInfo `json:"params"`
}

// FunctionInfo represents a function or method declaration.
type FunctionInfo struct {
	// Name is the function name (e.g., "CreateOrder").
	Name string `json:"name"`

	// File is the source file where the function is declared.
	File string `json:"file"`

	// Line is the 1-based line number of the func declaration.
	Line int `json:"line"`

	// Receiver is the receiver type for methods (e.g., "*OrderService"),
	// empty for package-level functions.
	Receiver string `json:"receiver,omitempty"`

	// Params contains the function parameter declarations.
	Params []*ParamInfo `json:"params"`

	// Returns contains the return type names.
	Returns []string `json:"returns"`

	// HTTPMethod is the HTTP method if this is an HTTP handler
	// (GET, POST, etc.), empty otherwise.
	HTTPMethod string `json:"httpMethod,omitempty"`

	// HTTPPath is the route path if this is an HTTP handler,
	// empty otherwise.
	HTTPPath string `json:"httpPath,omitempty"`

	// HasPanic indicates the function body contains panic() calls.
	HasPanic bool `json:"hasPanic,omitempty"`

	// Doc is the function's doc comment (first line only).
	Doc string `json:"doc,omitempty"`
}

// ParamInfo represents a function parameter.
type ParamInfo struct {
	// Name is the parameter name (may be empty for unnamed params).
	Name string `json:"name"`

	// Type is the parameter type as written in source code.
	Type string `json:"type"`

	// Package is the fully-qualified package path of the type,
	// if it references an internal project package. Empty for
	// builtin or external types.
	Package string `json:"package,omitempty"`

	// IsPointer indicates the parameter is a pointer type.
	IsPointer bool `json:"isPointer,omitempty"`

	// IsSlice indicates the parameter is a slice type.
	IsSlice bool `json:"isSlice,omitempty"`

	// IsVariadic indicates this is a variadic parameter.
	IsVariadic bool `json:"isVariadic,omitempty"`
}

// FieldInfo represents a struct field.
type FieldInfo struct {
	// Name is the field name (empty for embedded fields).
	Name string `json:"name"`

	// Type is the field type as written in source code.
	Type string `json:"type"`

	// Package is the fully-qualified package path of the type,
	// if it references an internal project package.
	Package string `json:"package,omitempty"`

	// IsExported indicates the field is exported (starts with uppercase).
	IsExported bool `json:"isExported"`

	// Tag is the struct field tag (e.g., `json:"name"`).
	Tag string `json:"tag,omitempty"`
}
