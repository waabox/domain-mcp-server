package analysis

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// createTestProject creates a minimal Go project in a temporary directory.
func createTestProject(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()

	// go.mod
	writeFile(t, dir, "go.mod", `module github.com/test/myapp

go 1.22
`)

	// main.go
	writeFile(t, dir, "main.go", `package main

import (
	"fmt"
	"github.com/test/myapp/internal/handler"
)

func main() {
	h := handler.NewOrderHandler()
	fmt.Println(h)
}
`)

	// internal/handler/order_handler.go
	writeFile(t, dir, "internal/handler/order_handler.go", `package handler

import (
	"net/http"
	"github.com/test/myapp/internal/service"
)

// OrderHandler handles HTTP requests for orders.
type OrderHandler struct {
	svc *service.OrderService
}

// NewOrderHandler creates a new OrderHandler.
func NewOrderHandler() *OrderHandler {
	return &OrderHandler{}
}

// Create handles order creation via HTTP POST.
func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
	panic("not implemented")
}

// List returns all orders.
func (h *OrderHandler) List(w http.ResponseWriter, r *http.Request) {
	// no panic here
}
`)

	// internal/service/order_service.go
	writeFile(t, dir, "internal/service/order_service.go", `package service

import "github.com/test/myapp/internal/repository"

// OrderService contains business logic for orders.
type OrderService struct {
	repo *repository.OrderRepository
}

// CreateOrder creates a new order.
func (s *OrderService) CreateOrder(name string) error {
	return nil
}

// FindByID finds an order by its ID.
func (s *OrderService) FindByID(id string) (string, error) {
	return "", nil
}
`)

	// internal/repository/order_repository.go
	writeFile(t, dir, "internal/repository/order_repository.go", `package repository

// OrderRepository handles persistence for orders.
type OrderRepository struct {
	db interface{}
}

// Save persists an order to the database.
func (r *OrderRepository) Save(name string) error {
	return nil
}

// FindByID finds an order by ID.
func (r *OrderRepository) FindByID(id string) (string, error) {
	return "", nil
}
`)

	// internal/model/order.go
	writeFile(t, dir, "internal/model/order.go", `package model

// Order represents a business order.
type Order struct {
	ID    string
	Name  string
	Total float64
}

// OrderStatus is the status of an order.
type OrderStatus string

const (
	OrderStatusPending  OrderStatus = "pending"
	OrderStatusComplete OrderStatus = "complete"
)
`)

	// internal/model/customer.go
	writeFile(t, dir, "internal/model/customer.go", `package model

// Customer represents a customer.
type Customer struct {
	ID   string
	Name string
}
`)

	// internal/config/config.go
	writeFile(t, dir, "internal/config/config.go", `package config

// Config holds application configuration.
type Config struct {
	Port     int
	DBHost   string
	DBPort   int
}

// NewConfig creates a default configuration.
func NewConfig() *Config {
	return &Config{Port: 8080}
}
`)

	return dir
}

func writeFile(t *testing.T, baseDir, relPath, content string) {
	t.Helper()
	fullPath := filepath.Join(baseDir, relPath)
	dir := filepath.Dir(fullPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatalf("failed to create dir %s: %v", dir, err)
	}
	if err := os.WriteFile(fullPath, []byte(content), 0644); err != nil {
		t.Fatalf("failed to write file %s: %v", fullPath, err)
	}
}

func TestAnalyzer_FullProject(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	// Module should be extracted from go.mod
	if result.Module != "github.com/test/myapp" {
		t.Errorf("expected module github.com/test/myapp, got %s",
			result.Module)
	}

	// Should find all packages
	if len(result.Packages) < 5 {
		t.Errorf("expected at least 5 packages, got %d",
			len(result.Packages))
	}

	// Verify JSON output is valid
	data, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		t.Fatalf("JSON encoding failed: %v", err)
	}
	if len(data) == 0 {
		t.Error("JSON output is empty")
	}

	t.Logf("JSON output (%d bytes):\n%s", len(data), string(data))
}

