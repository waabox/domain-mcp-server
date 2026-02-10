-- Add indexes to optimize Datadog stack trace correlation queries
-- These indexes avoid full table scans when searching by class/method name

-- Composite index for searching methods within a class
CREATE INDEX idx_source_methods_class_name ON source_methods(class_id, method_name);

-- Index on simple class name for partial matches
CREATE INDEX idx_source_classes_simple_name ON source_classes(simple_name);

-- Index for class type filtering (e.g., find all controllers)
CREATE INDEX idx_source_classes_type ON source_classes(class_type);

-- Composite index for project + package queries
CREATE INDEX idx_source_classes_project_package ON source_classes(project_id, package_name);

-- Index for method line number lookups (stack traces include line numbers)
CREATE INDEX idx_source_methods_line ON source_methods(line_number) WHERE line_number IS NOT NULL;
