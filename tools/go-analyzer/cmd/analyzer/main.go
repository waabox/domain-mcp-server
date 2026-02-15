// Command analyzer performs static analysis on a Go project and outputs
// the result as JSON to stdout. It is intended to be called from the
// Java-side GoSourceParser via ProcessBuilder.
//
// Usage:
//
//	go-analyzer /path/to/project
//	go-analyzer -o output.json /path/to/project
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"github.com/fanki/go-analyzer/pkg/analysis"
)

func main() {
	outputFile := flag.String("o", "", "output file path (default: stdout)")
	flag.Parse()

	if flag.NArg() < 1 {
		fmt.Fprintf(os.Stderr, "Usage: go-analyzer [-o output.json] <project-root>\n")
		os.Exit(1)
	}

	projectRoot := flag.Arg(0)

	// Verify the project root exists and has go.mod
	if _, err := os.Stat(projectRoot); os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "ERROR: project root does not exist: %s\n", projectRoot)
		os.Exit(1)
	}

	analyzer := analysis.NewAnalyzer(projectRoot)
	result, err := analyzer.Analyze()
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: analysis failed: %v\n", err)
		os.Exit(1)
	}

	data, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "ERROR: JSON encoding failed: %v\n", err)
		os.Exit(1)
	}

	if *outputFile != "" {
		if err := os.WriteFile(*outputFile, data, 0644); err != nil {
			fmt.Fprintf(os.Stderr, "ERROR: writing output file: %v\n", err)
			os.Exit(1)
		}
		fmt.Fprintf(os.Stderr, "Analysis complete: %s\n", *outputFile)
	} else {
		fmt.Println(string(data))
	}
}
