#!/usr/bin/env python3
"""Build the small, dependency-free GitHub Pages site. Never includes app artifacts."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "website"
OUTPUT = ROOT / ".site-build"
MAX_TOTAL = 5 * 1024 * 1024
MAX_FILE = 1024 * 1024
ENTRY_FILES = ("index.html", "styles.css", "app.js")
ASSET_EXTENSIONS = {".webp", ".svg", ".png", ".ico"}


def https_url(value: str, name: str) -> str:
    if not value:
        return ""
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError(f"{name} must be an absolute HTTPS URL without credentials")
    return value.rstrip("/")


def build(repository: str = "", download: str = "") -> None:
    config = json.loads((SOURCE / "site.json").read_text())
    repository_url = config.get("repositoryUrl", "")
    if repository:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Repository must use owner/name format")
        repository_url = f"https://github.com/{repository}"
    config = {
        "repositoryUrl": https_url(repository_url, "repositoryUrl"),
        "downloadUrl": https_url(download or config.get("downloadUrl", ""), "downloadUrl"),
    }
    sources = [SOURCE / name for name in ENTRY_FILES]
    for path in sorted((SOURCE / "assets").rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Symlinks are not allowed: {path.relative_to(SOURCE)}")
        if path.is_file():
            if path.suffix not in ASSET_EXTENSIONS:
                raise ValueError(f"Unexpected asset type: {path.relative_to(SOURCE)}")
            sources.append(path)
    for path in sources:
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Expected a regular source file: {path}")
        if path.stat().st_size > MAX_FILE:
            raise ValueError(f"Asset exceeds the 1 MiB site budget: {path.relative_to(SOURCE)}")
    total = sum(path.stat().st_size for path in sources)
    if total > MAX_TOTAL - 4096:
        raise ValueError("Site exceeds the 5 MiB budget. Keep APKs in GitHub Releases.")
    if OUTPUT.is_symlink():
        raise ValueError("Refusing to replace a symlink at .site-build")
    if OUTPUT.exists():
        # Only this fixed, generated directory is ever removed.
        if not (OUTPUT / ".unbound-generated").exists():
            raise ValueError("Refusing to overwrite an unrecognized .site-build directory")
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir()
    (OUTPUT / ".unbound-generated").touch()
    for path in sources:
        destination = OUTPUT / path.relative_to(SOURCE)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    (OUTPUT / "site-config.js").write_text("window.UNBOUND_SITE = " + json.dumps(config, ensure_ascii=True) + ";\n")
    (OUTPUT / ".nojekyll").touch()
    files = [p for p in OUTPUT.rglob("*") if p.is_file()]
    size = sum(p.stat().st_size for p in files)
    print(f"Built {len(files)} files: {size:,} bytes ({size / 1024:.1f} KiB), budget 5 MiB")
    print(f"Output: {OUTPUT}")
    print("APKs, release notes, device reports, source archives and build tools are excluded.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""), help="GitHub owner/repo; normally supplied by Actions")
    parser.add_argument("--download-url", default=os.environ.get("UNBOUND_DOWNLOAD_URL", ""), help="Optional HTTPS release-asset URL; no binary is bundled")
    args = parser.parse_args()
    try:
        build(args.repository, args.download_url)
    except (OSError, ValueError, TypeError) as error:
        print(f"Website build failed: {error}", file=sys.stderr)
        sys.exit(1)
