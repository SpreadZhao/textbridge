#!/usr/bin/env python3
"""TextBridge Wi-Fi HTTP server.

The service intentionally keeps network parsing outside the Fcitx5 process. It
validates one HTTP JSON request, forwards a small JSON datagram to the local
Fcitx5 addon, and maps the addon result back to HTTP.
"""

from __future__ import annotations

import argparse
import hmac
import json
import logging
import os
import secrets
import socket
import sys
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


TEXTBRIDGE_VERSION = "0.1.0"
DEFAULT_PORT = 17321
DEFAULT_DISCOVERY_PORT = 17322
DEFAULT_MAX_TEXT_BYTES = 16 * 1024
DEFAULT_TIMEOUT_MS = 2000
MAX_JSON_OVERHEAD_BYTES = 4096
MAX_DISCOVERY_DATAGRAM_BYTES = 2048
DISCOVERY_REQUEST_TYPE = "textbridge.discover"
DISCOVERY_OFFER_TYPE = "textbridge.offer"

STATUS_TO_HTTP = {
    "ok": HTTPStatus.OK,
    "invalid_request": HTTPStatus.BAD_REQUEST,
    "invalid_text": HTTPStatus.BAD_REQUEST,
    "unauthorized": HTTPStatus.UNAUTHORIZED,
    "busy_composing": HTTPStatus.CONFLICT,
    "sensitive_field": HTTPStatus.FORBIDDEN,
    "text_too_large": HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
    "no_focused_input": HTTPStatus.SERVICE_UNAVAILABLE,
    "fcitx_unavailable": HTTPStatus.SERVICE_UNAVAILABLE,
}


@dataclass(frozen=True)
class ServerConfig:
    listen_host: str
    listen_port: int
    token: str
    max_text_bytes: int
    request_timeout_ms: int
    runtime_dir: Path
    fcitx_socket: Path
    discovery_enabled: bool = True
    discovery_port: int = DEFAULT_DISCOVERY_PORT
    device_name: str = field(default_factory=socket.gethostname)


class ConfigError(Exception):
    pass


def validate_addon_response(response: dict[str, Any], request_id: str) -> dict[str, Any]:
    if response.get("v") != 1:
        raise OSError("invalid fcitx response version")
    if response.get("id") != request_id:
        raise OSError("fcitx response id mismatch")

    status = response.get("status")
    if not isinstance(status, str) or status not in STATUS_TO_HTTP:
        raise OSError("invalid fcitx response status")

    normalized: dict[str, Any] = {
        "id": request_id,
        "status": status,
    }
    target_program = response.get("target_program")
    if isinstance(target_program, str):
        normalized["target_program"] = target_program
    return normalized


def xdg_runtime_dir() -> Path:
    value = os.environ.get("XDG_RUNTIME_DIR")
    if value:
        return Path(value)
    return Path(tempfile.gettempdir()) / f"textbridge-{os.getuid()}"


def default_config_path() -> Path:
    config_home = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return config_home / "textbridge" / "server.json"


def default_runtime_dir() -> Path:
    return xdg_runtime_dir() / "textbridge"


def load_token(raw: dict[str, Any], config_path: Path) -> str:
    token_file = raw.get("token_file")
    if token_file is not None:
        token_path = Path(str(token_file)).expanduser()
        if not token_path.is_absolute():
            token_path = config_path.parent / token_path
        try:
            token = token_path.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise ConfigError(f"cannot read token_file {token_path}: {exc.strerror}") from exc
        if token == "":
            raise ConfigError(f"token_file {token_path} is empty")
        return token

    token = raw.get("token")
    if token is None:
        raise ConfigError("server config must set token_file or token")
    token_value = str(token)
    if token_value == "":
        raise ConfigError("token must not be empty")
    return token_value


def load_config(path: Path) -> ServerConfig:
    with path.open("r", encoding="utf-8") as fp:
        raw = json.load(fp)

    runtime_dir = Path(raw.get("runtime_dir") or default_runtime_dir())
    fcitx_socket = Path(raw.get("fcitx_socket") or runtime_dir / "fcitx.sock")

    return ServerConfig(
        listen_host=str(raw["listen_host"]),
        listen_port=int(raw.get("listen_port", DEFAULT_PORT)),
        token=load_token(raw, path),
        max_text_bytes=int(raw.get("max_text_bytes", DEFAULT_MAX_TEXT_BYTES)),
        request_timeout_ms=int(raw.get("request_timeout_ms", DEFAULT_TIMEOUT_MS)),
        runtime_dir=runtime_dir,
        fcitx_socket=fcitx_socket,
        discovery_enabled=bool(raw.get("discovery_enabled", True)),
        discovery_port=int(raw.get("discovery_port", DEFAULT_DISCOVERY_PORT)),
        device_name=str(raw.get("device_name") or socket.gethostname()),
    )


