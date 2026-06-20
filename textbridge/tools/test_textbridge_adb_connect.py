#!/usr/bin/env python3
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


HELPER = Path(__file__).with_name("textbridge-adb-connect")


class TextBridgeAdbConnectTest(unittest.TestCase):
    def run_helper(
        self,
        *args: str,
        devices_output: str = "List of devices attached\n",
        reverse_list_output: str = "",
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            log_path = temp_path / "adb.log"
            adb_path = temp_path / "adb"
            adb_path.write_text(
                f"#!{shutil.which('bash') or '/bin/sh'}\n" +
                textwrap.dedent(
                    """\
                    set -euo pipefail

                    if [ "$1" = "devices" ]; then
                      printf "%b" "$ADB_DEVICES_OUTPUT"
                      exit 0
                    fi

                    printf '%s\\n' "$*" >> "$ADB_LOG"

                    if [ "$1" = "-s" ] && [ "$3" = "reverse" ] && [ "${4:-}" = "--list" ]; then
                      printf "%b" "$ADB_REVERSE_LIST_OUTPUT"
                    fi
                    """
                ),
                encoding="utf-8",
            )
            adb_path.chmod(0o755)

            env = os.environ.copy()
            env["PATH"] = f"{temp_path}{os.pathsep}{env['PATH']}"
            env["ADB_LOG"] = str(log_path)
            env["ADB_DEVICES_OUTPUT"] = devices_output
            env["ADB_REVERSE_LIST_OUTPUT"] = reverse_list_output

            result = subprocess.run(
                [str(HELPER), *args],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
                check=False,
            )
            log = log_path.read_text(encoding="utf-8") if log_path.exists() else ""
            return result, log

    def test_help(self) -> None:
        result, _ = self.run_helper("--help")

        self.assertEqual(0, result.returncode)
        self.assertIn("textbridge-adb-connect", result.stdout)

    def test_rejects_invalid_port(self) -> None:
        result, _ = self.run_helper("--port", "70000")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("port must be between", result.stderr)

    def test_fails_when_no_authorized_device_exists(self) -> None:
        result, _ = self.run_helper()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("no authorized adb device", result.stderr)

    def test_connects_single_device_with_default_port(self) -> None:
        result, log = self.run_helper(devices_output="List of devices attached\nPHONE123\tdevice\n")

        self.assertEqual(0, result.returncode)
        self.assertEqual("-s PHONE123 reverse tcp:17321 tcp:17321\n", log)
        self.assertIn("127.0.0.1:17321", result.stdout)

    def test_requires_serial_for_multiple_devices(self) -> None:
        result, _ = self.run_helper(
            devices_output="List of devices attached\nPHONE1\tdevice\nPHONE2\tdevice\n",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("pass --serial DEVICE", result.stderr)

    def test_uses_explicit_serial_and_custom_port(self) -> None:
        result, log = self.run_helper("--serial", "PHONE2", "--port", "18000")

        self.assertEqual(0, result.returncode)
        self.assertEqual("-s PHONE2 reverse tcp:18000 tcp:18000\n", log)

    def test_remove_reverse_for_selected_port(self) -> None:
        result, log = self.run_helper(
            "--port",
            "18000",
            "--remove",
            devices_output="List of devices attached\nPHONE123\tdevice\n",
        )

        self.assertEqual(0, result.returncode)
        self.assertEqual("-s PHONE123 reverse --remove tcp:18000\n", log)

    def test_list_reverse_for_selected_device(self) -> None:
        result, log = self.run_helper(
            "--list",
            "--serial",
            "PHONE123",
            reverse_list_output="PHONE123 tcp:17321 tcp:17321\n",
        )

        self.assertEqual(0, result.returncode)
        self.assertEqual("-s PHONE123 reverse --list\n", log)
        self.assertIn("PHONE123 tcp:17321", result.stdout)


if __name__ == "__main__":
    unittest.main()
