/** Input passed from Java to the analyzer. */
export interface ProjectInput {
  packageJson: string;
  files: FileInput[];
}

export interface FileInput {
  path: string;
  content: string;
}

/** Output returned from the analyzer to Java. */
export interface ProjectOutput {
  framework: FrameworkInfo;
  files: FileAnalysis[];
}

export interface FrameworkInfo {
  name: string;
  sourceRoot: string;
  features: Record<string, string>;
}

export interface FileAnalysis {
  path: string;
  identifier: string;
  classType: string;
  entryPoint: boolean;
  dependencies: string[];
  methods: MethodAnalysis[];
  methodParameters: Record<string, string[]>;
}

export interface MethodAnalysis {
  name: string;
  lineNumber: number;
  httpMethod: string | null;
  httpPath: string | null;
  parameterTypes: string[];
}

/** Per-file analysis input for the per-file API. */
export interface FileAnalysisInput {
  content: string;
  filePath: string;
  frameworkName: string;
}

/** Per-file analysis output from the per-file API. */
export interface FileAnalysisOutput {
  methods: MethodAnalysis[];
  imports: ImportInfo[];
  classType: string;
  entryPoint: boolean;
}

export interface ImportInfo {
  importedName: string;
  localName: string;
  source: string;
}