def init_config(path: Path, listen_host: str, listen_port: int) -> None:
    if path.exists():
        raise SystemExit(f"Refusing to overwrite existing config: {path}")

    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    config = {
        "listen_host": listen_host,
        "listen_port": listen_port,
        "token": secrets.token_urlsafe(32),
        "max_text_bytes": DEFAULT_MAX_TEXT_BYTES,
        "request_timeout_ms": DEFAULT_TIMEOUT_MS,
        "discovery_enabled": True,
        "discovery_port": DEFAULT_DISCOVERY_PORT,
        "device_name": socket.gethostname(),
    }
    path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    path.chmod(0o600)
    print(f"Wrote {path}")
    print("Token:", config["token"])


def make_handler(config: ServerConfig) -> type[BaseHTTPRequestHandler]:
    class TextBridgeHandler(BaseHTTPRequestHandler):
        server_version = "TextBridge/0.1"

        def do_POST(self) -> None:  # noqa: N802
            started = time.monotonic()
            request_id = "-"
            status = "invalid_request"
            http_status = HTTPStatus.BAD_REQUEST

            try:
                if self.path != "/v1/commit":
                    status = "not_found"
                    http_status = HTTPStatus.NOT_FOUND
                    self.write_json(http_status, {"status": status})
                    return

                auth = self.headers.get("Authorization", "")
                expected = f"Bearer {config.token}"
                if not hmac.compare_digest(auth, expected):
                    status = "unauthorized"
                    http_status = HTTPStatus.UNAUTHORIZED
                    self.write_json(http_status, {"status": status})
                    return

                content_type = self.headers.get("Content-Type", "")
                if "application/json" not in content_type.lower():
                    self.write_json(HTTPStatus.BAD_REQUEST, {"status": "invalid_request"})
                    return

                content_length = self.parse_content_length()
                if content_length is None:
                    self.write_json(HTTPStatus.BAD_REQUEST, {"status": "invalid_request"})
                    return

                max_body_bytes = config.max_text_bytes + MAX_JSON_OVERHEAD_BYTES
                if content_length > max_body_bytes:
                    status = "text_too_large"
                    http_status = HTTPStatus.REQUEST_ENTITY_TOO_LARGE
                    self.write_json(http_status, {"status": status})
                    return

                body = self.rfile.read(content_length)
                payload = json.loads(body.decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("payload must be a JSON object")
                request_id = self.validate_request_id(payload.get("id"))
                text = payload.get("text")
                if not isinstance(text, str) or text == "":
                    self.write_json(HTTPStatus.BAD_REQUEST, {"id": request_id, "status": "invalid_request"})
                    return

                if len(text.encode("utf-8")) > config.max_text_bytes:
                    status = "text_too_large"
                    http_status = HTTPStatus.REQUEST_ENTITY_TOO_LARGE
                    self.write_json(http_status, {"id": request_id, "status": status})
                    return

                addon_response = validate_addon_response(
                    forward_to_fcitx(config, {"v": 1, "id": request_id, "text": text}),
                    request_id,
                )
                status = str(addon_response.get("status", "fcitx_unavailable"))
                http_status = STATUS_TO_HTTP.get(status, HTTPStatus.SERVICE_UNAVAILABLE)
                response = {
                    "id": request_id,
                    "status": "invalid_request" if status == "invalid_text" else status,
                }
                if "target_program" in addon_response:
                    response["target_program"] = addon_response["target_program"]
                self.write_json(http_status, response)
            except (json.JSONDecodeError, UnicodeDecodeError, ValueError, TypeError):
                self.write_json(HTTPStatus.BAD_REQUEST, {"id": request_id, "status": "invalid_request"})
            except TimeoutError:
                status = "fcitx_unavailable"
                http_status = HTTPStatus.SERVICE_UNAVAILABLE
                self.write_json(http_status, {"id": request_id, "status": status})
            except OSError:
                status = "fcitx_unavailable"
                http_status = HTTPStatus.SERVICE_UNAVAILABLE
                self.write_json(http_status, {"id": request_id, "status": status})
            finally:
                elapsed_ms = int((time.monotonic() - started) * 1000)
                logging.info(
                    "commit_request id=%s status=%s http=%s remote=%s elapsed_ms=%s",
                    request_id,
                    status,
                    int(http_status),
                    self.client_address[0],
                    elapsed_ms,
                )

        def parse_content_length(self) -> int | None:
            value = self.headers.get("Content-Length")
            if value is None:
                return None
            try:
                parsed = int(value)
            except ValueError:
                return None
            if parsed < 0:
                return None
            return parsed

        def validate_request_id(self, value: Any) -> str:
            if not isinstance(value, str):
                raise ValueError("id must be a UUID string")
            return str(uuid.UUID(value))

        def write_json(self, status_code: HTTPStatus, payload: dict[str, Any]) -> None:
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(int(status_code))
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt: str, *args: Any) -> None:
            logging.debug("http %s - %s", self.client_address[0], fmt % args)

    return TextBridgeHandler


