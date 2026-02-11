-- V8: Drop unused dependencies column from source_methods,
--     add commit_hash to source_classes for change tracking.

ALTER TABLE source_methods DROP COLUMN IF EXISTS dependencies;

ALTER TABLE source_classes ADD COLUMN commit_hash VARCHAR(64);
