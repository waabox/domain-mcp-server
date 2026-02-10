-- V4: Optimize indexes based on EXPLAIN ANALYZE results
-- Dataset tested: 100 projects, 1M classes, 5M methods

-- ============================================================================
-- PROBLEM 1: FIND_HTTP_ENDPOINTS_BY_PROJECT_ID takes 972ms
-- Root cause: No efficient way to filter http_method IS NOT NULL AND http_path IS NOT NULL
-- Solution: Partial composite index for endpoint queries
-- ============================================================================

-- Index for finding HTTP endpoints efficiently
-- Covers: FIND_HTTP_ENDPOINTS_BY_PROJECT_ID, COUNT_ENDPOINTS_BY_PROJECT_ID
CREATE INDEX idx_source_methods_http_endpoints
    ON source_methods(class_id, http_path, http_method)
    WHERE http_method IS NOT NULL AND http_path IS NOT NULL;

-- ============================================================================
-- PROBLEM 2: FIND_BY_PACKAGE_PREFIX takes 435ms with Parallel Seq Scan
-- Root cause: OR condition with LIKE pattern prevents index usage
-- Solution: Text pattern index for LIKE prefix queries
-- ============================================================================

-- Index for package prefix LIKE queries (text_pattern_ops enables prefix matching)
CREATE INDEX idx_source_classes_package_pattern
    ON source_classes(package_name text_pattern_ops);

-- ============================================================================
-- PROBLEM 3: FIND_BY_PROJECT_ID sorting 10,000 rows in memory
-- Solution: Composite index with full_class_name for ORDER BY
-- ============================================================================

-- Drop redundant index (project_id alone is covered by project_package)
DROP INDEX IF EXISTS idx_source_classes_project;

-- Covering index for project queries with ORDER BY full_class_name
CREATE INDEX idx_source_classes_project_ordered
    ON source_classes(project_id, full_class_name);
