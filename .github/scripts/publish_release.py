#!/usr/bin/env python3
"""Publish validated JARs; never create a public release or CF file from notes alone."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

from release_metadata import build_metadata, verify_jar

GH_API = "https://api.github.com"
CF_API = "https://minecraft.curseforge.com/api"
LEDGER_NAME = "curseforge-publish.json"
MAX_DOWNLOAD = 128 * 1024 * 1024


class PublishError(RuntimeError):
    pass


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        if parsed.scheme != "https":
            raise PublishError("Refusing a non-HTTPS publishing redirect")
        redirected = super().redirect_request(request, fp, code, msg, headers, newurl)
        if redirected is not None and parsed.hostname != urllib.parse.urlparse(request.full_url).hostname:
            # GitHub release downloads redirect to signed object storage URLs; never forward tokens.
            redirected.remove_header("Authorization")
            redirected.remove_header("X-api-token")
        return redirected


class Api:
    def __init__(self, token, base=GH_API, header="Authorization"):
        if not token:
            raise PublishError("Required publishing token is not configured")
        self.base = base
        self.headers = {header: ("Bearer " + token) if header == "Authorization" else token,
                        "User-Agent": "MEST-release-workflow"}
        if base == GH_API:
            self.headers["X-GitHub-Api-Version"] = "2022-11-28"

    def request(self, method, path, *, data=None, content_type=None, raw=False, missing_ok=False):
        url = path if path.startswith("https://") else self.base + path
        # Absolute upload URLs must stay at GitHub; tokens must never be sent to arbitrary hosts.
        allowed = {urllib.parse.urlparse(self.base).hostname}
        if self.base == GH_API:
            allowed.add("uploads.github.com")
        if urllib.parse.urlparse(url).hostname not in allowed:
            raise PublishError("Untrusted publishing API host")
        headers = dict(self.headers)
        headers["Accept"] = "application/octet-stream" if raw else (
            "application/vnd.github+json" if self.base == GH_API else "application/json")
        if isinstance(data, dict):
            data = json.dumps(data, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.build_opener(SafeRedirect()).open(request, timeout=120) as response:
                payload = response.read(MAX_DOWNLOAD + 1)
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing_ok:
                return None
            # A bounded, token-redacted error message is needed to diagnose validation failures.
            detail = error.read(8192).decode("utf-8", errors="replace")
            for key, value in self.headers.items():
                if key.lower() in {"authorization", "x-api-token"}:
                    detail = detail.replace(value, "[REDACTED]")
                    detail = detail.replace(value.removeprefix("Bearer "), "[REDACTED]")
            detail = " ".join(detail.split())[:1500]
            raise PublishError(f"{method} publishing API returned HTTP {error.code}: {detail}") from None
        if len(payload) > MAX_DOWNLOAD:
            raise PublishError("Publishing response exceeds size limit")
        if raw:
            return payload
        return json.loads(payload) if payload else None


def repository():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if len(repo.split("/")) != 2 or any(not part or part in {".", ".."} for part in repo.split("/")):
        raise PublishError("GITHUB_REPOSITORY is required")
    return "/repos/" + repo


def assets(api, root, release_id):
    result = []
    for page in range(1, 101):
        batch = api.request("GET", f"{root}/releases/{release_id}/assets?per_page=100&page={page}")
        result.extend(batch)
        if len(batch) < 100:
            return result
    raise PublishError("Too many release assets")


def current_commit(api, root, tag):
    ref = api.request("GET", root + "/git/ref/tags/" + urllib.parse.quote(tag, safe=""))
    obj = ref["object"]
    for _ in range(8):
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = api.request("GET", root + "/git/tags/" + obj["sha"])["object"]
    raise PublishError("Tag does not resolve to a commit")


def replace_asset(api, root, release, name, payload, content_type):
    existing = [a for a in assets(api, root, release["id"]) if a["name"] == name]
    if len(existing) > 1:
        raise PublishError("Duplicate release asset names")
    if existing:
        previous = api.request("GET", f"{root}/releases/assets/{existing[0]['id']}", raw=True)
        if previous == payload:
            return existing[0]
    # Upload the replacement before removing the previous asset. An upload failure preserves it.
    temporary = name if not existing else "pending-" + uuid.uuid4().hex + "-" + name
    upload_url = release["upload_url"].split("{", 1)[0]
    uploaded = api.request("POST", upload_url + "?" + urllib.parse.urlencode({"name": temporary}),
                           data=payload, content_type=content_type)
    if uploaded.get("size") != len(payload) or uploaded.get("state") != "uploaded":
        raise PublishError("Release asset upload was not confirmed")
    downloaded = api.request("GET", f"{root}/releases/assets/{uploaded['id']}", raw=True)
    if hashlib.sha256(downloaded).digest() != hashlib.sha256(payload).digest():
        raise PublishError("Release asset checksum mismatch")
    if existing:
        api.request("DELETE", f"{root}/releases/assets/{existing[0]['id']}")
        uploaded = api.request("PATCH", f"{root}/releases/assets/{uploaded['id']}", data={"name": name})
    return uploaded


def publish_github(api, root, metadata, jar, notes, commit):
    verify_jar(jar, metadata)  # BEFORE any mutation, including creating a draft.
    if not notes.strip():
        raise PublishError("Release notes must not be empty")
    if current_commit(api, root, metadata["tag"]) != commit:
        raise PublishError("Tag moved during the build; refusing to publish stale artifacts")
    path = root + "/releases/tags/" + urllib.parse.quote(metadata["tag"], safe="")
    release = api.request("GET", path, missing_ok=True)
    if release is None:
        release = api.request("POST", root + "/releases", data={
            "tag_name": metadata["tag"], "target_commitish": commit,
            "name": metadata["tag"], "body": notes, "draft": True,
            "prerelease": metadata["prerelease"]})
    replace_asset(api, root, release, metadata["expected_jar"], Path(jar).read_bytes(), "application/java-archive")
    if current_commit(api, root, metadata["tag"]) != commit:
        raise PublishError("Tag moved before release publication; rerun the workflow for its new commit")
    release = api.request("PATCH", f"{root}/releases/{release['id']}", data={
        "name": metadata["tag"], "body": notes, "draft": False,
        "prerelease": metadata["prerelease"]})
    print("GitHub Release published with verified JAR:", release["html_url"])


def multipart(metadata, jar=None):
    boundary = "mest-" + uuid.uuid4().hex
    parts = [(f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
              "Content-Type: application/json; charset=utf-8\r\n\r\n").encode(),
             json.dumps(metadata, ensure_ascii=False).encode("utf-8"), b"\r\n"]
    if jar is not None:
        path = Path(jar)
        if not path.is_file() or path.stat().st_size == 0:
            raise PublishError("A nonempty JAR is required for CurseForge upload")
        parts.extend([(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                       f"filename=\"{path.name}\"\r\nContent-Type: application/java-archive\r\n\r\n").encode(),
                      path.read_bytes(), b"\r\n"])
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def select_jar(release, release_assets, expected):
    if release.get("draft"):
        raise PublishError("Draft releases cannot be sent to CurseForge")
    if not (release.get("body") or "").strip():
        raise PublishError("Release text is empty")
    matches = [a for a in release_assets if a.get("name") == expected
               and a.get("state") == "uploaded" and a.get("size", 0) > 0]
    if len(matches) != 1:
        raise PublishError("Release must contain exactly one uploaded nonempty versioned mod JAR")
    return matches[0]


def curseforge_version_ids(cf, minecraft_version):
    versions = cf.request("GET", "/game/versions")
    result = []
    for name in (minecraft_version, "NeoForge", "Client", "Server"):
        matches = [version["id"] for version in versions if version.get("name") == name]
        matches = list(dict.fromkeys(str(value) for value in matches))
        if len(matches) != 1 or not matches[0].isdigit() or int(matches[0]) <= 0:
            raise PublishError(f"CurseForge version must resolve uniquely: {name}; matched IDs={matches}")
        result.append(int(matches[0]))
    return result


def sync_curseforge(gh, cf, root, tag, project_id, minecraft_version):
    if not str(project_id).isdigit() or int(project_id) < 1:
        raise PublishError("CURSEFORGE_PROJECT_ID must be a positive project number")
    metadata = build_metadata(tag, "mesplicedterminal", minecraft_version)
    release = gh.request("GET", root + "/releases/tags/" + urllib.parse.quote(tag, safe=""))
    listed = assets(gh, root, release["id"])
    asset = select_jar(release, listed, metadata["expected_jar"])
    payload = gh.request("GET", f"{root}/releases/assets/{asset['id']}", raw=True)
    if len(payload) != asset["size"]:
        raise PublishError("Release JAR download size mismatch")
    with tempfile.TemporaryDirectory(prefix="mest-publish-") as folder:
        jar = Path(folder) / metadata["expected_jar"]
        jar.write_bytes(payload)
        artifact = verify_jar(jar, metadata)
        # Durable receipts make normal reruns and release-text edits update the same CF file.
        receipts = [a for a in listed if a["name"] == LEDGER_NAME]
        if len(receipts) > 1:
            raise PublishError("Duplicate CurseForge receipts")
        ledger = {"schema": 1, "files": []}
        if receipts:
            ledger = json.loads(gh.request("GET", f"{root}/releases/assets/{receipts[0]['id']}", raw=True))
            if ledger.get("schema") != 1 or not isinstance(ledger.get("files"), list):
                raise PublishError("Invalid CurseForge receipt")
        matching = [entry for entry in ledger["files"] if entry.get("sha256") == artifact["sha256"]
                    and str(entry.get("project_id")) == str(project_id)]
        body_hash = hashlib.sha256(release["body"].encode("utf-8")).hexdigest()
        if len(matching) > 1:
            raise PublishError("Ambiguous CurseForge receipt")
        record = matching[0] if matching else None
        if record and record.get("body_sha256") == body_hash:
            print("CurseForge already synchronized; verified release JAR is still attached")
            return
        cf_metadata = {
            "changelog": release["body"], "changelogType": "markdown",
            "displayName": f"ME Spliced Terminal {metadata['version']}",
            "releaseType": metadata["release_type"],
            "gameVersions": curseforge_version_ids(cf, minecraft_version),
            "relations": {"projects": [
                {"slug": "applied-energistics-2", "projectID": "223794", "type": "requiredDependency"},
                {"slug": "applied-energistics-2-wireless-terminals", "projectID": "459929", "type": "requiredDependency"}]}}
        if record:
            file_id = record.get("file_id")
            if not isinstance(file_id, int) or file_id < 1:
                raise PublishError("Invalid CurseForge file ID in receipt")
            cf_metadata["fileID"] = file_id
            data, content_type = multipart(cf_metadata)
            result = cf.request("POST", f"/projects/{project_id}/update-file", data=data, content_type=content_type)
            if result.get("id") != file_id:
                raise PublishError("CurseForge file update was not confirmed")
        else:
            data, content_type = multipart(cf_metadata, jar)
            # No automatic retry: an ambiguous timeout might have already created a CF file.
            result = cf.request("POST", f"/projects/{project_id}/upload-file", data=data, content_type=content_type)
            file_id = result.get("id")
            if not isinstance(file_id, int) or file_id < 1:
                raise PublishError("CurseForge did not return a file ID")
            print(f"CurseForge upload accepted: project={project_id}, file={file_id}, sha256={artifact['sha256']}")
            record = {"project_id": str(project_id), "file_id": file_id, "sha256": artifact["sha256"]}
            ledger["files"].append(record)
        record["body_sha256"] = body_hash
        record["tag"] = tag
        replace_asset(gh, root, release, LEDGER_NAME,
                      (json.dumps(ledger, ensure_ascii=False, indent=2) + "\n").encode(), "application/json")
        print("CurseForge synchronized with the attached JAR and current Release text:", file_id)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    publish = sub.add_parser("github")
    for name in ("metadata", "jar", "notes", "commit"):
        publish.add_argument("--" + name, required=True)
    sync = sub.add_parser("curseforge")
    sync.add_argument("--tag", required=True)
    sync.add_argument("--project-id", required=True)
    sync.add_argument("--minecraft-version", default="1.21.1")
    args = parser.parse_args()
    gh = Api(os.environ.get("GH_TOKEN", ""))
    if args.command == "github":
        publish_github(gh, repository(), json.loads(Path(args.metadata).read_text(encoding="utf-8")),
                       args.jar, Path(args.notes).read_text(encoding="utf-8"), args.commit)
    else:
        cf = Api(os.environ.get("CURSEFORGE_TOKEN", ""), CF_API, "X-Api-Token")
        sync_curseforge(gh, cf, repository(), args.tag, args.project_id, args.minecraft_version)


if __name__ == "__main__":
    main()
