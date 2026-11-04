"""Check release numbering without contacting GitHub or modifying the checkout."""
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("next-version.sh").resolve()


class ReleaseVersionTest(unittest.TestCase):
    def test_publications_increment_independent_component_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                subprocess.run(["git", *args], cwd=directory, check=True,
                               capture_output=True)

            def version(component):
                return subprocess.check_output(
                    ["bash", str(SCRIPT), component], cwd=directory, text=True
                ).strip()

            git("init", "-q")
            git("-c", "user.name=CI", "-c", "user.email=ci@example.invalid",
                "commit", "--allow-empty", "-qm", "test")
            self.assertEqual(version("android"), "0.1.1")
            self.assertEqual(version("server"), "0.1.1")
            for tag in ("android-v0.1.9", "android-v0.1.11", "server-v0.1.40",
                        "android-v8.0.0-beta", "android-vinvalid"):
                git("tag", tag)
            self.assertEqual(version("android"), "0.1.12")
            self.assertEqual(version("server"), "0.1.41")
            # Validation builds must not consume a version.
            self.assertEqual(version("android"), "0.1.12")
            git("tag", "android-v0.1.12")
            self.assertEqual(version("android"), "0.1.13")
            git("tag", "android-v1.2.3")
            self.assertEqual(version("android"), "1.2.4")


if __name__ == "__main__":
    unittest.main()
