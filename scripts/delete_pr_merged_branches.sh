#!/bin/bash
# Delete branches that have "Merge pull request" in their last commit
# These are branches that were used to create PRs and have been merged

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}🔍 Finding branches with merged pull requests...${NC}"
echo ""

# Branches that have "Merge pull request" in their last commit message
BRANCHES_TO_DELETE=(
    "codex/fix-missing-download-button-on-home-screen"
    "codex/fix-missing-imports-in-homescreen"
    "codex/add-comprehensive-test-suite-for-storenamevalidator"
    "codex/implement-ai-guardrails-and-monitoring"
)

echo -e "${YELLOW}Branches to delete (merged via PR):${NC}"
for branch in "${BRANCHES_TO_DELETE[@]}"; do
    echo "  - $branch"
done
echo ""

read -p "Delete these branches? (yes/no): " confirm

if [[ "$confirm" != "yes" ]]; then
    echo "Cancelled"
    exit 0
fi

echo ""
echo -e "${BLUE}🗑️  Deleting branches...${NC}"

for branch in "${BRANCHES_TO_DELETE[@]}"; do
    echo -n "Deleting: ${branch}... "
    if git push origin --delete "$branch" 2>/dev/null; then
        echo -e "${GREEN}✓${NC}"
    else
        echo "Already deleted or doesn't exist"
    fi
done

echo ""
echo -e "${GREEN}✅ Done!${NC}"
echo ""
echo "Remaining branches:"
git branch -r | grep "origin/codex/"

