#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import json
import shutil
import hashlib
import datetime
from pathlib import Path
from tqdm import tqdm

def compute_file_hash(file_path):
    """Compute SHA-256 hash of a file
    
    Args:
        file_path (str): Path to the file
        
    Returns:
        str: Hex digest of the hash
    """
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        # Read and update hash in chunks of 4K
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def create_dataset_snapshot(data_dir, snapshot_dir=None, metadata=None):
    """Create a snapshot of the current dataset
    
    Args:
        data_dir (str): Base data directory
        snapshot_dir (str): Directory to store the snapshot (default: data_dir/snapshots/YYYYMMDD_HHMMSS)
        metadata (dict): Additional metadata to include in the snapshot
        
    Returns:
        str: Path to the snapshot directory
    """
    # Create timestamp for snapshot
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    
    # Define snapshot directory if not provided
    if snapshot_dir is None:
        snapshots_dir = os.path.join(data_dir, "snapshots")
        os.makedirs(snapshots_dir, exist_ok=True)
        snapshot_dir = os.path.join(snapshots_dir, timestamp)
    
    # Create snapshot directory
    os.makedirs(snapshot_dir, exist_ok=True)
    
    # Define directories to snapshot
    dirs_to_snapshot = ["raw", "annotated", "processed", "train", "validation"]
    
    # Initialize snapshot metadata
    snapshot_metadata = {
        "timestamp": timestamp,
        "created_at": datetime.datetime.now().isoformat(),
        "directories": {},
        "file_count": 0,
        "total_size_bytes": 0
    }
    
    # Add user-provided metadata
    if metadata:
        snapshot_metadata.update(metadata)
    
    # Process each directory
    for dir_name in dirs_to_snapshot:
        source_dir = os.path.join(data_dir, dir_name)
        if not os.path.exists(source_dir):
            print(f"Directory {source_dir} does not exist, skipping")
            continue
        
        # Create directory in snapshot
        snapshot_subdir = os.path.join(snapshot_dir, dir_name)
        os.makedirs(snapshot_subdir, exist_ok=True)
        
        # Initialize directory metadata
        dir_metadata = {
            "file_count": 0,
            "size_bytes": 0,
            "files": {}
        }
        
        # Process files in directory
        for file_path in tqdm(list(Path(source_dir).glob("*")), desc=f"Processing {dir_name}"):
            if file_path.is_file():
                # Compute file hash
                file_hash = compute_file_hash(file_path)
                
                # Get file size
                file_size = os.path.getsize(file_path)
                
                # Copy file to snapshot
                shutil.copy2(file_path, os.path.join(snapshot_subdir, file_path.name))
                
                # Update metadata
                dir_metadata["file_count"] += 1
                dir_metadata["size_bytes"] += file_size
                dir_metadata["files"][file_path.name] = {
                    "hash": file_hash,
                    "size_bytes": file_size,
                    "last_modified": datetime.datetime.fromtimestamp(os.path.getmtime(file_path)).isoformat()
                }
                
                # Update overall counts
                snapshot_metadata["file_count"] += 1
                snapshot_metadata["total_size_bytes"] += file_size
        
        # Add directory metadata to snapshot metadata
        snapshot_metadata["directories"][dir_name] = dir_metadata
    
    # Save metadata to snapshot directory
    metadata_path = os.path.join(snapshot_dir, "metadata.json")
    with open(metadata_path, "w") as f:
        json.dump(snapshot_metadata, f, indent=2)
    
    print(f"Created dataset snapshot at {snapshot_dir}")
    print(f"Total files: {snapshot_metadata['file_count']}")
    print(f"Total size: {snapshot_metadata['total_size_bytes'] / (1024*1024):.2f} MB")
    
    return snapshot_dir

