from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "make-evolution-release.py"
SPEC = importlib.util.spec_from_file_location("protocolobelisk_release", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class EvolutionReleaseTest(unittest.TestCase):
    def test_component_inventory_is_lobby_first_only(self) -> None:
        self.assertEqual(
            (
                "ProtocolObelisk-Paper-1.9.16-EVOLUTION.jar",
                "ProtocolObelisk-Velocity-1.9.16-EVOLUTION.jar",
            ),
            release.JAR_NAMES,
        )
        folded = "\n".join(sorted(release.RELEASE_PAYLOAD_NAMES)).casefold()
        self.assertNotIn("youer", folded)
        self.assertNotIn("crucible", folded)

    def test_zip_generation_is_byte_reproducible_and_canonical(self) -> None:
        entries = {
            "z-last.txt": (b"last", 0o644),
            "a-first.sh": (b"#!/bin/sh\n", 0o755),
        }
        first = release.make_zip(entries, stored=False)
        second = release.make_zip(dict(reversed(tuple(entries.items()))), stored=False)
        self.assertEqual(first, second)
        inspected, infos = release.inspect_zip(
            first, "test.zip", require_canonical_metadata=True
        )
        self.assertEqual({"a-first.sh", "z-last.txt"}, set(inspected))
        self.assertTrue(all(info.date_time == release.FIXED_TIMESTAMP for info in infos.values()))

    def test_unsafe_archive_path_is_rejected(self) -> None:
        with self.assertRaises(release.ReleaseError):
            release.canonical_archive_name("../escape.txt")
        with self.assertRaises(release.ReleaseError):
            release.canonical_archive_name("C:/alternate-stream.txt")
        with self.assertRaises(release.ReleaseError):
            release.canonical_archive_name("absolute\\windows.txt")
        with self.assertRaisesRegex(release.ReleaseError, "also an ancestor"):
            release.make_zip(
                {
                    "file": (b"not a directory", 0o644),
                    "file/child.txt": (b"ambiguous extraction", 0o644),
                },
                stored=True,
            )

    def test_operational_youer_class_is_rejected_from_velocity_jar(self) -> None:
        payload = self._jar_payload("velocity")
        entries, _ = release.inspect_zip(
            payload,
            release.JAR_NAMES[1],
            require_canonical_metadata=True,
            expected_timestamp=release.GRADLE_FIXED_TIMESTAMP,
        )
        entries["br/com/atmbrasil/lobby/velocity/YouerAgentControlServer.class"] = (
            self._class_file()
        )
        contaminated = release.make_zip(
            {name: (data, 0o644) for name, data in entries.items()},
            stored=True,
            timestamp=release.GRADLE_FIXED_TIMESTAMP,
        )
        with self.assertRaisesRegex(release.ReleaseError, "operational code leaked"):
            release.validate_jar(contaminated, release.JAR_NAMES[1])

    def test_source_snapshot_excludes_stale_tools_binaries_and_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source"
            self._source_fixture(source)
            generated = {
                "tools/test-evolution-tooling.py": b"stale",
                "tools/Loose.class": b"compiled",
                "tools/unreviewed.jar": b"binary",
                "tools/.env.production": b"TOKEN=not-for-source",
                "tools/private.pem": b"not-a-real-key",
            }
            for name, payload in generated.items():
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
            wrapper = source / "gradle/wrapper/gradle-wrapper.jar"
            wrapper.parent.mkdir(parents=True, exist_ok=True)
            wrapper.write_bytes(b"reviewed wrapper fixture")

            entries = release.source_entries(source)

            for name in generated:
                self.assertNotIn(name, entries)
            self.assertIn("gradle/wrapper/gradle-wrapper.jar", entries)

    def test_two_complete_publications_are_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            jars = root / "jars"
            first = root / "publication-a"
            second = root / "publication-b"
            self._source_fixture(source)
            self._jar_fixture(jars)

            first_files = release.create_release(source, jars, first)
            second_files = release.create_release(source, jars, second)
            self.assertEqual(first_files, second_files)
            release.validate_publication(first)
            release.validate_publication(second)

            source_entries, _ = release.inspect_zip(
                first_files[release.SOURCE_NAME],
                release.SOURCE_NAME,
                require_canonical_metadata=True,
            )
            self.assertFalse(any(name.startswith("youer-agent/") for name in source_entries))
            self.assertFalse(any(name.startswith("crucible-companion/") for name in source_entries))
            self.assertNotIn("examples/velocity-youer-agent-control.properties", source_entries)

            release_entries, _ = release.inspect_zip(
                first_files[release.RELEASE_NAME],
                release.RELEASE_NAME,
                require_canonical_metadata=True,
            )
            self.assertEqual(set(release.RELEASE_PAYLOAD_NAMES), set(release_entries))
            for integration in release.RELEASE_INTEGRATIONS:
                self.assertIn(integration, source_entries)
                self.assertEqual(release_entries[integration], source_entries[integration])

            (first / "ProtocolObelisk-Youer-1.9.16-EVOLUTION.jar").write_bytes(b"forbidden")
            with self.assertRaisesRegex(release.ReleaseError, "exact directory tree mismatch"):
                release.validate_publication(first)

    @staticmethod
    def _class_file() -> bytes:
        return b"\xca\xfe\xba\xbe\x00\x00\x00\x41"

    @classmethod
    def _jar_payload(cls, component: str) -> bytes:
        version = release.VERSION
        if component == "paper":
            name = release.JAR_NAMES[0]
            title = "ProtocolObelisk - Paper"
            module = "br.com.atmbrasil.lobby.paper"
            descriptor_name = "plugin.yml"
            descriptor = (
                f"name: ProtocolObelisk\nversion: {version}\n"
                "main: br.com.atmbrasil.lobby.paper.Atm10LobbyPaperPlugin\n"
                "api-version: '1.21'\n"
            ).encode()
            classes = {
                "br/com/atmbrasil/lobby/paper/Atm10LobbyPaperPlugin.class": cls._class_file(),
                "br/com/atmbrasil/lobby/paper/PaperNecroTempusBridge.class": cls._class_file(),
            }
        else:
            name = release.JAR_NAMES[1]
            title = "ProtocolObelisk - Velocity"
            module = "br.com.atmbrasil.lobby.velocity"
            descriptor_name = "velocity-plugin.json"
            descriptor = (
                '{"id":"protocolobelisk","name":"ProtocolObelisk",'
                f'"version":"{version}",'
                '"main":"br.com.atmbrasil.lobby.velocity.Atm10LobbyVelocityPlugin"}'
            ).encode()
            classes = {
                "br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.class": cls._class_file()
            }
        manifest = (
            "Manifest-Version: 1.0\r\n"
            f"Implementation-Title: {title}\r\n"
            f"Implementation-Version: {version}\r\n"
            f"Automatic-Module-Name: {module}\r\n\r\n"
        ).encode()
        entries = {
            "META-INF/MANIFEST.MF": (manifest, 0o644),
            descriptor_name: (descriptor, 0o644),
            **{entry: (payload, 0o644) for entry, payload in classes.items()},
        }
        payload = release.make_zip(
            entries,
            stored=True,
            timestamp=release.GRADLE_FIXED_TIMESTAMP,
        )
        release.validate_jar(payload, name)
        return payload

    @classmethod
    def _jar_fixture(cls, directory: Path) -> None:
        directory.mkdir()
        payloads = {
            release.JAR_NAMES[0]: cls._jar_payload("paper"),
            release.JAR_NAMES[1]: cls._jar_payload("velocity"),
        }
        for name, payload in payloads.items():
            (directory / name).write_bytes(payload)
        (directory / "CHECKSUMS.sha256").write_bytes(
            release.canonical_checksums(payloads)
        )

    @staticmethod
    def _source_fixture(root: Path) -> None:
        root.mkdir()
        for name in sorted(release.SOURCE_ROOT_FILES):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if name == "gradle.properties":
                path.write_text(f"protocolObeliskVersion={release.VERSION}\n", encoding="utf-8")
            else:
                path.write_text(f"fixture for {name}\n", encoding="utf-8")
        for directory in release.SOURCE_ROOTS:
            (root / directory).mkdir(parents=True, exist_ok=True)
        evidence_root = root / "build-evidence" / "1.9.16-EVOLUTION"
        evidence_root.mkdir(parents=True)
        for name in release.RELEASE_EVIDENCE:
            path = root / name
            path.write_text(f"fixture for {name}\n", encoding="utf-8")
        (root / "common" / "Example.java").write_text("final class Example {}\n")
        (root / "examples" / "velocity.compatibility.toml").write_text("enabled=true\n")
        (root / "examples" / "velocity-youer-agent-control.properties").write_text(
            "enabled=true\n"
        )
        (root / "youer-agent").mkdir()
        (root / "youer-agent" / "README.md").write_text("must stay out\n")
        (root / "crucible-companion").mkdir()
        (root / "crucible-companion" / "README.md").write_text("must stay out\n")


if __name__ == "__main__":
    unittest.main()
