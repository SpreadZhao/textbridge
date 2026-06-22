#!/usr/bin/env python3

from __future__ import annotations

import json
import socket
import tempfile
import threading
import unittest
import uuid
from pathlib import Path

import textbridge_bluetooth_server
import textbridge_server


class BluetoothProtocolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        runtime = Path(self.tmp.name) / "runtime"
        self.config = textbridge_server.ServerConfig(
            listen_host="127.0.0.1",
            listen_port=0,
            token="secret-token",
            max_text_bytes=64,
            request_timeout_ms=200,
            runtime_dir=runtime,
            fcitx_socket=runtime / "fcitx.sock",
        )
        self.original_forward = textbridge_server.forward_to_fcitx

    def tearDown(self) -> None:
        textbridge_server.forward_to_fcitx = self.original_forward
        self.tmp.cleanup()

    def frame(self, payload: dict[str, object]) -> dict[str, object]:
        return textbridge_bluetooth_server.handle_frame(self.config, json.dumps(payload).encode("utf-8"))

    def test_commit_success(self) -> None:
        request_id = str(uuid.uuid4())
        captured: dict[str, object] = {}

        def fake_forward(config, payload):
            captured.update(payload)
            return {"v": 1, "id": payload["id"], "status": "ok", "target_program": "test.editor"}

        textbridge_server.forward_to_fcitx = fake_forward
        response = self.frame(
            {
                "v": 1,
                "id": request_id,
                "token": "secret-token",
                "action": "commit",
                "text": "hello",
            }
        )

        self.assertEqual(response["id"], request_id)
        self.assertEqual(response["status"], "ok")
        self.assertEqual(response["target_program"], "test.editor")
        self.assertEqual(captured["v"], 1)
        self.assertEqual(captured["id"], request_id)
        self.assertEqual(captured["text"], "hello")

    def test_key_success_normalizes_modifiers(self) -> None:
        request_id = str(uuid.uuid4())
        captured: dict[str, object] = {}

        def fake_forward(config, payload):
            captured.update(payload)
            return {"v": 1, "id": payload["id"], "status": "ok"}

        textbridge_server.forward_to_fcitx = fake_forward
        response = self.frame(
            {
                "v": 1,
                "id": request_id,
                "token": "secret-token",
                "action": "key",
                "key": "V",
                "modifiers": ["Alt", "Control", "Control"],
            }
        )

        self.assertEqual(response["status"], "ok")
        self.assertEqual(captured["action"], "key")
        self.assertEqual(captured["key"], "V")
        self.assertEqual(captured["modifiers"], ["Control", "Alt"])

    def test_unauthorized_does_not_forward(self) -> None:
        def fake_forward(config, payload):
            raise AssertionError("unauthorized requests must not reach fcitx")

        textbridge_server.forward_to_fcitx = fake_forward
        response = self.frame(
            {
                "v": 1,
                "id": str(uuid.uuid4()),
                "token": "wrong",
                "action": "commit",
                "text": "hello",
            }
        )

        self.assertEqual(response["status"], "unauthorized")

    def test_text_too_large(self) -> None:
        response = self.frame(
            {
                "v": 1,
                "id": str(uuid.uuid4()),
                "token": "secret-token",
                "action": "commit",
                "text": "x" * 65,
            }
        )

        self.assertEqual(response["status"], "text_too_large")

    def test_invalid_key(self) -> None:
        response = self.frame(
            {
                "v": 1,
                "id": str(uuid.uuid4()),
                "token": "secret-token",
                "action": "key",
                "key": "F13",
                "modifiers": [],
            }
        )

        self.assertEqual(response["status"], "invalid_key")

    def test_socket_roundtrip(self) -> None:
        request_id = str(uuid.uuid4())

        def fake_forward(config, payload):
            return {"v": 1, "id": payload["id"], "status": "ok"}

        textbridge_server.forward_to_fcitx = fake_forward
        server, client = socket.socketpair()

        def serve() -> None:
            with server:
                textbridge_bluetooth_server.handle_socket(self.config, server, remote="test")

        thread = threading.Thread(target=serve)
        thread.start()
        try:
            request = {
                "v": 1,
                "id": request_id,
                "token": "secret-token",
                "action": "commit",
                "text": "hello",
            }
            client.sendall(json.dumps(request).encode("utf-8") + b"\n")
            data = bytearray()
            while True:
                chunk = client.recv(1024)
                if not chunk:
                    break
                data.extend(chunk)
                if b"\n" in chunk:
                    break
        finally:
            client.close()
            thread.join(timeout=2)

        response = json.loads(bytes(data).strip().decode("utf-8"))
        self.assertEqual(response["id"], request_id)
        self.assertEqual(response["status"], "ok")


if __name__ == "__main__":
    unittest.main()
