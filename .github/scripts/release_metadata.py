#!/usr/bin/env python3
"""Offline release metadata and JAR validation helpers (Python 3.11+).

No third-party packages or network access. ``rc`` maps to the publishing
services' ``beta`` release type; all suffixes set ``prerelease`` to true.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tomllib
import zipfile
import zlib


class ReleaseMetadataError(ValueError):
    """A release input does not satisfy the publication contract."""


_VERSION = r"[0-9]+(?:\.[0-9]+)+(?:-(?:alpha|beta|rc)(?:\.[0-9]+)?)?"
_TAG_RE = re.compile(rf"v?(?P<version>{_VERSION})", re.ASCII)
_MOD_ID_RE = re.compile(r"[a-z][a-z0-9_]{1,63}", re.ASCII)
_MOD_DESCRIPTOR = "META-INF/neoforge.mods.toml"


def parse_tag(tag: str) -> str:
    """Validate a bare tag and return its version, removing only one leading v."""
    match = _TAG_RE.fullmatch(tag) if isinstance(tag, str) else None
    if match is None:
        raise ReleaseMetadataError(f"Invalid release tag: {tag!r}")
    return match.group("version")


def build_metadata(tag: str, mod_id: str, minecraft_version: str) -> dict:
    """Build canonical metadata without consulting the checkout or network."""
    version = parse_tag(tag)
    if not isinstance(mod_id, str) or not _MOD_ID_RE.fullmatch(mod_id):
        raise ReleaseMetadataError(f"Invalid mod_id: {mod_id!r}")
    if not isinstance(minecraft_version, str) or not re.fullmatch(
        r"[0-9]+(?:\.[0-9]+)+", minecraft_version, re.ASCII
    ):
        raise ReleaseMetadataError(f"Invalid minecraft_version: {minecraft_version!r}")
    prerelease = "-" in version
    release_type = "alpha" if "-alpha" in version else "beta" if prerelease else "release"
    return {
        "version": version,
        "tag": tag,
        "mod_id": mod_id,
        "minecraft_version": minecraft_version,
        "release_type": release_type,
        "prerelease": prerelease,
        "expected_jar": f"{mod_id}-{version}.jar",
    }


def validate_metadata(metadata: dict) -> dict:
    """Require canonical metadata fields; leave additional caller fields intact."""
    if not isinstance(metadata, dict):
        raise ReleaseMetadataError("Metadata must be a JSON object")
    canonical = build_metadata(
        metadata.get("tag"), metadata.get("mod_id"), metadata.get("minecraft_version")
    )
    for key, expected in canonical.items():
        actual = metadata.get(key)
        if type(actual) is not type(expected) or actual != expected:
            raise ReleaseMetadataError(f"Metadata {key!r} must equal {expected!r}")
    return metadata


def read_properties(path: str | Path) -> dict[str, str]:
    """Read required literal Gradle properties, rejecting duplicate assignments.

    Release identifiers intentionally cannot use Java-properties escapes or
    continuations: publish exactly the literal values declared in the file.
    """
    required = {"mod_version", "mod_id", "minecraft_version"}
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        match = re.match(r"([^\s=:]+)(?:\s*[=:]\s*|\s+)(.*)$", line)
        if not match or match.group(1) not in required:
            continue
        key, value = match.group(1), match.group(2).strip()
        if key in values:
            raise ReleaseMetadataError(f"Duplicate property: {key}")
        if not value or "\\" in value:
            raise ReleaseMetadataError(f"Property {key} must be a nonempty literal")
        values[key] = value
    missing = required - values.keys()
    if missing:
        raise ReleaseMetadataError(f"Missing properties: {', '.join(sorted(missing))}")
    return values


def extract_release_notes(changelog: str, version: str) -> str:
    """Extract the exact version section, excluding its H2 heading.

    Accept ``## vVERSION``, ``## VERSION`` and their no-space forms. Ignore
    headings inside fenced code. A level-three heading never ends a release.
    There is deliberately no fallback to Unreleased or prefix matching.
    """
    if parse_tag(version) != version:
        raise ReleaseMetadataError("Expected a version without a v prefix")
    lines = changelog.splitlines(keepends=True)
    headings = []
    fence = None
    for index, line in enumerate(lines):
        fence_match = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line.rstrip("\r\n"))
        if fence_match:
            marker, tail = fence_match.groups()
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence) and not tail.strip():
                fence = None
            continue
        if fence is None:
            match = re.match(r"^##(?!#)[ \t]*(.*?)[ \t]*$", line.rstrip("\r\n"))
            if match:
                headings.append((index, match.group(1)))
    matches = [i for i, (_, title) in enumerate(headings) if title in (version, f"v{version}")]
    if len(matches) != 1:
        raise ReleaseMetadataError(
            f"Expected exactly one changelog heading for {version}; found {len(matches)}"
        )
    position = matches[0]
    start = headings[position][0] + 1
    end = headings[position + 1][0] if position + 1 < len(headings) else len(lines)
    notes = "".join(lines[start:end]).strip("\r\n")
    if not notes.strip():
        raise ReleaseMetadataError(f"Changelog section for {version} is empty")
    return notes + "\n"


def prepare_metadata(
    tag: str, properties_path: str | Path, changelog_path: str | Path
) -> tuple[dict, str]:
    """Validate release inputs and return (metadata, UTF-8 release notes)."""
    version = parse_tag(tag)
    properties = read_properties(properties_path)
    if version != properties["mod_version"]:
        raise ReleaseMetadataError(
            f"Tag version {version!r} does not match mod_version {properties['mod_version']!r}"
        )
    metadata = build_metadata(tag, properties["mod_id"], properties["minecraft_version"])
    notes = extract_release_notes(Path(changelog_path).read_text(encoding="utf-8-sig"), version)
    return metadata, notes


def verify_jar(path: str | Path, metadata: dict) -> dict:
    """Validate a built binary JAR and return filename, size and SHA-256.

    The metadata is not mutated. All ZIP members are CRC checked; duplicate
    members are rejected to prevent ambiguous descriptor/class interpretation.
    """
    validate_metadata(metadata)
    path = Path(path)
    if path.name != metadata["expected_jar"]:
        raise ReleaseMetadataError(
            f"Expected binary JAR {metadata['expected_jar']!r}, got {path.name!r}; "
            "sources/javadoc/dev artifacts are not allowed"
        )
    if not path.is_file() or path.stat().st_size == 0:
        raise ReleaseMetadataError(f"JAR is missing, not a regular file, or empty: {path}")
    if path.stat().st_size > 128 * 1024 * 1024:
        raise ReleaseMetadataError("JAR exceeds the 128 MiB upload/download limit")
    try:
        with path.open("rb") as stream:
            if stream.read(4) != b"PK\x03\x04":
                raise ReleaseMetadataError("JAR must be a real, nonempty ZIP archive")
            stream.seek(0)
            with zipfile.ZipFile(stream) as archive:
                entries = archive.infolist()
                if len(entries) > 20000 or sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
                    raise ReleaseMetadataError("JAR exceeds entry/decompressed size limits")
                names = archive.namelist()
                if len(names) != len(set(names)):
                    raise ReleaseMetadataError("JAR contains duplicate ZIP members")
                bad_member = archive.testzip()
                if bad_member is not None:
                    raise ReleaseMetadataError(f"Corrupt ZIP member: {bad_member}")
                if _MOD_DESCRIPTOR not in names:
                    raise ReleaseMetadataError(f"JAR lacks {_MOD_DESCRIPTOR}")
                descriptor = tomllib.loads(archive.read(_MOD_DESCRIPTOR).decode("utf-8"))
                mods = descriptor.get("mods")
                if not isinstance(mods, list) or any(not isinstance(mod, dict) for mod in mods):
                    raise ReleaseMetadataError("Descriptor must contain [[mods]] tables")
                matching = [mod for mod in mods if mod.get("modId") == metadata["mod_id"]]
                if len(matching) != 1:
                    raise ReleaseMetadataError("Descriptor must contain exactly one matching modId")
                if matching[0].get("version") != metadata["version"]:
                    raise ReleaseMetadataError("Descriptor mod version does not match metadata")
                classes = [info for info in archive.infolist() if not info.is_dir()
                           and re.fullmatch(r"com/lhy/mest/(?:[^/]+/)*[^/]+\.class", info.filename)
                           and info.file_size > 0]
                if not classes:
                    raise ReleaseMetadataError("JAR lacks nonempty com/lhy/mest/*.class entries")
            stream.seek(0)
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
            size = stream.seek(0, 2)
    except (OSError, zipfile.BadZipFile, RuntimeError, NotImplementedError,
            EOFError, zlib.error, UnicodeError, tomllib.TOMLDecodeError) as exc:
        raise ReleaseMetadataError(f"Cannot validate JAR: {exc}") from exc
    return {"filename": path.name, "size": size, "sha256": digest}


def write_json(path: str | Path, value: dict) -> None:
    """Write human-readable UTF-8 JSON, preserving non-ASCII characters."""
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare", help="Validate tag/properties/changelog and write release files")
    for argument in ("tag", "properties", "changelog", "output-dir"):
        prepare.add_argument(f"--{argument}", required=True)
    verify = commands.add_parser("verify", help="Validate a binary JAR and print its SHA-256 record")
    verify.add_argument("--metadata", required=True)
    verify.add_argument("--jar", required=True)
    verify.add_argument("--output-json", help="Write a separate artifact.json; never overwrites metadata/JAR")
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            metadata, notes = prepare_metadata(args.tag, args.properties, args.changelog)
            output = Path(args.output_dir)
            output.mkdir(parents=True, exist_ok=True)
            (output / "release-notes.md").write_text(notes, encoding="utf-8")
            write_json(output / "metadata.json", metadata)
        else:
            if args.output_json and Path(args.output_json).resolve() in {
                Path(args.metadata).resolve(), Path(args.jar).resolve()
            }:
                raise ReleaseMetadataError("--output-json must not overwrite metadata or JAR")
            metadata = json.loads(Path(args.metadata).read_text(encoding="utf-8"))
            artifact = verify_jar(args.jar, metadata)
            if args.output_json:
                write_json(args.output_json, artifact)
            print(json.dumps(artifact, ensure_ascii=False))
    except (ReleaseMetadataError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
