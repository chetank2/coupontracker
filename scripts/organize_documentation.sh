#!/bin/bash
# Organize documentation files from root directory into docs/
# This script categorizes and moves markdown files to appropriate subdirectories

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

DRY_RUN=false
if [[ "$1" == "--dry-run" ]]; then
    DRY_RUN=true
    echo -e "${YELLOW}🔍 DRY RUN MODE - No files will be moved${NC}"
fi

echo -e "${BLUE}📁 Documentation Organization Script${NC}"
echo "===================================="
echo ""

# Create directory structure
echo -e "${BLUE}📂 Creating directory structure...${NC}"

DIRS=(
    "docs/archive/implementation"
    "docs/archive/architecture"
    "docs/archive/fixes"
    "docs/archive/testing"
    "docs/archive/releases"
    "docs/archive/sessions"
)

for dir in "${DIRS[@]}"; do
    if [[ "$DRY_RUN" == false ]]; then
        mkdir -p "$dir"
        echo "  ✓ Created: $dir"
    else
        echo "  [DRY RUN] Would create: $dir"
    fi
done

echo ""

# Function to move file
move_file() {
    local file=$1
    local dest=$2
    
    if [[ "$DRY_RUN" == true ]]; then
        echo -e "${YELLOW}  [DRY RUN] Would move: $file -> $dest${NC}"
    else
        git mv "$file" "$dest" 2>/dev/null || mv "$file" "$dest"
        echo -e "${GREEN}  ✓ Moved: $file -> $dest${NC}"
    fi
}

# Count files
MOVED_COUNT=0

echo -e "${BLUE}📋 Organizing files...${NC}"
echo ""

# Architecture files
echo -e "${YELLOW}Architecture & Design:${NC}"
for file in *ARCHITECTURE*.md *DIAGRAM*.md *FLOW*.md V2_*.md; do
    if [[ -f "$file" ]]; then
        move_file "$file" "docs/archive/architecture/"
        ((MOVED_COUNT++))
    fi
done

# Implementation files
echo ""
echo -e "${YELLOW}Implementation & Progress:${NC}"
for file in *IMPLEMENTATION*.md *PROGRESS*.md *PLAN*.md *TRACKER*.md *GUIDE*.md *ROADMAP*.md; do
    if [[ -f "$file" ]]; then
        move_file "$file" "docs/archive/implementation/"
        ((MOVED_COUNT++))
    fi
done

# Fix/Bug files
echo ""
echo -e "${YELLOW}Fixes & Debugging:${NC}"
for file in *FIX*.md *BUG*.md *CRITICAL*.md *ISSUE*.md *DIAGNOSIS*.md *ROOT_CAUSE*.md *ANALYSIS*.md; do
    if [[ -f "$file" ]]; then
        move_file "$file" "docs/archive/fixes/"
        ((MOVED_COUNT++))
    fi
done

# Testing files
echo ""
echo -e "${YELLOW}Testing & Validation:${NC}"
for file in *TEST*.md *TESTING*.md *VERIFICATION*.md; do
    if [[ -f "$file" ]]; then
        move_file "$file" "docs/archive/testing/"
        ((MOVED_COUNT++))
    fi
done

# Release files
echo ""
echo -e "${YELLOW}Releases & Delivery:${NC}"
for file in RELEASE_NOTES*.md *DELIVERY*.md *COMPLETE*.md *SUMMARY*.md; do
    if [[ -f "$file" ]]; then
        move_file "$file" "docs/archive/releases/"
        ((MOVED_COUNT++))
    fi
done

# Session files
echo ""
echo -e "${YELLOW}Session Notes:${NC}"
for file in SESSION*.md *STATUS*.md DONE.md FINISH*.md PHASE*.md; do
    if [[ -f "$file" ]]; then
        move_file "$file" "docs/archive/sessions/"
        ((MOVED_COUNT++))
    fi
done

# Specific files to keep in root
KEEP_IN_ROOT=(
    "README.md"
    "LICENSE"
    "CONTRIBUTING.md"
    "BRANCH_CLEANUP_LOG.md"
)

# Move remaining .md files to archive
echo ""
echo -e "${YELLOW}Other Documentation:${NC}"
for file in *.md; do
    if [[ -f "$file" ]]; then
        # Check if should keep in root
        KEEP=false
        for keep_file in "${KEEP_IN_ROOT[@]}"; do
            if [[ "$file" == "$keep_file" ]]; then
                KEEP=true
                break
            fi
        done
        
        if [[ "$KEEP" == false ]]; then
            move_file "$file" "docs/archive/"
            ((MOVED_COUNT++))
        fi
    fi
done

echo ""
echo -e "${BLUE}📊 Summary${NC}"
echo "===================================="
echo -e "Files organized: ${GREEN}${MOVED_COUNT}${NC}"
echo ""

if [[ "$DRY_RUN" == false ]]; then
    # Create index file
    echo -e "${BLUE}📝 Creating documentation index...${NC}"
    
    cat > docs/archive/README.md << 'EOF'
# Archived Documentation

This directory contains historical documentation from the CouponTracker project development.

## Directory Structure

- **architecture/** - Architecture diagrams, data flow charts, and design documents
- **implementation/** - Implementation plans, progress trackers, and guides
- **fixes/** - Bug fixes, critical issues, and debugging documentation
- **testing/** - Testing reports, verification documents, and test plans
- **releases/** - Release notes, delivery summaries, and completion reports
- **sessions/** - Development session notes and status updates

## Active Documentation

For current documentation, see:
- [Main README](../../README.md) - Project overview and setup
- [Branch Management Policy](../BRANCH_MANAGEMENT_POLICY.md) - Git workflow
- [Implementation Status](../IMPLEMENTATION_STATUS.md) - Current status
- [LLM Integration](../LLM_INTEGRATION.md) - AI/ML documentation

## Note

These files are kept for historical reference and may contain outdated information.
Always refer to the main documentation for current practices and implementation details.
EOF
    
    echo -e "${GREEN}✅ Created docs/archive/README.md${NC}"
    
    # Update main README to reference new structure
    echo ""
    echo -e "${BLUE}📝 Next steps:${NC}"
    echo "1. Review the organized files in docs/archive/"
    echo "2. Update README.md to reference the new documentation structure"
    echo "3. Commit the changes: git add -A && git commit -m 'Organize documentation into docs/archive'"
else
    echo -e "${YELLOW}This was a dry run. Run without --dry-run to actually move files.${NC}"
fi

echo ""
echo -e "${GREEN}✅ Done!${NC}"