func TestAnalyzer_PackageDiscovery(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	// Build a map of package paths
	pkgMap := make(map[string]*PackageAnalysis)
	for _, pkg := range result.Packages {
		pkgMap[pkg.Path] = pkg
	}

	// Verify expected packages exist
	expectedPkgs := []string{
		"github.com/test/myapp",
		"github.com/test/myapp/internal/handler",
		"github.com/test/myapp/internal/service",
		"github.com/test/myapp/internal/repository",
		"github.com/test/myapp/internal/model",
		"github.com/test/myapp/internal/config",
	}

	for _, exp := range expectedPkgs {
		if _, ok := pkgMap[exp]; !ok {
			t.Errorf("expected package %s not found", exp)
		}
	}
}

func TestAnalyzer_StructExtraction(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	// Find the handler package
	var handlerPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/handler" {
			handlerPkg = pkg
			break
		}
	}

	if handlerPkg == nil {
		t.Fatal("handler package not found")
	}

	// Should have OrderHandler struct
	if len(handlerPkg.Structs) != 1 {
		t.Fatalf("expected 1 struct, got %d", len(handlerPkg.Structs))
	}

	orderHandler := handlerPkg.Structs[0]
	if orderHandler.Name != "OrderHandler" {
		t.Errorf("expected struct OrderHandler, got %s", orderHandler.Name)
	}

	// Should have 2 methods (Create, List)
	if len(orderHandler.Methods) != 2 {
		t.Errorf("expected 2 methods on OrderHandler, got %d",
			len(orderHandler.Methods))
	}

	// Should have 1 field (svc)
	if len(orderHandler.Fields) != 1 {
		t.Errorf("expected 1 field, got %d", len(orderHandler.Fields))
	}

	if orderHandler.Fields[0].Name != "svc" {
		t.Errorf("expected field name 'svc', got '%s'",
			orderHandler.Fields[0].Name)
	}
}

func TestAnalyzer_FunctionExtraction(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	// Find the handler package
	var handlerPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/handler" {
			handlerPkg = pkg
			break
		}
	}

	if handlerPkg == nil {
		t.Fatal("handler package not found")
	}

	// Should have NewOrderHandler as a package-level function
	if len(handlerPkg.Functions) != 1 {
		t.Fatalf("expected 1 package-level function, got %d",
			len(handlerPkg.Functions))
	}

	fn := handlerPkg.Functions[0]
	if fn.Name != "NewOrderHandler" {
		t.Errorf("expected function NewOrderHandler, got %s", fn.Name)
	}

	if fn.Receiver != "" {
		t.Errorf("expected no receiver for NewOrderHandler, got %s",
			fn.Receiver)
	}

	if fn.Doc != "NewOrderHandler creates a new OrderHandler." {
		t.Errorf("unexpected doc: %s", fn.Doc)
	}
}

func TestAnalyzer_PanicDetection(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	var handlerPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/handler" {
			handlerPkg = pkg
			break
		}
	}

	if handlerPkg == nil {
		t.Fatal("handler package not found")
	}

	// Find Create method which has panic()
	var createMethod *FunctionInfo
	for _, m := range handlerPkg.Structs[0].Methods {
		if m.Name == "Create" {
			createMethod = m
			break
		}
	}

	if createMethod == nil {
		t.Fatal("Create method not found")
	}

	if !createMethod.HasPanic {
		t.Error("expected Create to have hasPanic=true")
	}

	// Find List method which does NOT have panic
	var listMethod *FunctionInfo
	for _, m := range handlerPkg.Structs[0].Methods {
		if m.Name == "List" {
			listMethod = m
			break
		}
	}

	if listMethod == nil {
		t.Fatal("List method not found")
	}

	if listMethod.HasPanic {
		t.Error("expected List to have hasPanic=false")
	}
}

func TestAnalyzer_ImportExtraction(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	var handlerPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/handler" {
			handlerPkg = pkg
			break
		}
	}

	if handlerPkg == nil {
		t.Fatal("handler package not found")
	}

	// Handler imports service (internal), not net/http (external)
	foundService := false
	for _, imp := range handlerPkg.Imports {
		if imp == "github.com/test/myapp/internal/service" {
			foundService = true
		}
		// Should NOT contain external imports
		if imp == "net/http" {
			t.Error("should not include external import net/http")
		}
	}

	if !foundService {
		t.Error("expected handler to import internal/service")
	}
}

