#!/usr/bin/env python3
"""Manage packaging registry entries."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ml.packaging.registry import PackagingRegistry


def cmd_list(args: argparse.Namespace) -> None:
    registry = PackagingRegistry(args.registry)
    records = registry.list()
    for record in records:
        print(json.dumps(record.__dict__, indent=2))


def cmd_promote(args: argparse.Namespace) -> None:
    registry = PackagingRegistry(args.registry)
    record = registry.update_status(args.package_id, 'candidate', args.notes)
    if not record:
        raise SystemExit(f"Package {args.package_id} not found")
    print(json.dumps(record.__dict__, indent=2))


def cmd_release(args: argparse.Namespace) -> None:
    registry = PackagingRegistry(args.registry)
    record = registry.update_status(args.package_id, 'released', args.notes)
    if not record:
        raise SystemExit(f"Package {args.package_id} not found")
    print(json.dumps(record.__dict__, indent=2))


def cmd_rollback(args: argparse.Namespace) -> None:
    registry = PackagingRegistry(args.registry)
    record = registry.update_status(args.package_id, 'rollback', args.notes)
    if not record:
        raise SystemExit(f"Package {args.package_id} not found")
    print(json.dumps(record.__dict__, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description='Packaging registry management')
    parser.add_argument('--registry', default='artifacts/packages/index.json')
    subparsers = parser.add_subparsers(dest='command', required=True)

    list_parser = subparsers.add_parser('list', help='List packages')
    list_parser.set_defaults(func=cmd_list)

    promote_parser = subparsers.add_parser('promote', help='Mark package as release candidate')
    promote_parser.add_argument('package_id')
    promote_parser.add_argument('--notes', default=None)
    promote_parser.set_defaults(func=cmd_promote)

    release_parser = subparsers.add_parser('release', help='Mark package as released')
    release_parser.add_argument('package_id')
    release_parser.add_argument('--notes', default=None)
    release_parser.set_defaults(func=cmd_release)

    rollback_parser = subparsers.add_parser('rollback', help='Mark package as rollback')
    rollback_parser.add_argument('package_id')
    rollback_parser.add_argument('--notes', default=None)
    rollback_parser.set_defaults(func=cmd_rollback)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
