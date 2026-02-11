SET search_path TO domain_mcp;

-- Method parameters linking source_methods to source_classes.
-- Captures parameters whose type is a known project class, enriching
-- the dependency graph with method-level connectivity.
CREATE TABLE method_parameters (
    id VARCHAR(36) PRIMARY KEY,
    method_id VARCHAR(36) NOT NULL REFERENCES source_methods(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    class_id VARCHAR(36) NOT NULL REFERENCES source_classes(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_method_param_position UNIQUE(method_id, position)
);

-- Used by: findByMethodId (MethodParameterRepository)
CREATE INDEX idx_method_params_method ON method_parameters(method_id);

-- Used by: deleteByClassId (MethodParameterRepository)
CREATE INDEX idx_method_params_class ON method_parameters(class_id);
