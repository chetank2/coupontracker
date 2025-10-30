#!/bin/bash
# Script to safely delete merged and duplicate branches
# Usage: ./scripts/cleanup_merged_branches.sh [--dry-run]

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

DRY_RUN=false
if [[ "$1" == "--dry-run" ]]; then
    DRY_RUN=true
    echo -e "${YELLOW}🔍 DRY RUN MODE - No branches will be deleted${NC}"
fi

echo -e "${BLUE}🌿 Branch Cleanup Script${NC}"
echo "================================"
echo ""

# Fetch latest and prune
echo -e "${BLUE}📥 Fetching latest from remote and pruning...${NC}"
git fetch --prune --all

# Function to check if branch is merged into main
is_merged_to_main() {
    local branch=$1
    git branch -r --merged origin/main | grep -q "^[[:space:]]*${branch}$"
}

# Function to check if branch has unique commits
has_unique_commits() {
    local branch1=$1
    local branch2=$2
    local count=$(git log ${branch2}..${branch1} --oneline 2>/dev/null | wc -l)
    [[ $count -gt 0 ]]
}

# Function to delete remote branch
delete_branch() {
    local branch=$1
    local reason=$2
    
    if [[ "$DRY_RUN" == true ]]; then
        echo -e "${YELLOW}  [DRY RUN] Would delete: ${branch} (${reason})${NC}"
    else
        echo -e "${GREEN}  ✓ Deleting: ${branch} (${reason})${NC}"
        git push origin --delete "${branch}" 2>/dev/null || echo -e "${RED}    ✗ Failed to delete ${branch}${NC}"
    fi
}

# Count variables
MERGED_COUNT=0
DUPLICATE_COUNT=0
STALE_COUNT=0

echo ""
echo -e "${BLUE}🔍 Phase 1: Identifying merged branches...${NC}"
echo "-------------------------------------------"

# List all remote branches except main and feature branches
BRANCHES=$(git branch -r | grep "origin/codex/" | sed 's/origin\///' | sed 's/^[[:space:]]*//')

for branch in $BRANCHES; do
    if is_merged_to_main "origin/${branch}"; then
        echo -e "${GREEN}Found merged branch: ${branch}${NC}"
        delete_branch "${branch}" "merged into main"
        ((MERGED_COUNT++))
    fi
done

echo ""
echo -e "${BLUE}🔍 Phase 2: Identifying duplicate branches...${NC}"
echo "----------------------------------------------"

# Known duplicate patterns
DUPLICATE_PATTERNS=(
    "remove-all-offertext-references"
    "develop-plan-for-coupon-data-extraction"
    "fix-twostagedetector-and-database-issues"
    "fix-incomplete-description-fetch-issue"
    "review-smartcoupon-extraction-plan"
    "find-discrepancies-in-coupon-extraction"
    "fix-high-priority-bug-in-storenamemetricstracker"
)

for pattern in "${DUPLICATE_PATTERNS[@]}"; do
    echo ""
    echo -e "${YELLOW}Checking pattern: ${pattern}${NC}"
    
    # Find all branches matching this pattern
    MATCHING_BRANCHES=$(git branch -r | grep "origin/codex/${pattern}" | sed 's/origin\///' | sed 's/^[[:space:]]*//' || true)
    
    if [[ -z "$MATCHING_BRANCHES" ]]; then
        continue
    fi
    
    # Count matching branches
    BRANCH_COUNT=$(echo "$MATCHING_BRANCHES" | wc -l | tr -d ' ')
    
    if [[ $BRANCH_COUNT -gt 1 ]]; then
        echo -e "${YELLOW}  Found ${BRANCH_COUNT} branches matching '${pattern}'${NC}"
        
        # Find the canonical branch (without suffix)
        CANONICAL="codex/${pattern}"
        
        # Check if canonical exists
        if echo "$MATCHING_BRANCHES" | grep -q "^${CANONICAL}$"; then
            echo -e "${GREEN}  Canonical branch exists: ${CANONICAL}${NC}"
            
            # Delete all others if they have no unique commits
            while IFS= read -r branch; do
                if [[ "$branch" != "$CANONICAL" ]]; then
                    if has_unique_commits "origin/${branch}" "origin/${CANONICAL}"; then
                        echo -e "${YELLOW}  ⚠️  ${branch} has unique commits - SKIPPING${NC}"
                    else
                        echo -e "${GREEN}  ${branch} has no unique commits${NC}"
                        delete_branch "${branch}" "duplicate of ${CANONICAL}"
                        ((DUPLICATE_COUNT++))
                    fi
                fi
            done <<< "$MATCHING_BRANCHES"
        else
            echo -e "${YELLOW}  No canonical branch found, keeping all for manual review${NC}"
        fi
    fi
