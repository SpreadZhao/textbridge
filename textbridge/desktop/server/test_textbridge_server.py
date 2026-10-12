#!/usr/bin/env python3

from __future__ import annotations

import http.client
import json
import os
import socket
import tempfile
import threading
import unittest
import uuid
from pathlib import Path

import textbridge_server


class ConfigTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.config_path = Path(self.tmp.name) / "server.json"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write_config(self, payload: dict[str, object]) -> None:
        base: dict[str, object] = {
            "listen_host": "127.0.0.1",
            "listen_port": 0,
        }
        base.update(payload)
        self.config_path.write_text(json.dumps(base), encoding="utf-8")

    def test_load_config_without_token(self) -> None:
        self.write_config({})
        config = textbridge_server.load_config(self.config_path)
        self.assertEqual(config.listen_host, "127.0.0.1")
        self.assertFalse(hasattr(config, "token"))

    def test_load_config_ignores_legacy_token_fields(self) -> None:
        for payload in (
            {"token": "legacy-token"},
            {"token": ""},
            {"token_file": str(Path(self.tmp.name) / "missing")},
            {"token_file": "missing", "token": "legacy-token"},
        ):
            with self.subTest(payload=payload):
                self.write_config(payload)
                config = textbridge_server.load_config(self.config_path)
                self.assertEqual(config.listen_port, 0)
                self.assertFalse(hasattr(config, "token"))

    def test_init_config_has_no_token(self) -> None:
        textbridge_server.init_config(self.config_path, "127.0.0.1", 17321)
        raw = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertNotIn("token", raw)
        self.assertNotIn("token_file", raw)
        self.assertEqual(textbridge_server.load_config(self.config_path).listen_port, 17321)


class FakeFcitxSocket:
    def __init__(
        self,
        socket_path: Path,
        status: str = "ok",
        target_program: str = "fake.editor",
        response_id: str | None = None,
        response_version: int = 1,
    ) -> None:
        self.socket_path = socket_path
        self.status = status
        self.target_program = target_program
        self.response_id = response_id
        self.response_version = response_version
        self.received_payload: dict[str, object] | None = None
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)

    def __enter__(self) -> "FakeFcitxSocket":
        self.socket_path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        try:
            self.socket_path.unlink()
        except FileNotFoundError:
            pass
        self.sock.bind(str(self.socket_path))
        os.chmod(self.socket_path, 0o600)
        self.thread.start()
        self.ready.wait(timeout=2)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.sock.close()
        self.thread.join(timeout=2)
        try:
            self.socket_path.unlink()
        except FileNotFoundError:
            pass

    def _run(self) -> None:
        self.ready.set()
        try:
            data, client_path = self.sock.recvfrom(65536)
            payload = json.loads(data.decode("utf-8"))
            self.received_payload = payload
            response = {
                "v": self.response_version,
                "id": self.response_id or payload["id"],
                "status": self.status,
                "target_program": self.target_program,
            }
            self.sock.sendto(json.dumps(response).encode("utf-8"), client_path)
        except OSError:
            pass


class ServerProtocolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        runtime = Path(self.tmp.name) / "runtime"
        self.config = textbridge_server.ServerConfig(
            listen_host="127.0.0.1",
            listen_port=0,
            max_text_bytes=64,
            request_timeout_ms=200,
            runtime_dir=runtime,
            fcitx_socket=runtime / "fcitx.sock",
        )
        self.original_forward = textbridge_server.forward_to_fcitx

    def tearDown(self) -> None:
        textbridge_server.forward_to_fcitx = self.original_forward
        self.tmp.cleanup()

    def request(
        self,
        payload: dict[str, object],
        path: str = "/v1/commit",
        authorization: str | None = None,
    ) -> tuple[int, dict[str, object]]:
        return self.raw_request(json.dumps(payload).encode("utf-8"), path=path, authorization=authorization)

    def raw_request(
        self,
        body_bytes: bytes,
        path: str = "/v1/commit",
        authorization: str | None = None,
    ) -> tuple[int, dict[str, object]]:
        handler = textbridge_server.make_handler(self.config)
        server = textbridge_server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            conn.request(
                "POST",
                path,
                body=body_bytes,
                headers={
                    **({"Authorization": authorization} if authorization is not None else {}),
                    "Content-Type": "application/json; charset=utf-8",
                },
            )
            response = conn.getresponse()
            body = json.loads(response.read().decode("utf-8"))
            return response.status, body
        finally:
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()

    def test_success(self) -> None:
        request_id = str(uuid.uuid4())

        def fake_forward(config, payload):
            self.assertEqual(payload["text"], "hello")
            return {"v": 1, "id": payload["id"], "status": "ok", "target_program": "test"}

        textbridge_server.forward_to_fcitx = fake_forward
        status, body = self.request({"id": request_id, "text": "hello"})
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "ok")
        self.assertEqual(body["target_program"], "test")

    def test_http_to_unix_socket_roundtrip(self) -> None:
        request_id = str(uuid.uuid4())
        text = "中文 ASCII emoji 😀\nsecond line"

        with FakeFcitxSocket(self.config.fcitx_socket) as fake_fcitx:
            status, body = self.request({"id": request_id, "text": text})

        self.assertEqual(status, 200)
        self.assertEqual(body["id"], request_id)
        self.assertEqual(body["status"], "ok")
        self.assertEqual(body["target_program"], "fake.editor")
        self.assertIsNotNone(fake_fcitx.received_payload)
        self.assertEqual(fake_fcitx.received_payload["v"], 1)
        self.assertEqual(fake_fcitx.received_payload["id"], request_id)
        self.assertEqual(fake_fcitx.received_payload["text"], text)

    def test_legacy_authorization_is_ignored(self) -> None:
        for path, payload in (
            ("/v1/commit", {"text": "hello"}),
            ("/v1/key", {"key": "Return", "modifiers": []}),
        ):
            with self.subTest(path=path), FakeFcitxSocket(self.config.fcitx_socket):
                status, body = self.request(
                    {"id": str(uuid.uuid4()), **payload},
                    path=path,
                    authorization="Bearer wrong",
                )
            self.assertEqual(status, 200)
            self.assertEqual(body["status"], "ok")

    def test_addon_user_rejection_is_preserved(self) -> None:
        with FakeFcitxSocket(self.config.fcitx_socket, status="unauthorized"):
            status, body = self.request({"id": str(uuid.uuid4()), "text": "hello"})
        self.assertEqual(status, 401)
        self.assertEqual(body["status"], "unauthorized")

    def test_fcitx_unavailable_without_socket(self) -> None:
        status, body = self.request({"id": str(uuid.uuid4()), "text": "hello"})
        self.assertEqual(status, 503)
        self.assertEqual(body["status"], "fcitx_unavailable")

    def test_busy_composing_maps_to_conflict(self) -> None:
        request_id = str(uuid.uuid4())
        with FakeFcitxSocket(self.config.fcitx_socket, status="busy_composing"):
            status, body = self.request({"id": request_id, "text": "hello"})

        self.assertEqual(status, 409)
        self.assertEqual(body["status"], "busy_composing")

    def test_sensitive_field_maps_to_forbidden(self) -> None:
        request_id = str(uuid.uuid4())
        with FakeFcitxSocket(self.config.fcitx_socket, status="sensitive_field"):
            status, body = self.request({"id": request_id, "text": "hello"})

        self.assertEqual(status, 403)
        self.assertEqual(body["status"], "sensitive_field")

    def test_mismatched_addon_response_id_is_unavailable(self) -> None:
        with FakeFcitxSocket(self.config.fcitx_socket, response_id=str(uuid.uuid4())):
            status, body = self.request({"id": str(uuid.uuid4()), "text": "hello"})

        self.assertEqual(status, 503)
        self.assertEqual(body["status"], "fcitx_unavailable")

    def test_unknown_addon_status_is_unavailable(self) -> None:
        with FakeFcitxSocket(self.config.fcitx_socket, status="unknown_status"):
            status, body = self.request({"id": str(uuid.uuid4()), "text": "hello"})

        self.assertEqual(status, 503)
        self.assertEqual(body["status"], "fcitx_unavailable")

    def test_text_too_large(self) -> None:
        status, body = self.request({"id": str(uuid.uuid4()), "text": "x" * 65})
        self.assertEqual(status, 413)
        self.assertEqual(body["status"], "text_too_large")

    def test_empty_text_is_invalid(self) -> None:
        status, body = self.request({"id": str(uuid.uuid4()), "text": ""})
        self.assertEqual(status, 400)
        self.assertEqual(body["status"], "invalid_request")

    def test_non_object_json_is_invalid(self) -> None:
        status, body = self.raw_request(b"[]")
        self.assertEqual(status, 400)
        self.assertEqual(body["status"], "invalid_request")

    def test_key_action_roundtrip(self) -> None:
        request_id = str(uuid.uuid4())

        with FakeFcitxSocket(self.config.fcitx_socket) as fake_fcitx:
            status, body = self.request(
                {
                    "id": request_id,
                    "key": "Return",
                    "modifiers": ["Control"],
                },
                path="/v1/key",
            )

        self.assertEqual(status, 200)
        self.assertEqual(body["id"], request_id)
        self.assertEqual(body["status"], "ok")
        self.assertIsNotNone(fake_fcitx.received_payload)
        self.assertEqual(fake_fcitx.received_payload["v"], 1)
        self.assertEqual(fake_fcitx.received_payload["id"], request_id)
        self.assertEqual(fake_fcitx.received_payload["action"], "key")
        self.assertEqual(fake_fcitx.received_payload["key"], "Return")
        self.assertEqual(fake_fcitx.received_payload["modifiers"], ["Control"])

    def test_key_action_normalizes_modifier_order(self) -> None:
        request_id = str(uuid.uuid4())

        with FakeFcitxSocket(self.config.fcitx_socket) as fake_fcitx:
            status, body = self.request(
                {
                    "id": request_id,
                    "key": "V",
                    "modifiers": ["Alt", "Control", "Control"],
                },
                path="/v1/key",
            )

        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "ok")
        self.assertIsNotNone(fake_fcitx.received_payload)
        self.assertEqual(fake_fcitx.received_payload["modifiers"], ["Control", "Alt"])

    def test_key_action_rejects_invalid_key(self) -> None:
        status, body = self.request(
            {
                "id": str(uuid.uuid4()),
                "key": "F13",
                "modifiers": [],
            },
            path="/v1/key",
        )

        self.assertEqual(status, 400)
        self.assertEqual(body["status"], "invalid_key")

    def test_key_action_rejects_invalid_modifier(self) -> None:
        status, body = self.request(
            {
                "id": str(uuid.uuid4()),
                "key": "Return",
                "modifiers": ["Super"],
            },
            path="/v1/key",
        )

        self.assertEqual(status, 400)
        self.assertEqual(body["status"], "invalid_key")

    def test_key_action_fcitx_invalid_key_maps_to_bad_request(self) -> None:
        request_id = str(uuid.uuid4())
        with FakeFcitxSocket(self.config.fcitx_socket, status="invalid_key"):
            status, body = self.request(
                {
                    "id": request_id,
                    "key": "Return",
                    "modifiers": [],
                },
                path="/v1/key",
            )

        self.assertEqual(status, 400)
        self.assertEqual(body["status"], "invalid_key")


class DiscoveryProtocolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        runtime = Path(self.tmp.name) / "runtime"
        self.config = textbridge_server.ServerConfig(
            listen_host="0.0.0.0",
            listen_port=17321,
            max_text_bytes=64,
            request_timeout_ms=200,
            runtime_dir=runtime,
            fcitx_socket=runtime / "fcitx.sock",
            discovery_port=0,
            device_name="test-host",
        )

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_discovery_valid_request_returns_offer(self) -> None:
        listener = textbridge_server.start_discovery_listener(self.config)
        self.assertIsNotNone(listener)
        assert listener is not None
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.settimeout(2)
                request_id = str(uuid.uuid4())
                request = {
                    "v": 1,
                    "type": "textbridge.discover",
                    "id": request_id,
                }
                client.sendto(json.dumps(request).encode("utf-8"), ("127.0.0.1", listener.port))
                data, _ = client.recvfrom(4096)
        finally:
            listener.close()

        offer = json.loads(data.decode("utf-8"))
        self.assertEqual(offer["v"], 1)
        self.assertEqual(offer["type"], "textbridge.offer")
        self.assertEqual(offer["id"], request_id)
        self.assertEqual(offer["name"], "test-host")
        self.assertEqual(offer["host"], "127.0.0.1")
        self.assertEqual(offer["port"], 17321)
        self.assertEqual(offer["version"], "0.2.0")
        self.assertEqual(offer["auth"], "none")

    def test_discovery_echoes_configured_listen_host(self) -> None:
        request_id = str(uuid.uuid4())
        config = textbridge_server.ServerConfig(
            listen_host="192.168.1.10",
            listen_port=17321,
            max_text_bytes=64,
            request_timeout_ms=200,
            runtime_dir=Path(self.tmp.name) / "runtime",
            fcitx_socket=Path(self.tmp.name) / "runtime" / "fcitx.sock",
            device_name="test-host",
        )
        offer = textbridge_server.make_discovery_offer(config, request_id, ("127.0.0.1", 12345))
        self.assertEqual(offer["host"], "192.168.1.10")
        self.assertEqual(offer["id"], request_id)

    def test_discovery_invalid_requests_are_ignored(self) -> None:
        self.assertIsNone(textbridge_server.decode_discovery_request(b"not json"))
        self.assertIsNone(textbridge_server.decode_discovery_request(b"[]"))
        self.assertIsNone(
            textbridge_server.decode_discovery_request(
                json.dumps({"v": 2, "type": "textbridge.discover", "id": str(uuid.uuid4())}).encode("utf-8")
            )
        )
        self.assertIsNone(
            textbridge_server.decode_discovery_request(
                json.dumps({"v": 1, "type": "wrong.type", "id": str(uuid.uuid4())}).encode("utf-8")
            )
        )
        self.assertIsNone(
            textbridge_server.decode_discovery_request(
                json.dumps({"v": 1, "type": "textbridge.discover", "id": ""}).encode("utf-8")
            )
        )
        self.assertIsNone(textbridge_server.decode_discovery_request(b"{" + (b"x" * 3000) + b"}"))

    def test_discovery_disabled_does_not_listen(self) -> None:
        config = textbridge_server.ServerConfig(
            listen_host="0.0.0.0",
            listen_port=17321,
            max_text_bytes=64,
            request_timeout_ms=200,
            runtime_dir=Path(self.tmp.name) / "runtime",
            fcitx_socket=Path(self.tmp.name) / "runtime" / "fcitx.sock",
            discovery_enabled=False,
            discovery_port=0,
        )
        self.assertIsNone(textbridge_server.start_discovery_listener(config))


if __name__ == "__main__":
    unittest.main()
