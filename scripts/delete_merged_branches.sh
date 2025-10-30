#!/bin/bash
# Delete branches that have been merged into feature/qwen-multi-coupon-extraction
# Usage: ./scripts/delete_merged_branches.sh [--dry-run] [--batch-size N]

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DRY_RUN=false
BATCH_SIZE=10
INTERACTIVE=true

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --batch-size)
            BATCH_SIZE="$2"
            shift 2
            ;;
        --yes|-y)
            INTERACTIVE=false
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--dry-run] [--batch-size N] [--yes]"
            exit 1
            ;;
    esac
done

if [[ "$DRY_RUN" == true ]]; then
    echo -e "${YELLOW}🔍 DRY RUN MODE - No branches will be deleted${NC}"
fi

echo -e "${BLUE}🌿 Delete Merged Branches Script${NC}"
echo "================================"
echo ""

# Fetch latest
echo -e "${BLUE}📥 Fetching latest from remote...${NC}"
git fetch --prune --all

# Protected branches that should never be deleted
PROTECTED_BRANCHES=(
    "origin/main"
    "origin/feature/qwen-multi-coupon-extraction"
    "origin/gh-pages"
)

# Get branches merged into feature branch
echo -e "${BLUE}🔍 Finding branches merged into feature/qwen-multi-coupon-extraction...${NC}"
MERGED_BRANCHES=$(git branch -r --merged origin/feature/qwen-multi-coupon-extraction | \
    grep "origin/codex/" | \
    sed 's/^[[:space:]]*//' | \
    sed 's/origin\///')

# Count total
TOTAL_COUNT=$(echo "$MERGED_BRANCHES" | wc -l | tr -d ' ')
echo -e "${GREEN}Found ${TOTAL_COUNT} branches merged into feature branch${NC}"
echo ""

# Filter out protected branches
BRANCHES_TO_DELETE=()
while IFS= read -r branch; do
    if [[ -n "$branch" ]]; then
        # Check if protected
        IS_PROTECTED=false
        for protected in "${PROTECTED_BRANCHES[@]}"; do
            if [[ "origin/$branch" == "$protected" ]]; then
                IS_PROTECTED=true
                break
            fi
        done
        
        if [[ "$IS_PROTECTED" == false ]]; then
            BRANCHES_TO_DELETE+=("$branch")
        fi
    fi
done <<< "$MERGED_BRANCHES"

DELETE_COUNT=${#BRANCHES_TO_DELETE[@]}
echo -e "${YELLOW}Branches to delete: ${DELETE_COUNT}${NC}"
echo ""

if [[ $DELETE_COUNT -eq 0 ]]; then
    echo -e "${GREEN}✅ No branches to delete!${NC}"
    exit 0
fi

# Show first 20 branches
echo -e "${BLUE}Preview (first 20 branches):${NC}"
echo "----------------------------"
for i in "${!BRANCHES_TO_DELETE[@]}"; do
    if [[ $i -lt 20 ]]; then
        echo "  - ${BRANCHES_TO_DELETE[$i]}"
    fi
done

if [[ $DELETE_COUNT -gt 20 ]]; then
    echo "  ... and $((DELETE_COUNT - 20)) more"
fi
echo ""

# Confirm if interactive
if [[ "$INTERACTIVE" == true && "$DRY_RUN" == false ]]; then
    echo -e "${YELLOW}⚠️  This will delete ${DELETE_COUNT} remote branches!${NC}"
    read -p "Continue? (yes/no): " confirm
    
    if [[ "$confirm" != "yes" ]]; then
        echo -e "${RED}❌ Cancelled${NC}"
        exit 0
    fi
    echo ""
fi

# Delete branches in batches
DELETED=0
FAILED=0
BATCH_NUM=1

echo -e "${BLUE}🗑️  Starting deletion...${NC}"
echo ""

for branch in "${BRANCHES_TO_DELETE[@]}"; do
    if [[ "$DRY_RUN" == true ]]; then
        echo -e "${YELLOW}[DRY RUN] Would delete: ${branch}${NC}"
        ((DELETED++))
    else
        echo -n "Deleting: ${branch}... "
        if git push origin --delete "$branch" 2>/dev/null; then
            echo -e "${GREEN}✓${NC}"
            ((DELETED++))
        else
            echo -e "${RED}✗ Failed${NC}"
            ((FAILED++))
        fi
    fi
    
    # Pause between batches
    if [[ $((DELETED % BATCH_SIZE)) -eq 0 && $DELETED -lt $DELETE_COUNT ]]; then
        echo ""
        echo -e "${BLUE}Batch ${BATCH_NUM} complete (${DELETED}/${DELETE_COUNT})${NC}"
        if [[ "$INTERACTIVE" == true && "$DRY_RUN" == false ]]; then
            read -p "Press Enter to continue with next batch..."
        else
            sleep 1
        fi
        echo ""
        ((BATCH_NUM++))
    fi
done

echo ""
echo -e "${BLUE}📊 Summary${NC}"
echo "================================"
echo -e "Total branches found: ${TOTAL_COUNT}"
echo -e "Branches deleted: ${GREEN}${DELETED}${NC}"
if [[ $FAILED -gt 0 ]]; then
    echo -e "Failed deletions: ${RED}${FAILED}${NC}"
fi
echo ""

if [[ "$DRY_RUN" == false ]]; then
    # Update cleanup log
    {
        echo ""
        echo "## $(date +%Y-%m-%d) - Automated Cleanup"
        echo ""
        echo "- Deleted ${DELETED} branches merged into feature/qwen-multi-coupon-extraction"
        if [[ $FAILED -gt 0 ]]; then
            echo "- Failed to delete ${FAILED} branches"
        fi
        echo "- Remaining active branches: $(git branch -r | grep "origin/codex/" | wc -l | tr -d ' ')"
    } >> BRANCH_CLEANUP_LOG.md
    
    echo -e "${GREEN}✅ Updated BRANCH_CLEANUP_LOG.md${NC}"
    echo ""
    
    # Generate new report
    echo -e "${BLUE}📋 Generating updated branch report...${NC}"
    python3 scripts/generate_branch_report.py > branch_report_after_cleanup_$(date +%Y%m%d).md
    echo -e "${GREEN}✅ Report saved to: branch_report_after_cleanup_$(date +%Y%m%d).md${NC}"
else
    echo -e "${YELLOW}This was a dry run. Run without --dry-run to actually delete branches.${NC}"
fi

echo ""
echo -e "${GREEN}✅ Done!${NC}"