def restore_dataset_snapshot(snapshot_dir, data_dir, dirs_to_restore=None):
    """Restore a dataset snapshot
    
    Args:
        snapshot_dir (str): Path to the snapshot directory
        data_dir (str): Base data directory to restore to
        dirs_to_restore (list): List of directories to restore (default: all)
        
    Returns:
        bool: True if successful, False otherwise
    """
    # Check if snapshot directory exists
    if not os.path.exists(snapshot_dir):
        print(f"Snapshot directory {snapshot_dir} does not exist")
        return False
    
    # Check if metadata file exists
    metadata_path = os.path.join(snapshot_dir, "metadata.json")
    if not os.path.exists(metadata_path):
        print(f"Metadata file {metadata_path} does not exist")
        return False
    
    # Load metadata
    with open(metadata_path, "r") as f:
        metadata = json.load(f)
    
    # Determine directories to restore
    if dirs_to_restore is None:
        dirs_to_restore = list(metadata["directories"].keys())
    
    # Restore each directory
    for dir_name in dirs_to_restore:
        if dir_name not in metadata["directories"]:
            print(f"Directory {dir_name} not found in snapshot, skipping")
            continue
        
        source_dir = os.path.join(snapshot_dir, dir_name)
        target_dir = os.path.join(data_dir, dir_name)
        
        if not os.path.exists(source_dir):
            print(f"Source directory {source_dir} does not exist, skipping")
            continue
        
        # Create target directory if it doesn't exist
        os.makedirs(target_dir, exist_ok=True)
        
        # Copy files from snapshot to target
        file_count = 0
        for file_path in tqdm(list(Path(source_dir).glob("*")), desc=f"Restoring {dir_name}"):
            if file_path.is_file():
                shutil.copy2(file_path, os.path.join(target_dir, file_path.name))
                file_count += 1
        
        print(f"Restored {file_count} files to {target_dir}")
    
    print(f"Snapshot restoration complete")
    return True

def list_snapshots(data_dir):
    """List available dataset snapshots
    
    Args:
        data_dir (str): Base data directory
        
    Returns:
        list: List of snapshot metadata
    """
    snapshots_dir = os.path.join(data_dir, "snapshots")
    if not os.path.exists(snapshots_dir):
        print(f"Snapshots directory {snapshots_dir} does not exist")
        return []
    
    snapshots = []
    for snapshot_dir in sorted(Path(snapshots_dir).glob("*")):
        if snapshot_dir.is_dir():
            metadata_path = os.path.join(snapshot_dir, "metadata.json")
            if os.path.exists(metadata_path):
                try:
                    with open(metadata_path, "r") as f:
                        metadata = json.load(f)
                    
                    # Add snapshot directory to metadata
                    metadata["snapshot_dir"] = str(snapshot_dir)
                    snapshots.append(metadata)
                except Exception as e:
                    print(f"Error loading metadata from {metadata_path}: {e}")
    
    return snapshots

if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Dataset versioning tools")
    subparsers = parser.add_subparsers(dest="command", help="Command to run")
    
    # Create snapshot command
    create_parser = subparsers.add_parser("create", help="Create a dataset snapshot")
    create_parser.add_argument("--data-dir", type=str, default="../data", help="Base data directory")
    create_parser.add_argument("--description", type=str, help="Description of the snapshot")
    
    # List snapshots command
    list_parser = subparsers.add_parser("list", help="List available snapshots")
    list_parser.add_argument("--data-dir", type=str, default="../data", help="Base data directory")
    
    # Restore snapshot command
    restore_parser = subparsers.add_parser("restore", help="Restore a dataset snapshot")
    restore_parser.add_argument("--snapshot-dir", type=str, required=True, help="Path to the snapshot directory")
    restore_parser.add_argument("--data-dir", type=str, default="../data", help="Base data directory to restore to")
    restore_parser.add_argument("--dirs", type=str, nargs="+", help="Directories to restore")
    
    args = parser.parse_args()
    
    if args.command == "create":
        metadata = {}
        if args.description:
            metadata["description"] = args.description
        
        create_dataset_snapshot(args.data_dir, metadata=metadata)
    
    elif args.command == "list":
        snapshots = list_snapshots(args.data_dir)
        
        if not snapshots:
            print("No snapshots found")
        else:
            print(f"Found {len(snapshots)} snapshots:")
            for i, snapshot in enumerate(snapshots):
                print(f"{i+1}. {snapshot['timestamp']} - {snapshot.get('description', 'No description')}")
                print(f"   Files: {snapshot['file_count']}, Size: {snapshot['total_size_bytes'] / (1024*1024):.2f} MB")
                print(f"   Path: {snapshot['snapshot_dir']}")
                print()
    
    elif args.command == "restore":
        restore_dataset_snapshot(args.snapshot_dir, args.data_dir, args.dirs)
    
    else:
        parser.print_help()