def forward_to_fcitx(config: ServerConfig, payload: dict[str, Any]) -> dict[str, Any]:
    config.runtime_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(config.runtime_dir, 0o700)

    client_path = config.runtime_dir / f"client-{os.getpid()}-{uuid.uuid4()}.sock"
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    timeout = max(config.request_timeout_ms, 1) / 1000

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    try:
        sock.settimeout(timeout)
        sock.bind(str(client_path))
        os.chmod(client_path, 0o600)
        sock.sendto(data, str(config.fcitx_socket))
        response, _ = sock.recvfrom(config.max_text_bytes + MAX_JSON_OVERHEAD_BYTES)
        decoded = json.loads(response.decode("utf-8"))
        if not isinstance(decoded, dict):
            raise OSError("invalid fcitx response")
        return decoded
    except socket.timeout as exc:
        raise TimeoutError("fcitx response timed out") from exc
    finally:
        sock.close()
        try:
            client_path.unlink()
        except FileNotFoundError:
            pass


def decode_discovery_request(data: bytes) -> str | None:
    if len(data) > MAX_DISCOVERY_DATAGRAM_BYTES:
        return None
    try:
        payload = json.loads(data.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    if payload.get("v") != 1 or payload.get("type") != DISCOVERY_REQUEST_TYPE:
        return None
    request_id = payload.get("id")
    if not isinstance(request_id, str) or request_id == "":
        return None
    return request_id


def is_unspecified_host(host: str) -> bool:
    return host in {"", "*", "0.0.0.0", "::"}


def advertised_host(listen_host: str, requester_address: tuple[str, int]) -> str:
    if not is_unspecified_host(listen_host):
        return listen_host

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(requester_address)
        host = probe.getsockname()[0]
        if host and not is_unspecified_host(host):
            return host
    except OSError:
        pass
    finally:
        probe.close()
    return listen_host


def make_discovery_offer(config: ServerConfig, request_id: str, requester_address: tuple[str, int]) -> dict[str, Any]:
    return {
        "v": 1,
        "type": DISCOVERY_OFFER_TYPE,
        "id": request_id,
        "name": config.device_name,
        "host": advertised_host(config.listen_host, requester_address),
        "port": config.listen_port,
        "version": TEXTBRIDGE_VERSION,
        "auth": "bearer",
    }


class DiscoveryListener:
    def __init__(self, config: ServerConfig) -> None:
        self.config = config
        self._stopped = threading.Event()
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("", config.discovery_port))
        self._sock.settimeout(0.2)
        self._thread = threading.Thread(target=self._serve, name="textbridge-discovery", daemon=True)

    @property
    def port(self) -> int:
        return int(self._sock.getsockname()[1])

    def start(self) -> None:
        self._thread.start()

    def close(self) -> None:
        self._stopped.set()
        self._sock.close()
        self._thread.join(timeout=2)

    def _serve(self) -> None:
        while not self._stopped.is_set():
            try:
                data, address = self._sock.recvfrom(MAX_DISCOVERY_DATAGRAM_BYTES + 1)
            except socket.timeout:
                continue
            except OSError:
                break

            request_id = decode_discovery_request(data)
            if request_id is None:
                logging.debug("discovery_request remote=%s result=ignored", address[0])
                continue

            offer = make_discovery_offer(self.config, request_id, address)
            response = json.dumps(offer, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            try:
                self._sock.sendto(response, address)
                logging.info("discovery_request remote=%s result=responded", address[0])
            except OSError as exc:
                logging.info("discovery_request remote=%s result=failed error=%s", address[0], exc.__class__.__name__)


def start_discovery_listener(config: ServerConfig) -> DiscoveryListener | None:
    if not config.discovery_enabled:
        logging.info("discovery disabled")
        return None
    try:
        listener = DiscoveryListener(config)
    except OSError as exc:
        logging.warning(
            "discovery listen failed host=0.0.0.0 port=%s error=%s",
            config.discovery_port,
            exc.__class__.__name__,
        )
        return None
    listener.start()
    logging.info("discovery listening host=0.0.0.0 port=%s", listener.port)
    return listener


def run(config: ServerConfig) -> None:
    config.runtime_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(config.runtime_dir, 0o700)

    handler = make_handler(config)
    server = ThreadingHTTPServer((config.listen_host, config.listen_port), handler)
    discovery_listener = start_discovery_listener(config)
    logging.info("listening host=%s port=%s", config.listen_host, config.listen_port)
    try:
        server.serve_forever()
    finally:
        if discovery_listener is not None:
            discovery_listener.close()
        server.server_close()


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="TextBridge Wi-Fi HTTP server")
    parser.add_argument("--config", type=Path, default=default_config_path())
    parser.add_argument("--init-config", action="store_true", help="write a new config with a random token")
    parser.add_argument("--listen-host", default="127.0.0.1", help="host used by --init-config")
    parser.add_argument("--listen-port", type=int, default=DEFAULT_PORT, help="port used by --init-config")
    parser.add_argument("--log-level", default="INFO", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level), format="%(asctime)s %(levelname)s %(message)s")

    if args.init_config:
        init_config(args.config, args.listen_host, args.listen_port)
        return 0

    try:
        config = load_config(args.config)
        run(config)
    except ConfigError as exc:
        logging.error("configuration error: %s", exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
