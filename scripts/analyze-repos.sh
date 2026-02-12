#!/usr/bin/env bash
#
# Analyzes a list of git repositories sequentially via the REST API.
#
# Usage:
#   ./scripts/analyze-repos.sh repos.txt
#   ./scripts/analyze-repos.sh repos.txt main
#   ./scripts/analyze-repos.sh repos.txt main http://localhost:8080
#
# repos.txt format (one entry per line, branch is optional):
#   git@github.com:fanki/order-service.git
#   git@github.com:fanki/payment-service.git develop
#   git@gitlab.com:fanki/billing-service.git master
#
# If a line contains a branch, it overrides the default branch argument.
#

set -euo pipefail

REPOS_FILE="${1:?Usage: $0 <repos-file> [branch] [base-url]}"
BRANCH="${2:-main}"
BASE_URL="${3:-http://localhost:8080}"

if [[ ! -f "$REPOS_FILE" ]]; then
  echo "Error: file not found: $REPOS_FILE"
  exit 1
fi

TOTAL=$(grep -cve '^\s*$\|^\s*#' "$REPOS_FILE" || true)
CURRENT=0
SUCCEEDED=0
FAILED=0

echo "=== Analyzing $TOTAL repositories (branch: $BRANCH) ==="
echo ""

while IFS= read -r line; do
  # Skip empty lines and comments
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue

  # Split line into URL and optional branch
  read -r repo line_branch <<< "$line"
  REPO_BRANCH="${line_branch:-$BRANCH}"

  CURRENT=$((CURRENT + 1))
  echo "[$CURRENT/$TOTAL] Analyzing: $repo"
  echo "         Branch: $REPO_BRANCH"

  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/projects/analyze" \
    -H "Content-Type: application/json" \
    -d "{
      \"repositoryUrl\": \"$repo\",
      \"branch\": \"$REPO_BRANCH\",
      \"fixMissed\": true
    }")

  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  BODY=$(echo "$RESPONSE" | sed '$d')

  if [[ "$HTTP_CODE" -ge 200 && "$HTTP_CODE" -lt 300 ]]; then
    CLASSES=$(echo "$BODY" | grep -o '"classesAnalyzed":[0-9]*' | cut -d: -f2 || echo "?")
    ENDPOINTS=$(echo "$BODY" | grep -o '"endpointsFound":[0-9]*' | cut -d: -f2 || echo "?")
    echo "         OK ($HTTP_CODE) - $CLASSES classes, $ENDPOINTS endpoints"
    SUCCEEDED=$((SUCCEEDED + 1))
  else
    MESSAGE=$(echo "$BODY" | grep -o '"message":"[^"]*"' | cut -d: -f2- | tr -d '"' || echo "$BODY")
    echo "         FAILED ($HTTP_CODE) - $MESSAGE"
    FAILED=$((FAILED + 1))
  fi

  echo ""

done < "$REPOS_FILE"

echo "=== Done ==="
echo "    Succeeded: $SUCCEEDED"
echo "    Failed:    $FAILED"
echo "    Total:     $TOTAL"

exit $FAILED