func TestAnalyzer_EntryPointDetection(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	pkgMap := make(map[string]*PackageAnalysis)
	for _, pkg := range result.Packages {
		pkgMap[pkg.Path] = pkg
	}

	// main package should be entry point
	mainPkg := pkgMap["github.com/test/myapp"]
	if mainPkg == nil {
		t.Fatal("main package not found")
	}
	if !mainPkg.IsEntryPoint {
		t.Error("expected main package to be entry point")
	}

	// config should NOT be entry point
	configPkg := pkgMap["github.com/test/myapp/internal/config"]
	if configPkg == nil {
		t.Fatal("config package not found")
	}
	if configPkg.IsEntryPoint {
		t.Error("expected config package to NOT be entry point")
	}
}

func TestAnalyzer_ClassTypeInference(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	pkgMap := make(map[string]*PackageAnalysis)
	for _, pkg := range result.Packages {
		pkgMap[pkg.Path] = pkg
	}

	tests := map[string]string{
		"github.com/test/myapp/internal/handler":    "CONTROLLER",
		"github.com/test/myapp/internal/service":    "SERVICE",
		"github.com/test/myapp/internal/repository": "REPOSITORY",
		"github.com/test/myapp/internal/model":      "ENTITY",
		"github.com/test/myapp/internal/config":     "CONFIGURATION",
	}

	for pkgPath, expectedType := range tests {
		pkg := pkgMap[pkgPath]
		if pkg == nil {
			t.Errorf("package %s not found", pkgPath)
			continue
		}
		if pkg.ClassType != expectedType {
			t.Errorf("package %s: expected classType %s, got %s",
				pkgPath, expectedType, pkg.ClassType)
		}
	}
}

func TestAnalyzer_ModelPackage(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	var modelPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/model" {
			modelPkg = pkg
			break
		}
	}

	if modelPkg == nil {
		t.Fatal("model package not found")
	}

	// Should have 2 files
	if len(modelPkg.Files) != 2 {
		t.Errorf("expected 2 files in model, got %d", len(modelPkg.Files))
	}

	// Should have 2 structs (Order, Customer)
	if len(modelPkg.Structs) != 2 {
		t.Errorf("expected 2 structs in model, got %d",
			len(modelPkg.Structs))
	}

	structNames := map[string]bool{}
	for _, s := range modelPkg.Structs {
		structNames[s.Name] = true
	}

	if !structNames["Order"] {
		t.Error("expected Order struct in model package")
	}
	if !structNames["Customer"] {
		t.Error("expected Customer struct in model package")
	}
}

func TestAnalyzer_HttpHandlerDetection(t *testing.T) {
	dir := createTestProject(t)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	var handlerPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/handler" {
			handlerPkg = pkg
			break
		}
	}

	if handlerPkg == nil {
		t.Fatal("handler package not found")
	}

	// Create method should be detected as HTTP handler
	createMethod := handlerPkg.Structs[0].Methods[0]
	if createMethod.Name == "List" {
		createMethod = handlerPkg.Structs[0].Methods[1]
	}

	if createMethod.HTTPMethod == "" {
		t.Error("expected Create to be detected as HTTP handler")
	}
}

func TestAnalyzer_ExcludesTestFiles(t *testing.T) {
	dir := createTestProject(t)

	// Add a test file
	writeFile(t, dir, "internal/service/order_service_test.go", `package service

import "testing"

func TestCreateOrder(t *testing.T) {
	// test
}
`)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	// Find service package
	var servicePkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/service" {
			servicePkg = pkg
			break
		}
	}

	if servicePkg == nil {
		t.Fatal("service package not found")
	}

	// Should NOT include test file
	for _, f := range servicePkg.Files {
		if f == "order_service_test.go" {
			t.Error("should not include test files")
		}
	}
}

func TestAnalyzer_ExcludesGeneratedFiles(t *testing.T) {
	dir := createTestProject(t)

	// Add a generated file
	writeFile(t, dir, "internal/model/order.pb.go", `package model
// Code generated by protoc-gen-go. DO NOT EDIT.
type OrderProto struct {}
`)

	analyzer := NewAnalyzer(dir)
	result, err := analyzer.Analyze()
	if err != nil {
		t.Fatalf("Analyze() failed: %v", err)
	}

	var modelPkg *PackageAnalysis
	for _, pkg := range result.Packages {
		if pkg.Path == "github.com/test/myapp/internal/model" {
			modelPkg = pkg
			break
		}
	}

	if modelPkg == nil {
		t.Fatal("model package not found")
	}

	for _, f := range modelPkg.Files {
		if f == "order.pb.go" {
			t.Error("should not include generated .pb.go files")
		}
	}
}