done

echo ""
echo -e "${BLUE}🔍 Phase 3: Identifying stale branches (90+ days)...${NC}"
echo "----------------------------------------------------"

# Get branches older than 90 days
STALE_BRANCHES=$(python3 scripts/generate_branch_report.py | grep "Stale" | awk -F'|' '{print $2}' | tr -d ' `' || true)

if [[ -n "$STALE_BRANCHES" ]]; then
    while IFS= read -r branch; do
        if [[ -n "$branch" && "$branch" != "origin/main" && "$branch" != "origin/gh-pages" ]]; then
            BRANCH_NAME=$(echo "$branch" | sed 's/origin\///')
            echo -e "${YELLOW}Found stale branch: ${BRANCH_NAME}${NC}"
            
            # Archive stale branches instead of deleting
            ARCHIVE_NAME="archive/$(date +%Y-%m-%d)/${BRANCH_NAME}"
            
            if [[ "$DRY_RUN" == true ]]; then
                echo -e "${YELLOW}  [DRY RUN] Would archive to: ${ARCHIVE_NAME}${NC}"
            else
                echo -e "${GREEN}  ✓ Archiving to: ${ARCHIVE_NAME}${NC}"
                git checkout -b "${ARCHIVE_NAME}" "origin/${BRANCH_NAME}" 2>/dev/null || true
                git push origin "${ARCHIVE_NAME}" 2>/dev/null || true
                delete_branch "${BRANCH_NAME}" "archived to ${ARCHIVE_NAME}"
            fi
            ((STALE_COUNT++))
        fi
    done <<< "$STALE_BRANCHES"
else
    echo -e "${GREEN}No stale branches found!${NC}"
fi

echo ""
echo -e "${BLUE}📊 Summary${NC}"
echo "================================"
echo -e "Merged branches: ${GREEN}${MERGED_COUNT}${NC}"
echo -e "Duplicate branches: ${GREEN}${DUPLICATE_COUNT}${NC}"
echo -e "Stale branches: ${GREEN}${STALE_COUNT}${NC}"
echo -e "Total cleaned: ${GREEN}$((MERGED_COUNT + DUPLICATE_COUNT + STALE_COUNT))${NC}"
echo ""

if [[ "$DRY_RUN" == true ]]; then
    echo -e "${YELLOW}This was a dry run. Run without --dry-run to actually delete branches.${NC}"
else
    echo -e "${GREEN}✅ Cleanup complete!${NC}"
    echo ""
    echo "Updating cleanup log..."
    
    # Update cleanup log
    {
        echo ""
        echo "## $(date +%Y-%m-%d)"
        echo ""
        echo "- Ran automated branch cleanup script"
        echo "- Deleted ${MERGED_COUNT} merged branches"
        echo "- Deleted ${DUPLICATE_COUNT} duplicate branches"
        echo "- Archived ${STALE_COUNT} stale branches"
        echo "- Total branches cleaned: $((MERGED_COUNT + DUPLICATE_COUNT + STALE_COUNT))"
    } >> BRANCH_CLEANUP_LOG.md
    
    echo -e "${GREEN}✅ Updated BRANCH_CLEANUP_LOG.md${NC}"
fi

echo ""
echo -e "${BLUE}Next steps:${NC}"
echo "1. Review the changes"
echo "2. Run 'python3 scripts/generate_branch_report.py' to see updated status"
echo "3. Update docs/branch_inventory.md if needed"

