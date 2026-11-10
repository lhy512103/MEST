"""Offline tests: python -B -m unittest discover -s .github/scripts -p test_release_metadata.py."""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

_SPEC = importlib.util.spec_from_file_location("release_metadata", Path(__file__).with_name("release_metadata.py"))
release = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(release)


class ReleaseMetadataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.properties = self.root / "gradle.properties"
        self.changelog = self.root / "CHANGELOG.md"
        self.properties.write_text(
            "# comment\nmod_id=mesplicedterminal\nmod_version=0.0.3.1\nminecraft_version=1.21.1\n",
            encoding="utf-8",
        )
        self.changelog.write_text(
            "# Changelog\n\n## Unreleased\nnot released\n\n## v0.0.3.1\n\n"
            "### English\nFixed slots.\n\n### 中文\n修复：保存槽位。\n\n## v0.0.3.0\nold\n",
            encoding="utf-8",
        )
        self.metadata = release.build_metadata("v0.0.3.1", "mesplicedterminal", "1.21.1")
        self.jar = self.root / self.metadata["expected_jar"]

    def make_jar(self, version="0.0.3.1", mod_id="mesplicedterminal", classes=True,
                 descriptor=True, toml=None):
        with zipfile.ZipFile(self.jar, "w", zipfile.ZIP_DEFLATED) as archive:
            if descriptor:
                archive.writestr("META-INF/neoforge.mods.toml", toml if toml is not None else
                                 f'[[mods]]\nmodId = "{mod_id}"\nversion = "{version}"\n')
            if classes:
                archive.writestr("com/lhy/mest/nested/Example.class", b"\xca\xfe\xba\xbe\x00\x00")
        return self.jar

    def test_four_segment_prepare_preserves_languages_and_subheadings(self):
        metadata, notes = release.prepare_metadata("v0.0.3.1", self.properties, self.changelog)
        self.assertEqual(metadata, self.metadata)
        self.assertEqual(metadata["release_type"], "release")
        self.assertIs(metadata["prerelease"], False)
        self.assertEqual(notes, "### English\nFixed slots.\n\n### 中文\n修复：保存槽位。\n")

    def test_tags_and_prerelease(self):
        for suffix, release_type in [("", "release"), ("-alpha", "alpha"), ("-alpha.1", "alpha"),
                                     ("-beta", "beta"), ("-beta.2", "beta"), ("-rc", "beta"), ("-rc.1", "beta")]:
            for prefix in ("", "v"):
                with self.subTest(suffix=suffix, prefix=prefix):
                    version = "0.0.3.1" + suffix
                    metadata = release.build_metadata(prefix + version, "mest", "1.21.1")
                    self.assertEqual(metadata["version"], version)
                    self.assertEqual(metadata["release_type"], release_type)
                    self.assertIs(metadata["prerelease"], bool(suffix))
                    self.assertEqual(metadata["expected_jar"], f"mest-{version}.jar")

    def test_invalid_tags(self):
        for tag in ("", "v", "v1", "1.2.", "v1..2", "vv1.2", "V1.2", "refs/tags/v1.2",
                    "v1.2\n", " v1.2", "1.2-beta.foo", "1.2-dev", "1.2+build", "１.２", None):
            with self.subTest(tag=tag), self.assertRaises(release.ReleaseMetadataError):
                release.parse_tag(tag)

    def test_tag_must_match_property_exactly(self):
        for tag in ("0.0.3", "v0.0.3.01", "0.0.3.1-alpha.1"):
            with self.subTest(tag=tag), self.assertRaises(release.ReleaseMetadataError):
                release.prepare_metadata(tag, self.properties, self.changelog)

    def test_prerelease_prepare(self):
        self.properties.write_text("mod_id=mest\nmod_version=1.2.3-rc.1\nminecraft_version=1.21.1\n", encoding="utf-8")
        self.changelog.write_text("##1.2.3-rc.1\n候选版本\n", encoding="utf-8")
        metadata, notes = release.prepare_metadata("v1.2.3-rc.1", self.properties, self.changelog)
        self.assertEqual(metadata["release_type"], "beta")
        self.assertEqual(notes, "候选版本\n")

    def test_missing_duplicate_empty_and_unreleased_sections(self):
        for text in ("## Unreleased\nnew\n", "## v0.0.3.10\nwrong\n", "## v0.0.3.1\n \n",
                     "## v0.0.3.1\n\n## Unreleased\nnew\n",
                     "## v0.0.3.1\none\n## 0.0.3.1\ntwo\n",
                     "## v0.0.3.1\none\n##v0.0.3.1\ntwo\n",
                     "## v0.0.3.1 - date\nnot exact\n"):
            with self.subTest(text=text), self.assertRaises(release.ReleaseMetadataError):
                release.extract_release_notes(text, "0.0.3.1")

    def test_fenced_headings_are_not_sections(self):
        text = "```md\n## v0.0.3.1\n```\n## v0.0.3.1\nexample:\n~~~\n## other\n~~~\n### 子标题\n内容\n## next\nstop"
        notes = release.extract_release_notes(text, "0.0.3.1")
        self.assertIn("## other", notes)
        self.assertIn("### 子标题\n内容", notes)
        self.assertNotIn("stop", notes)

    def test_properties_missing_duplicate_and_nonliteral(self):
        for content in ("mod_version=0.0.3.1\n", self.properties.read_text() + "mod_id=mest\n",
                        "mod_version=0.0.3.1\\\nmod_id=mest\nminecraft_version=1.21.1\n"):
            with self.subTest(content=content):
                self.properties.write_text(content, encoding="utf-8")
                with self.assertRaises(release.ReleaseMetadataError):
                    release.read_properties(self.properties)

    def test_valid_jar_returns_digest_without_metadata_mutation(self):
        self.make_jar()
        original = dict(self.metadata)
        artifact = release.verify_jar(self.jar, self.metadata)
        self.assertEqual(artifact, {"filename": self.jar.name, "size": self.jar.stat().st_size,
                                    "sha256": hashlib.sha256(self.jar.read_bytes()).hexdigest()})
        self.assertEqual(self.metadata, original)

    def test_missing_empty_fake_and_empty_zip(self):
        with self.assertRaises(release.ReleaseMetadataError):
            release.verify_jar(self.jar, self.metadata)
        for content in (b"", b"not a zip", b"PK\x03\x04fake zip"):
            with self.subTest(content=content):
                self.jar.write_bytes(content)
                with self.assertRaises(release.ReleaseMetadataError):
                    release.verify_jar(self.jar, self.metadata)
        with zipfile.ZipFile(self.jar, "w"):
            pass
        with self.assertRaises(release.ReleaseMetadataError):
            release.verify_jar(self.jar, self.metadata)

    def test_wrong_version_mod_id_or_missing_contents(self):
        cases = [{"version": "0.0.3"}, {"version": "${file.jarVersion}"}, {"mod_id": "other"},
                 {"classes": False}, {"descriptor": False}, {"toml": "not valid TOML"},
                 {"toml": '[mods]\nmodId="mesplicedterminal"\nversion="0.0.3.1"'},
                 {"toml": '[[mods]]\nmodId="mesplicedterminal"\nversion="0.0.3.1"\n' * 2}]
        for options in cases:
            with self.subTest(options=options):
                self.make_jar(**options)
                with self.assertRaises(release.ReleaseMetadataError):
                    release.verify_jar(self.jar, self.metadata)

    def test_wrong_and_classifier_filenames(self):
        self.make_jar()
        for suffix in ("-sources.jar", "-javadoc.jar", "-dev.jar", ".JAR", "-other.jar"):
            path = self.root / ("mesplicedterminal-0.0.3.1" + suffix)
            path.write_bytes(self.jar.read_bytes())
            with self.subTest(suffix=suffix), self.assertRaises(release.ReleaseMetadataError):
                release.verify_jar(path, self.metadata)

    def test_empty_or_wrong_package_class(self):
        for name, data in (("com/lhy/mest/Empty.class", b""), ("elsewhere/Other.class", b"123")):
            self.make_jar(classes=False)
            with zipfile.ZipFile(self.jar, "a") as archive:
                archive.writestr(name, data)
            with self.subTest(name=name), self.assertRaises(release.ReleaseMetadataError):
                release.verify_jar(self.jar, self.metadata)

    def test_corrupt_zip_member_crc(self):
        self.make_jar()
        with zipfile.ZipFile(self.jar, "a", zipfile.ZIP_STORED) as archive:
            archive.writestr("crc-test.txt", b"UNIQUE_CRC_TEST_CONTENT")
        content = self.jar.read_bytes().replace(b"UNIQUE_CRC_TEST_CONTENT", b"BROKEN_CRC_TEST_CONTENT")
        self.jar.write_bytes(content)
        with self.assertRaises(release.ReleaseMetadataError):
            release.verify_jar(self.jar, self.metadata)

    def test_duplicate_zip_members(self):
        self.make_jar()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.jar, "a") as archive:
                archive.writestr("com/lhy/mest/nested/Example.class", b"123")
        with self.assertRaises(release.ReleaseMetadataError):
            release.verify_jar(self.jar, self.metadata)

    def test_tampered_metadata(self):
        self.make_jar()
        for key, value in (("version", "9.9"), ("expected_jar", "../other.jar"), ("prerelease", 0),
                           ("release_type", "alpha"), ("mod_id", "../mest")):
            with self.subTest(key=key), self.assertRaises(release.ReleaseMetadataError):
                release.verify_jar(self.jar, {**self.metadata, key: value})

    def test_cli_prepare_verify_and_artifact_json(self):
        output = self.root / "output"
        self.assertEqual(release.main(["prepare", "--tag", "v0.0.3.1", "--properties", str(self.properties),
                                       "--changelog", str(self.changelog), "--output-dir", str(output)]), 0)
        metadata_path = output / "metadata.json"
        original = metadata_path.read_bytes()
        self.assertEqual(json.loads(original), self.metadata)
        self.assertIn("修复", (output / "release-notes.md").read_text(encoding="utf-8"))
        self.make_jar()
        artifact_path = output / "artifact.json"
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            result = release.main(["verify", "--metadata", str(metadata_path), "--jar", str(self.jar),
                                   "--output-json", str(artifact_path)])
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(stdout.getvalue()), json.loads(artifact_path.read_bytes()))
        self.assertEqual(metadata_path.read_bytes(), original)
        with redirect_stderr(io.StringIO()):
            self.assertEqual(release.main(["verify", "--metadata", str(metadata_path), "--jar", str(self.jar),
                                           "--output-json", str(metadata_path)]), 1)
        self.assertEqual(metadata_path.read_bytes(), original)

    def test_cli_failure_creates_no_output(self):
        output = self.root / "output"
        with redirect_stderr(io.StringIO()) as stderr:
            result = release.main(["prepare", "--tag", "wrong", "--properties", str(self.properties),
                                   "--changelog", str(self.changelog), "--output-dir", str(output)])
        self.assertEqual(result, 1)
        self.assertIn("Invalid release tag", stderr.getvalue())
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
