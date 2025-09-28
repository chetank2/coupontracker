#!/usr/bin/env python3
"""
GitHub Repository Cleanup Script
Cleans up stale branches, archives important ones, and optimizes repository structure.
"""

import subprocess
import sys
from pathlib import Path

class RepositoryCleanup:
    def __init__(self):
        self.branches_to_keep = {
            'main',
            'gh-pages',
            'feature/llm-ocr-integration'  # Keep until fully archived
        }
        
        self.branches_to_archive = [
            'feature/enhanced-coupon-fields',
            'feature/super-ocr',
            'feature/phase4-roi-ocr-final',
            'web-training-app',
            'mobile-pwa-final'
        ]
        
        self.cleanup_stats = {
            'local_deleted': 0,
            'remote_deleted': 0,
            'archived': 0,
            'errors': []
        }

    def run_git_command(self, command):
        """Run git command and return result"""
        try:
            result = subprocess.run(command, shell=True, capture_output=True, text=True)
            return result.returncode == 0, result.stdout.strip(), result.stderr.strip()
        except Exception as e:
            return False, "", str(e)

    def archive_important_branches(self):
        """Archive important branches before deletion"""
        print("🏷️  ARCHIVING IMPORTANT BRANCHES")
        
        for branch in self.branches_to_archive:
            # Check if branch exists locally
            success, _, _ = self.run_git_command(f"git show-ref --verify --quiet refs/heads/{branch}")
            if success:
                tag_name = f"archive/{branch.replace('/', '-')}"
                success, stdout, stderr = self.run_git_command(f"git tag {tag_name} {branch}")
                if success:
                    print(f"✅ Archived {branch} → {tag_name}")
                    self.cleanup_stats['archived'] += 1
                else:
                    print(f"❌ Failed to archive {branch}: {stderr}")
                    self.cleanup_stats['errors'].append(f"Archive {branch}: {stderr}")
            
            # Check if branch exists remotely
            success, _, _ = self.run_git_command(f"git show-ref --verify --quiet refs/remotes/origin/{branch}")
            if success:
                tag_name = f"archive/{branch.replace('/', '-')}"
                success, stdout, stderr = self.run_git_command(f"git tag {tag_name} origin/{branch}")
                if success:
                    print(f"✅ Archived origin/{branch} → {tag_name}")

    def cleanup_local_branches(self):
        """Clean up local branches"""
        print("\n🧹 CLEANING LOCAL BRANCHES")
        
        # Get all local branches
        success, branches, _ = self.run_git_command("git branch --format='%(refname:short)'")
        if not success:
            print("❌ Failed to get local branches")
            return
        
        for branch in branches.split('\n'):
            branch = branch.strip()
            if branch and branch not in self.branches_to_keep:
                success, _, stderr = self.run_git_command(f"git branch -D {branch}")
                if success:
                    print(f"✅ Deleted local branch: {branch}")
                    self.cleanup_stats['local_deleted'] += 1
                else:
                    print(f"❌ Failed to delete {branch}: {stderr}")
                    self.cleanup_stats['errors'].append(f"Local {branch}: {stderr}")

    def cleanup_remote_branches(self):
        """Clean up remote branches (except protected ones)"""
        print("\n🌐 CLEANING REMOTE BRANCHES")
        
        # Get all remote branches
        success, branches, _ = self.run_git_command("git branch -r --format='%(refname:short)'")
        if not success:
            print("❌ Failed to get remote branches")
            return
        
        protected_patterns = [
            'origin/main',
            'origin/gh-pages'
        ]
        
        branches_to_delete = []
        for branch in branches.split('\n'):
            branch = branch.strip()
            if branch and not any(pattern in branch for pattern in protected_patterns):
                # Extract branch name without origin/
                branch_name = branch.replace('origin/', '')
                branches_to_delete.append(branch_name)
        
        print(f"📋 Found {len(branches_to_delete)} remote branches to delete")
        
        # Delete in batches to avoid command line length limits
        batch_size = 10
        for i in range(0, len(branches_to_delete), batch_size):
            batch = branches_to_delete[i:i + batch_size]
            branch_list = ' '.join(batch)
            
            success, stdout, stderr = self.run_git_command(f"git push origin --delete {branch_list}")
            if success:
                print(f"✅ Deleted remote branches: {', '.join(batch)}")
                self.cleanup_stats['remote_deleted'] += len(batch)
            else:
                print(f"❌ Failed to delete batch: {stderr}")
                # Try individual deletion for failed batch
                for branch in batch:
                    success, _, stderr = self.run_git_command(f"git push origin --delete {branch}")
                    if success:
                        print(f"✅ Deleted remote branch: {branch}")
                        self.cleanup_stats['remote_deleted'] += 1
                    else:
                        print(f"❌ Failed to delete {branch}: {stderr}")
                        self.cleanup_stats['errors'].append(f"Remote {branch}: {stderr}")

    def cleanup_local_remote_refs(self):
        """Clean up local references to deleted remote branches"""
        print("\n🔄 CLEANING LOCAL REMOTE REFERENCES")
        
        success, _, stderr = self.run_git_command("git remote prune origin")
        if success:
            print("✅ Pruned stale remote references")
        else:
            print(f"❌ Failed to prune remote references: {stderr}")

    def optimize_repository(self):
        """Optimize repository after cleanup"""
        print("\n⚡ OPTIMIZING REPOSITORY")
        
        commands = [
            ("git gc --aggressive --prune=now", "Garbage collection"),
            ("git repack -Ad", "Repacking objects"),
            ("git prune", "Pruning unreachable objects")
        ]
        
        for command, description in commands:
            success, stdout, stderr = self.run_git_command(command)
            if success:
                print(f"✅ {description} completed")
            else:
                print(f"❌ {description} failed: {stderr}")

    def push_archive_tags(self):
        """Push archive tags to remote"""
        print("\n🏷️  PUSHING ARCHIVE TAGS")
        
        success, tags, _ = self.run_git_command("git tag -l 'archive/*'")
        if success and tags:
            success, _, stderr = self.run_git_command("git push origin --tags")
            if success:
                print("✅ Pushed archive tags to remote")
            else:
                print(f"❌ Failed to push tags: {stderr}")

    def generate_cleanup_report(self):
        """Generate cleanup summary report"""
        print("\n" + "=" * 60)
        print("📊 REPOSITORY CLEANUP SUMMARY")
        print("=" * 60)
        
        print(f"🧹 Local branches deleted: {self.cleanup_stats['local_deleted']}")
        print(f"🌐 Remote branches deleted: {self.cleanup_stats['remote_deleted']}")
        print(f"🏷️  Branches archived: {self.cleanup_stats['archived']}")
        print(f"❌ Errors encountered: {len(self.cleanup_stats['errors'])}")
        
        total_deleted = self.cleanup_stats['local_deleted'] + self.cleanup_stats['remote_deleted']
        print(f"\n📈 Total branches removed: {total_deleted}")
        
        if self.cleanup_stats['errors']:
            print(f"\n❌ ERRORS:")
            for error in self.cleanup_stats['errors'][:5]:  # Show first 5 errors
                print(f"   - {error}")
            if len(self.cleanup_stats['errors']) > 5:
                print(f"   ... and {len(self.cleanup_stats['errors']) - 5} more")
        
        # Check final state
        success, branches, _ = self.run_git_command("git branch -a | wc -l")
        if success:
            remaining_branches = int(branches.strip())
            print(f"\n📊 Remaining branches: {remaining_branches}")
            
            if remaining_branches <= 10:
                print("🎉 REPOSITORY SUCCESSFULLY CLEANED! 🚀")
                return True
            elif remaining_branches <= 20:
                print("✅ REPOSITORY SIGNIFICANTLY IMPROVED")
                return True
            else:
                print("⚠️  REPOSITORY PARTIALLY CLEANED - MANUAL REVIEW NEEDED")
                return False
        
        return False

    def run_cleanup(self):
        """Execute complete repository cleanup"""
        print("🧹 COUPONTRACKER REPOSITORY CLEANUP")
        print("=" * 50)
        
        # Ensure we're on main branch
        success, _, _ = self.run_git_command("git checkout main")
        if not success:
            print("❌ Failed to checkout main branch")
            return False
        
        # Pull latest changes
        success, _, _ = self.run_git_command("git pull origin main")
        if not success:
            print("⚠️  Warning: Failed to pull latest changes")
        
        # Execute cleanup steps
        self.archive_important_branches()
        self.cleanup_local_branches()
        self.cleanup_remote_branches()
        self.cleanup_local_remote_refs()
        self.push_archive_tags()
        self.optimize_repository()
        
        return self.generate_cleanup_report()

if __name__ == "__main__":
    cleanup = RepositoryCleanup()
    success = cleanup.run_cleanup()
    sys.exit(0 if success else 1)
