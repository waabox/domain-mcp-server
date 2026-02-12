import type {
  FileAnalysisInput,
  FileAnalysisOutput,
  FrameworkInfo,
} from './types';
import { detectFramework } from './detector';
import { extractFileData } from './extractor';

/**
 * Per-file analysis entry point called from GraalJS.
 *
 * Receives a single file's content and metadata, parses it with
 * Babel AST, and returns raw extraction results. Cross-file
 * resolution (dependencies, parameter types) is handled in Java.
 */
function analyzeFile(inputJson: string): string {
  const input: FileAnalysisInput = JSON.parse(inputJson);

  const extracted = extractFileData(
    input.content,
    input.filePath,
    input.frameworkName,
  );

  const output: FileAnalysisOutput = {
    methods: extracted.methods,
    imports: extracted.imports,
    classType: extracted.classType,
    entryPoint: extracted.entryPoint,
  };

  return JSON.stringify(output);
}

/**
 * Framework detection entry point called from GraalJS.
 *
 * Receives the package.json content and returns framework info.
 */
function detectFrameworkFromPackageJson(packageJson: string): string {
  const framework: FrameworkInfo = detectFramework(packageJson);
  return JSON.stringify(framework);
}

// Expose to GraalJS
(globalThis as any).analyzeFile = analyzeFile;
(globalThis as any).detectFrameworkFromPackageJson =
    detectFrameworkFromPackageJson;
