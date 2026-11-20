#!/usr/bin/env python3
"""Verify PocketBridge's release identity before staging or installing an update."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib import request
import xml.etree.ElementTree as ET

PACKAGE = "com.local.wififiles"
GRADLE = Path(__file__).resolve().parents[1] / "app/build.gradle"
BUILD_TOOLS = "36.0.0"
FIRST_STABLE_TAG = "v4.0.1"
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TAG = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?", re.ASCII)
SHA256 = re.compile(r"[0-9a-fA-F]{64}|(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}")
MAX_APK = 100 * 1024 * 1024


class ReleaseError(Exception):
    pass


@dataclass(frozen=True)
class Apk:
    version_name: str
    version_code: int
    signer: str


def digest(value: str) -> str:
    text = value.strip()
    if not SHA256.fullmatch(text):
        raise ReleaseError("PB_SIGNER_SHA256 must contain the SHA-256 fingerprint of the release certificate")
    return text.replace(":", "").lower()


def version_tuple(tag: str) -> tuple[int, int, int]:
    match = TAG.fullmatch(tag)
    if not match:
        raise ReleaseError(f"Invalid release tag: {tag!r}")
    return tuple(int(part or 0) for part in match.groups())


def metadata(tag: str, gradle: Path = GRADLE) -> tuple[str, int]:
    if version_tuple(tag) < version_tuple(FIRST_STABLE_TAG):
        raise ReleaseError(f"Stable release signing starts at {FIRST_STABLE_TAG}")
    text = gradle.read_text(encoding="utf-8")
    names = re.findall(r"^\s*versionName\s+'([^']+)'\s*$", text, re.M)
    codes = re.findall(r"^\s*versionCode\s+(\d+)\s*$", text, re.M)
    if len(names) != 1 or len(codes) != 1 or tag != f"v{names[0]}":
        raise ReleaseError("The tag must match the single versionName in app/build.gradle")
    code = int(codes[0])
    if not 1 <= code <= 2_100_000_000:
        raise ReleaseError("Invalid versionCode in app/build.gradle")
    return names[0], code


def sdk_tool(name: str) -> Path:
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        raise ReleaseError("Set ANDROID_HOME to the Android SDK directory")
    tool = Path(root) / "build-tools" / BUILD_TOOLS / name
    if not tool.is_file():
        raise ReleaseError(f"Missing Android Build Tools {BUILD_TOOLS}: {name}")
    return tool


def output(*cmd: str | Path) -> str:
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            encoding="utf-8", errors="replace", env={**os.environ, "LC_ALL": "C.UTF-8"})
    if result.returncode:
        # Only verification utilities are run here; no password or key arguments.
        raise ReleaseError(f"{Path(cmd[0]).name} failed: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def parse_badging(text: str) -> tuple[str, int]:
    package_lines = re.findall(r"^package: (.+)$", text, re.M)
    if len(package_lines) != 1:
        raise ReleaseError("APK has no unambiguous package declaration")
    values = dict(re.findall(r"([A-Za-z]+)='([^']*)'", package_lines[0]))
    if values.get("name") != PACKAGE:
        raise ReleaseError(f"Expected package {PACKAGE}")
    if not re.fullmatch(r"[0-9]+", values.get("versionCode", "")) or not values.get("versionName"):
        raise ReleaseError("APK has no valid versionName/versionCode")
    if re.search(r"^application-debuggable\b", text, re.M):
        raise ReleaseError("A debuggable APK cannot be published as a stable release")
    return values["versionName"], int(values["versionCode"])


def parse_apk_signer(text: str) -> str:
    matches = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: (\S+)\s*$", text, re.M)
    if len(matches) != 1 or matches[0][0] != "1":
        raise ReleaseError("Expected exactly one APK signing certificate")
    return digest(matches[0][1])


def inspect_apk(path: Path) -> Apk:
    path = path.resolve(strict=True)
    # Do not infer the signer from the filename or from a release note.
    name, code = parse_badging(output(sdk_tool("aapt2"), "dump", "badging", path))
    signer = parse_apk_signer(output(sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", path))
    return Apk(name, code, signer)


def compare(old: Apk, new: Apk) -> None:
    if old.signer != new.signer:
        raise ReleaseError("Signing certificate differs from the previous APK; Android cannot update in place")
    if new.version_code <= old.version_code:
        raise ReleaseError(f"versionCode must increase ({old.version_code} -> {new.version_code})")


def verify_bundle(path: Path, apk: Apk) -> None:
    path = path.resolve(strict=True)
    checked = output("jarsigner", "-J-Duser.language=en", "-J-Duser.country=US", "-verify", path)
    if not re.search(r"^jar verified\.$", checked, re.M) or "unsigned entries" in checked.lower():
        raise ReleaseError("AAB is not fully signed or its signature is invalid")
    certs = output("keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-printcert", "-jarfile", path)
    signers = re.findall(r"^Signer #(\d+):\s*$", certs, re.M)
    cert_hashes = re.findall(r"^\s*SHA256:\s*(\S+)\s*$", certs, re.M)
    # keytool also prints parent certificates, if present; the first one is the signer.
    if signers != ["1"] or not cert_hashes or digest(cert_hashes[0]) != apk.signer:
        raise ReleaseError("AAB signing certificate does not match the verified APK")

    jar = Path(os.environ.get("PB_BUNDLETOOL_JAR", ""))
    if not jar.is_file():
        raise ReleaseError("Set PB_BUNDLETOOL_JAR to the pinned bundletool-all-1.18.3.jar")
    with jar.open("rb") as tool:
        if hashlib.file_digest(tool, "sha256").hexdigest() != BUNDLETOOL_SHA256:
            raise ReleaseError("bundletool JAR checksum mismatch")
    bundletool = ("java", "-jar", jar)
    bundle_arg = f"--bundle={path}"
    output(*bundletool, "validate", bundle_arg)
    manifest = ET.fromstring(output(*bundletool, "dump", "manifest", bundle_arg, "--module=base"))
    if (manifest.get("package"), manifest.get(ANDROID + "versionName"), manifest.get(ANDROID + "versionCode")) != (
        PACKAGE, apk.version_name, str(apk.version_code)
    ):
        raise ReleaseError("AAB package/version does not match the verified APK")
    application = manifest.find("application")
    if application is None or application.get(ANDROID + "debuggable", "false") != "false":
        raise ReleaseError("AAB must have a non-debuggable application")


def stage(tag: str, apk_path: Path, aab_path: Path, out: Path, previous: Path | None) -> None:
    version_name, version_code = metadata(tag)
    pinned = digest(os.environ.get("PB_SIGNER_SHA256", ""))
    if previous is None and tag != FIRST_STABLE_TAG:
        raise ReleaseError("A previous published stable APK is required after the first stable release")
    app = inspect_apk(apk_path)
    if (app.version_name, app.version_code) != (version_name, version_code):
        raise ReleaseError("Built APK version does not match its release tag and versionCode")
    if app.signer != pinned:
        raise ReleaseError("Built APK certificate does not match PB_SIGNER_SHA256")
    if previous:
        compare(inspect_apk(previous), app)
    output(sdk_tool("zipalign"), "-c", "-P", "16", "4", apk_path.resolve())
    verify_bundle(aab_path, app)

    # No files enter the publication directory before every check passes.
    out.mkdir(parents=True, exist_ok=False)
    apk_name = f"PocketBridge-{tag}.apk"
    aab_name = f"PocketBridge-{tag}.aab"
    shutil.copyfile(apk_path, out / apk_name)
    shutil.copyfile(aab_path, out / aab_name)
    (out / "APK-SIGNER-SHA256.txt").write_text(app.signer + "\n", encoding="ascii")
    names = (apk_name, aab_name, "APK-SIGNER-SHA256.txt")
    with (out / "SHA256SUMS.txt").open("w", encoding="ascii") as sums:
        for name in sorted(names):
            with (out / name).open("rb") as asset:
                sums.write(f"{hashlib.file_digest(asset, 'sha256').hexdigest()}  {name}\n")
    print(f"Verified {PACKAGE} {app.version_name} (versionCode {app.version_code}), signer {app.signer}")


def select_baseline(releases: list[dict], tag: str) -> tuple[str, dict] | None:
    current = version_tuple(tag)
    first = version_tuple(FIRST_STABLE_TAG)
    candidates = []
    for entry in releases:
        name = entry["tag_name"]
        if name == tag:
            raise ReleaseError("This tag already has a Release (including drafts); do not replace its APK")
        if entry["draft"] or entry["prerelease"] or not TAG.fullmatch(name) or version_tuple(name) < first:
            continue
        apk = [a for a in entry["assets"] if a["name"] == f"PocketBridge-{name}.apk"]
        if len(apk) != 1:
            raise ReleaseError(f"Missing or ambiguous stable APK on {name}; restore it before releasing")
        candidates.append((version_tuple(name), name, apk[0]))
    if not candidates and tag == FIRST_STABLE_TAG:
        return None
    if not any(name == FIRST_STABLE_TAG for _, name, _ in candidates):
        raise ReleaseError(f"Cannot find the first stable release {FIRST_STABLE_TAG}; do not reset the signer")
    latest = max(candidates, key=lambda candidate: candidate[0])
    if latest[0] >= current:
        raise ReleaseError(f"New stable release must be newer than {latest[1]}")
    return latest[1], latest[2]


def api(repo: str, token: str, path: str, accept: str = "application/vnd.github+json"):
    call = request.Request(f"https://api.github.com/repos/{repo}/{path}", headers={
        "Accept": accept, "User-Agent": "PocketBridge-Release", "X-GitHub-Api-Version": "2022-11-28",
    })
    # Do not forward the GitHub token to redirected asset/CDN URLs.
    call.add_unredirected_header("Authorization", "Bearer " + token)
    return request.urlopen(call, timeout=35)


def fetch_baseline(tag: str, destination: Path) -> None:
    metadata(tag)
    repo, token = os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GH_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not token:
        raise ReleaseError("Checking release history requires GITHUB_REPOSITORY and read-only GH_TOKEN")
    if destination.exists():
        raise ReleaseError("Baseline output already exists")
    releases = []
    for page in range(1, 21):
        with api(repo, token, f"releases?per_page=100&page={page}") as response:
            entries = json.load(response)
        if not isinstance(entries, list):
            raise ReleaseError("Invalid response while reading release history")
        releases.extend(entries)
        if len(entries) < 100:
            break
    else:
        raise ReleaseError("Cannot establish complete release history")
    prior = select_baseline(releases, tag)
    if not prior:
        print("First stable APK: previous debug APKs are not compatible with a new certificate")
        return
    prior_tag, asset = prior
    size = int(asset["size"])
    if not 0 < size <= MAX_APK or not isinstance(asset["id"], int):
        raise ReleaseError("Unexpected size or ID for the previous stable APK")
    with api(repo, token, f"releases/assets/{asset['id']}", "application/octet-stream") as response:
        data = response.read(size + 1)
    if len(data) != size or not data.startswith(b"PK\x03\x04"):
        raise ReleaseError("Could not download the previous stable APK")
    if asset.get("digest") and asset["digest"] != "sha256:" + hashlib.sha256(data).hexdigest():
        raise ReleaseError("Previous stable APK does not match GitHub's asset digest")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    print(f"Using {prior_tag} as the previous stable APK")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("metadata", "baseline", "stage"):
        sub.add_parser(command).add_argument("tag")
    sub.choices["metadata"].add_argument("--check-pin", action="store_true")
    sub.choices["baseline"].add_argument("destination", type=Path)
    staged = sub.choices["stage"]
    staged.add_argument("apk", type=Path)
    staged.add_argument("aab", type=Path)
    staged.add_argument("out", type=Path)
    staged.add_argument("--previous", type=Path)
    check = sub.add_parser("compare")
    check.add_argument("previous", type=Path)
    check.add_argument("candidate", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "metadata":
            name, code = metadata(args.tag)
            if args.check_pin:
                digest(os.environ.get("PB_SIGNER_SHA256", ""))
            print(f"{name} {code}")
        elif args.command == "baseline":
            fetch_baseline(args.tag, args.destination)
        elif args.command == "stage":
            stage(args.tag, args.apk, args.aab, args.out, args.previous)
        else:
            old, new = inspect_apk(args.previous), inspect_apk(args.candidate)
            compare(old, new)
            print(f"Update identity verified: {old.version_name} ({old.version_code}) -> {new.version_name} ({new.version_code}), signer {new.signer}")
    except (ReleaseError, OSError, ValueError, KeyError, TypeError, ET.ParseError) as exc:
        parser.exit(1, f"Release check failed: {exc}\n")


if __name__ == "__main__":
    main()
