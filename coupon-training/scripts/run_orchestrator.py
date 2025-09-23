#!/usr/bin/env python3
"""Launch the training orchestrator FastAPI service."""

from __future__ import annotations

import uvicorn

from ml.orchestrator import create_app


def main() -> None:
    app = create_app()
    uvicorn.run(app, host="127.0.0.1", port=8001)


if __name__ == "__main__":
    main()
