-- Remove unused tables from initial schema
-- These were planned but not implemented
-- Drop in correct order due to foreign key dependencies

DROP TABLE IF EXISTS business_rules;
DROP TABLE IF EXISTS api_endpoints;
DROP TABLE IF EXISTS contracts;
DROP TABLE IF EXISTS database_schemas;
DROP TABLE IF EXISTS source_files;
DROP TABLE IF EXISTS analysis_results;
