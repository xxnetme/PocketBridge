"""Offline tests for the fail-closed release checks (no credentials or Android SDK)."""
from contextlib import redirect_stdout
import hashlib
from io import BytesIO, StringIO
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from release import Apk, FIRST_STABLE_TAG, PACKAGE, ReleaseError, compare, digest, metadata
from release import fetch_baseline, parse_apk_signer, parse_badging, select_baseline, stage

PIN = "ab" * 32
OTHER_PIN = "cd" * 32
BADGING = f"package: name='{PACKAGE}' versionCode='26' versionName='4.0.1'\nsdkVersion:'26'\n"
SIGNER = f"Signer #1 certificate SHA-256 digest: {PIN}\n"


def release(tag, assets=(), *, draft=False, prerelease=False):
    return {"tag_name": tag, "assets": [{"name": name} for name in assets],
            "draft": draft, "prerelease": prerelease}


def stable(tag):
    return release(tag, (f"PocketBridge-{tag}.apk", "SHA256SUMS.txt"))


class ReleaseTests(unittest.TestCase):
    def test_normalizes_only_full_sha256_certificate_pins(self):
        self.assertEqual(digest(PIN.upper()), PIN)
        self.assertEqual(digest("\n" + ":".join(["AB"] * 32) + "\n"), PIN)
        for invalid in ("", "ab", "sha256:" + PIN, ":".join(["AG"] * 32)):
            with self.subTest(invalid=invalid), self.assertRaises(ReleaseError):
                digest(invalid)

    def test_gradle_version_must_match_exact_release_tag(self):
        with TemporaryDirectory() as temp:
            gradle = Path(temp) / "build.gradle"
            gradle.write_text("    versionCode 26\n    versionName '4.0.1'\n", encoding="ascii")
            self.assertEqual(metadata(FIRST_STABLE_TAG, gradle), ("4.0.1", 26))
            for tag in ("v4.0", "v4.0.2", "v4.0.01", "4.0.1", "v4.0.1-debug", "v4.0.1/../main"):
                with self.subTest(tag=tag), self.assertRaises(ReleaseError):
                    metadata(tag, gradle)

    def test_apk_rejects_debug_wrong_package_or_invalid_version(self):
        self.assertEqual(parse_badging(BADGING), ("4.0.1", 26))
        for text in (BADGING + "application-debuggable\n", BADGING.replace(PACKAGE, "wrong.id"),
                     BADGING.replace("'26'", "'not-a-code'"), BADGING + BADGING):
            with self.subTest(text=text), self.assertRaises(ReleaseError):
                parse_badging(text)

    def test_apk_rejects_missing_or_multiple_signers(self):
        self.assertEqual(parse_apk_signer(SIGNER), PIN)
        for text in ("", SIGNER + SIGNER.replace("#1", "#2"), SIGNER.replace(PIN, "abcd")):
            with self.subTest(text=text), self.assertRaises(ReleaseError):
                parse_apk_signer(text)

    def test_update_requires_same_signer_and_higher_code(self):
        old = Apk("4.0", 25, PIN)
        compare(old, Apk("4.0.1", 26, PIN))
        for new in (Apk("4.0.1", 26, OTHER_PIN), Apk("4.0.1", 25, PIN), Apk("4.0.1", 24, PIN)):
            with self.subTest(new=new), self.assertRaises(ReleaseError):
                compare(old, new)

    def test_first_stable_release_skips_all_legacy_releases(self):
        history = [stable("v2.0"), release("v3.9", ("PocketBridge-v3.9-debug.apk",)),
                   release("v4.0", ("PocketBridge-v4.0-debug.apk",))]
        self.assertIsNone(select_baseline(history, FIRST_STABLE_TAG))
        with self.assertRaisesRegex(ReleaseError, "Cannot find the first stable release"):
            select_baseline(history, "v4.0.2")

    def test_baseline_uses_newest_numeric_tag_not_list_order(self):
        history = [stable(FIRST_STABLE_TAG), stable("v4.9"), stable("v4.10"),
                   release("v4.11", draft=True), release("v4.12", prerelease=True)]
        tag, asset = select_baseline(history, "v4.13")
        self.assertEqual((tag, asset["name"]), ("v4.10", "PocketBridge-v4.10.apk"))

    def test_refuses_old_tags_and_existing_release_including_draft(self):
        with self.assertRaisesRegex(ReleaseError, "newer"):
            select_baseline([stable(FIRST_STABLE_TAG), stable("v4.1")], "v4.0.2")
        for entry in (stable(FIRST_STABLE_TAG), release(FIRST_STABLE_TAG, draft=True)):
            with self.assertRaisesRegex(ReleaseError, "already has a Release"):
                select_baseline([entry], FIRST_STABLE_TAG)

    def test_refuses_missing_or_ambiguous_stable_baseline(self):
        for assets in ((), ("PocketBridge-v4.0.2-debug.apk",), ("PocketBridge-v4.0.2.apk",) * 2):
            with self.assertRaisesRegex(ReleaseError, "Missing or ambiguous"):
                select_baseline([stable(FIRST_STABLE_TAG), release("v4.0.2", assets)], "v4.0.3")

    @patch("release.metadata", return_value=("4.0.2", 27))
    def test_download_checks_baseline_size_and_asset_digest(self, _metadata):
        content = b"PK\x03\x04test apk bytes"
        entry = stable(FIRST_STABLE_TAG)
        entry["assets"][0].update({"id": 123, "size": len(content),
                                   "digest": "sha256:" + hashlib.sha256(content).hexdigest()})
        with TemporaryDirectory() as temp, patch.dict(os.environ, GITHUB_REPOSITORY="owner/pb", GH_TOKEN="test"):
            target = Path(temp) / "baseline.apk"
            for data in (content[:-1], content[:-1] + b"!", b"wrong response"):
                with patch("release.api", side_effect=[BytesIO(json.dumps([entry]).encode()), BytesIO(data)]), \
                     self.assertRaises(ReleaseError):
                    fetch_baseline("v4.0.2", target)
                self.assertFalse(target.exists())
            with patch("release.api", side_effect=[BytesIO(json.dumps([entry]).encode()), BytesIO(content)]), \
                 redirect_stdout(StringIO()):
                fetch_baseline("v4.0.2", target)
            self.assertEqual(target.read_bytes(), content)

    @patch("release.metadata", return_value=("4.0.2", 27))
    def test_stage_requires_previous_stable_apk_after_bootstrap(self, _metadata):
        with patch.dict(os.environ, PB_SIGNER_SHA256=PIN), self.assertRaisesRegex(ReleaseError, "previous published"):
            stage("v4.0.2", Path("unused.apk"), Path("unused.aab"), Path("unused"), None)

    @patch("release.metadata", return_value=("4.0.1", 26))
    def test_stage_never_outputs_assets_on_missing_pin_wrong_signer_or_version(self, _metadata):
        with TemporaryDirectory() as temp:
            out = Path(temp) / "assets"
            for pin, apk in (("", Apk("4.0.1", 26, PIN)), (OTHER_PIN, Apk("4.0.1", 26, PIN)),
                             (PIN, Apk("4.0.1", 25, PIN)), (PIN, Apk("4.0", 26, PIN))):
                with self.subTest(pin=pin, apk=apk), patch.dict(os.environ, PB_SIGNER_SHA256=pin), \
                     patch("release.inspect_apk", return_value=apk), self.assertRaises(ReleaseError):
                    stage(FIRST_STABLE_TAG, Path("unused.apk"), Path("unused.aab"), out, None)
                self.assertFalse(out.exists())

    @patch("release.metadata", return_value=("4.0.1", 26))
    @patch("release.inspect_apk", return_value=Apk("4.0.1", 26, PIN))
    @patch("release.sdk_tool", return_value=Path("zipalign"))
    @patch("release.output")
    @patch("release.verify_bundle")
    def test_stage_waits_for_bundle_check_and_writes_verified_checksums(self, bundle, *_mocks):
        with TemporaryDirectory() as temp, patch.dict(os.environ, PB_SIGNER_SHA256=PIN):
            folder = Path(temp)
            apk, aab = folder / "app.apk", folder / "app.aab"
            apk.write_bytes(b"pretend APK")
            aab.write_bytes(b"pretend AAB")
            out = folder / "assets"
            bundle.side_effect = ReleaseError("Bad AAB")
            with self.assertRaisesRegex(ReleaseError, "Bad AAB"):
                stage(FIRST_STABLE_TAG, apk, aab, out, None)
            self.assertFalse(out.exists())
            bundle.side_effect = None
            with redirect_stdout(StringIO()):
                stage(FIRST_STABLE_TAG, apk, aab, out, None)
            self.assertEqual(set(p.name for p in out.iterdir()), {"PocketBridge-v4.0.1.apk", "PocketBridge-v4.0.1.aab",
                                                                 "APK-SIGNER-SHA256.txt", "SHA256SUMS.txt"})
            sums = (out / "SHA256SUMS.txt").read_text().splitlines()
            self.assertEqual(len(sums), 3)
            for line in sums:
                expected, name = line.split("  ", 1)
                self.assertEqual(hashlib.sha256((out / name).read_bytes()).hexdigest(), expected)


if __name__ == "__main__":
    unittest.main()
