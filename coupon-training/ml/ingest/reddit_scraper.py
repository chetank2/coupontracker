"""Authenticated Reddit scraper for coupon images."""

from __future__ import annotations

import json
import logging
import os
import pathlib
import time
from typing import Dict, Iterable, Optional

import requests
from requests import Response

LOGGER = logging.getLogger("reddit_ingest")
DEFAULT_HEADERS = {
    "User-Agent": "CouponTrackerBot/1.0 (contact: ops@coupontracker.app)",
    "Accept": "application/json",
}


def _request(url: str, headers: Optional[Dict[str, str]] = None) -> Response:
    merged = {**DEFAULT_HEADERS, **(headers or {})}
    resp = requests.get(url, headers=merged, timeout=15)
    resp.raise_for_status()
    return resp


def fetch_listing(query: str, limit: int = 50, token: Optional[str] = None) -> Dict:
    base = "https://www.reddit.com/search.json"
    params = {"q": query, "type": "link", "limit": limit, "sort": "new"}
    headers = {}
    if token:
        headers["Authorization"] = f"bearer {token}"
    resp = _request(base, headers=headers)
    return resp.json()


def download_media(url: str, output_dir: pathlib.Path, session: Optional[requests.Session] = None) -> Optional[pathlib.Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    sess = session or requests.Session()
    resp = sess.get(url, headers={"User-Agent": DEFAULT_HEADERS["User-Agent"]}, timeout=15)
    if resp.status_code != 200:
        LOGGER.warning("Failed to download %s status=%s", url, resp.status_code)
        return None
    filename = url.split("?")[0].split("/")[-1]
    path = output_dir / filename
    path.write_bytes(resp.content)
    return path


def scrape(query: str, output_dir: pathlib.Path, auth_token: Optional[str] = None) -> Iterable[pathlib.Path]:
    listing = fetch_listing(query, token=auth_token)
    session = requests.Session()
    for child in listing.get("data", {}).get("children", []):
        data = child.get("data", {})
        url = data.get("url_overridden_by_dest") or data.get("url")
        if not url:
            continue
        if any(url.lower().endswith(ext) for ext in (".jpg", ".jpeg", ".png")):
            path = download_media(url, output_dir, session=session)
            if path:
                yield path
        elif url.startswith("https://preview.redd.it"):
            path = download_media(url, output_dir, session=session)
            if path:
                yield path
        time.sleep(0.3)
