-- Domain MCP Server Initial Schema
-- Version: 1
-- Description: Creates tables for projects, analysis results, extracted business information,
--              and source code context for Datadog stack trace correlation

-- Create schema
CREATE SCHEMA IF NOT EXISTS domain_mcp;

-- Set search path for this migration
SET search_path TO domain_mcp;

-- Projects table - stores registered git repositories
CREATE TABLE projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    repository_url VARCHAR(1024) NOT NULL UNIQUE,
    clone_location VARCHAR(1024),
    default_branch VARCHAR(255) DEFAULT 'main',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    last_analyzed_at TIMESTAMP WITH TIME ZONE,
    last_commit_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_name ON projects(name);

-- Analysis results table - stores analysis metadata
CREATE TABLE analysis_results (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    commit_hash VARCHAR(64) NOT NULL,
    analysis_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    summary TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_analysis_results_project_id ON analysis_results(project_id);
CREATE INDEX idx_analysis_results_status ON analysis_results(status);

-- Business rules table - extracted business rules from code
CREATE TABLE business_rules (
    id VARCHAR(36) PRIMARY KEY,
    analysis_result_id VARCHAR(36) NOT NULL REFERENCES analysis_results(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    source_file VARCHAR(1024),
    source_line INTEGER,
    domain_context VARCHAR(255),
    rule_type VARCHAR(100),
    confidence_score DECIMAL(3,2),
    raw_code TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_business_rules_analysis_id ON business_rules(analysis_result_id);
CREATE INDEX idx_business_rules_domain ON business_rules(domain_context);

-- API endpoints table - extracted REST/GraphQL endpoints
CREATE TABLE api_endpoints (
    id VARCHAR(36) PRIMARY KEY,
    analysis_result_id VARCHAR(36) NOT NULL REFERENCES analysis_results(id) ON DELETE CASCADE,
    http_method VARCHAR(10) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    description TEXT,
    controller_class VARCHAR(512),
    method_name VARCHAR(255),
    request_body_type VARCHAR(512),
    response_type VARCHAR(512),
    path_parameters JSONB,
    query_parameters JSONB,
    headers JSONB,
    source_file VARCHAR(1024),
    source_line INTEGER,
    business_logic TEXT,
    service_calls JSONB,
    repository_calls JSONB,
    external_calls JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_endpoints_analysis_id ON api_endpoints(analysis_result_id);
CREATE INDEX idx_api_endpoints_path ON api_endpoints(path);

-- Contracts table - API contracts and DTOs
CREATE TABLE contracts (
    id VARCHAR(36) PRIMARY KEY,
    analysis_result_id VARCHAR(36) NOT NULL REFERENCES analysis_results(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    contract_type VARCHAR(50) NOT NULL,
    full_class_name VARCHAR(512),
    fields JSONB,
    validations JSONB,
    description TEXT,
    source_file VARCHAR(1024),
    source_line INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_contracts_analysis_id ON contracts(analysis_result_id);
CREATE INDEX idx_contracts_type ON contracts(contract_type);

-- Database schemas table - extracted entity/table definitions
CREATE TABLE database_schemas (
    id VARCHAR(36) PRIMARY KEY,
    analysis_result_id VARCHAR(36) NOT NULL REFERENCES analysis_results(id) ON DELETE CASCADE,
    entity_name VARCHAR(255) NOT NULL,
    table_name VARCHAR(255),
    full_class_name VARCHAR(512),
    columns JSONB,
    relationships JSONB,
    indexes JSONB,
    constraints JSONB,
    description TEXT,
    source_file VARCHAR(1024),
    source_line INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_database_schemas_analysis_id ON database_schemas(analysis_result_id);
CREATE INDEX idx_database_schemas_table ON database_schemas(table_name);

-- Source files table - tracks analyzed source files
CREATE TABLE source_files (
    id VARCHAR(36) PRIMARY KEY,
    analysis_result_id VARCHAR(36) NOT NULL REFERENCES analysis_results(id) ON DELETE CASCADE,
    file_path VARCHAR(1024) NOT NULL,
    file_type VARCHAR(50),
    language VARCHAR(50),
    package_name VARCHAR(512),
    class_name VARCHAR(255),
    file_hash VARCHAR(64),
    line_count INTEGER,
    analyzed BOOLEAN DEFAULT FALSE,
    analysis_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_source_files_analysis_id ON source_files(analysis_result_id);
CREATE INDEX idx_source_files_path ON source_files(file_path);
CREATE INDEX idx_source_files_language ON source_files(language);

-- Source classes table - extracted class information for Datadog stack trace correlation
CREATE TABLE source_classes (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    full_class_name VARCHAR(500) NOT NULL,
    simple_name VARCHAR(200) NOT NULL,
    package_name VARCHAR(400),
    class_type VARCHAR(50) NOT NULL,
    description TEXT,
    source_file VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_source_classes_project_class UNIQUE(project_id, full_class_name)
);

CREATE INDEX idx_source_classes_full_name ON source_classes(full_class_name);
CREATE INDEX idx_source_classes_package ON source_classes(package_name);
CREATE INDEX idx_source_classes_project ON source_classes(project_id);

-- Source methods table - extracted method information for stack trace correlation
CREATE TABLE source_methods (
    id VARCHAR(36) PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL REFERENCES source_classes(id) ON DELETE CASCADE,
    method_name VARCHAR(200) NOT NULL,
    description TEXT,
    business_logic TEXT,
    dependencies TEXT,
    exceptions TEXT,
    http_method VARCHAR(10),
    http_path VARCHAR(500),
    line_number INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_source_methods_class ON source_methods(class_id);
CREATE INDEX idx_source_methods_name ON source_methods(method_name);
CREATE INDEX idx_source_methods_http_path ON source_methods(http_path);

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Trigger for projects table
CREATE TRIGGER update_projects_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
