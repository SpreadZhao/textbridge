#!/usr/bin/env python3
"""TextBridge Bluetooth RFCOMM server.

This service registers a BlueZ profile and receives one newline-delimited JSON
request per RFCOMM connection. It keeps the Fcitx-facing Unix datagram protocol
shared with textbridge_server.py.
"""

from __future__ import annotations

import argparse
import hmac
import json
import logging
import socket
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import textbridge_server


TEXTBRIDGE_BLUETOOTH_UUID = "6f6f3b6e-8ff4-4a5f-8f24-0f8e7f4a7d42"
DEFAULT_TEXTBRIDGE_BLUETOOTH_CHANNEL = 22
MIN_RFCOMM_CHANNEL = 1
MAX_RFCOMM_CHANNEL = 30
PROFILE_PATH = "/io/github/textbridge/bluetooth/profile"
BLUEZ_SERVICE = "org.bluez"
BLUEZ_PROFILE_MANAGER_PATH = "/org/bluez"
BLUEZ_PROFILE_MANAGER_IFACE = "org.bluez.ProfileManager1"
BLUEZ_PROFILE_IFACE = "org.bluez.Profile1"


class FrameTooLarge(Exception):
    pass


def max_frame_bytes(config: textbridge_server.ServerConfig) -> int:
    return config.max_text_bytes + textbridge_server.MAX_JSON_OVERHEAD_BYTES


def read_frame(sock: socket.socket, limit: int) -> bytes:
    chunks = bytearray()
    while True:
        data = sock.recv(4096)
        if not data:
            break
        newline = data.find(b"\n")
        if newline >= 0:
            chunks.extend(data[:newline])
            break
        chunks.extend(data)
        if len(chunks) > limit:
            raise FrameTooLarge("bluetooth request too large")
    if len(chunks) > limit:
        raise FrameTooLarge("bluetooth request too large")
    return bytes(chunks)


def write_frame(sock: socket.socket, payload: dict[str, Any]) -> None:
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
    sock.sendall(data)


def validate_request_id(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("id must be a UUID string")
    return str(uuid.UUID(value))


def error_response(request_id: str, status: str) -> dict[str, Any]:
    return {
        "id": request_id,
        "status": status,
    }


def decode_payload(data: bytes) -> dict[str, Any]:
    payload = json.loads(data.decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("payload must be a JSON object")
    return payload


def payload_request_id(payload: dict[str, Any]) -> str:
    try:
        return validate_request_id(payload.get("id"))
    except (TypeError, ValueError):
        return "-"


def handle_payload(config: textbridge_server.ServerConfig, payload: dict[str, Any]) -> dict[str, Any]:
    request_id = payload_request_id(payload)
    try:
        if payload.get("v") != 1:
            return error_response(request_id, "invalid_request")

        if not hmac.compare_digest(str(payload.get("token", "")), config.token):
            return error_response(request_id, "unauthorized")

        request_id = validate_request_id(payload.get("id"))
        action = payload.get("action")
        if action == "commit":
            text = payload.get("text")
            if not isinstance(text, str) or text == "":
                return error_response(request_id, "invalid_request")
            if len(text.encode("utf-8")) > config.max_text_bytes:
                return error_response(request_id, "text_too_large")
            fcitx_payload = {"v": 1, "id": request_id, "text": text}
        elif action == "key":
            key_action = textbridge_server.normalize_key_action(payload)
            if key_action is None:
                return error_response(request_id, "invalid_key")
            key, modifiers = key_action
            fcitx_payload = {
                "v": 1,
                "id": request_id,
                "action": "key",
                "key": key,
                "modifiers": modifiers,
            }
        else:
            return error_response(request_id, "invalid_request")

        addon_response = textbridge_server.validate_addon_response(
            textbridge_server.forward_to_fcitx(config, fcitx_payload),
            request_id,
        )
        status = str(addon_response.get("status", "fcitx_unavailable"))
        response = {
            "id": request_id,
            "status": "invalid_request" if status == "invalid_text" else status,
        }
        if "target_program" in addon_response:
            response["target_program"] = addon_response["target_program"]
        return response
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError, TypeError):
        return error_response(request_id, "invalid_request")
    except (TimeoutError, OSError):
        return error_response(request_id, "fcitx_unavailable")


def handle_frame(config: textbridge_server.ServerConfig, data: bytes) -> dict[str, Any]:
    try:
        return handle_payload(config, decode_payload(data))
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError, TypeError):
        return error_response("-", "invalid_request")


def handle_socket(config: textbridge_server.ServerConfig, sock: socket.socket, remote: str = "-") -> None:
    started = time.monotonic()
    status = "invalid_request"
    request_id = "-"
    try:
        data = read_frame(sock, max_frame_bytes(config))
        response = handle_frame(config, data)
        status = str(response.get("status", status))
        request_id = str(response.get("id", request_id))
        write_frame(sock, response)
    except FrameTooLarge:
        status = "text_too_large"
        write_frame(sock, error_response("-", status))
    except OSError as exc:
        logging.info("bluetooth_request remote=%s result=io_error error=%s", remote, exc.__class__.__name__)
        return
    finally:
        elapsed_ms = int((time.monotonic() - started) * 1000)
        logging.info(
            "bluetooth_request id=%s status=%s remote=%s elapsed_ms=%s",
            request_id,
            status,
            remote,
            elapsed_ms,
        )


def build_bluez_profile_class(dbus_module: Any):
    class TextBridgeBluetoothProfile(dbus_module.service.Object):
        def __init__(self, bus: Any, path: str, config: textbridge_server.ServerConfig, loop: Any) -> None:
            super().__init__(bus, path)
            self.config = config
            self.loop = loop

        @dbus_module.service.method(BLUEZ_PROFILE_IFACE, in_signature="", out_signature="")
        def Release(self) -> None:  # noqa: N802
            logging.info("bluetooth profile released")
            self.loop.quit()

        @dbus_module.service.method(BLUEZ_PROFILE_IFACE, in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device: str, fd: Any, properties: dict[str, Any]) -> None:  # noqa: N802
            raw_fd = fd.take() if hasattr(fd, "take") else int(fd)
            thread = threading.Thread(
                target=self._handle_connection,
                args=(device, raw_fd),
                name="textbridge-bluetooth-client",
                daemon=True,
            )
            thread.start()

        @dbus_module.service.method(BLUEZ_PROFILE_IFACE, in_signature="o", out_signature="")
        def RequestDisconnection(self, device: str) -> None:  # noqa: N802
            logging.info("bluetooth disconnect requested device=%s", device)

        @dbus_module.service.method(BLUEZ_PROFILE_IFACE, in_signature="", out_signature="")
        def Cancel(self) -> None:  # noqa: N802
            logging.info("bluetooth profile request canceled")

        def _handle_connection(self, device: str, raw_fd: int) -> None:
            with socket.socket(fileno=raw_fd) as client:
                client.settimeout(max(self.config.request_timeout_ms, 1) / 1000)
                handle_socket(self.config, client, remote=device)

    return TextBridgeBluetoothProfile


def validate_rfcomm_channel(value: int) -> int:
    if value < MIN_RFCOMM_CHANNEL or value > MAX_RFCOMM_CHANNEL:
        raise ValueError(
            f"Bluetooth RFCOMM channel must be between {MIN_RFCOMM_CHANNEL} and {MAX_RFCOMM_CHANNEL}"
        )
    return value


def parse_rfcomm_channel(value: str) -> int:
    try:
        return validate_rfcomm_channel(int(value))
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def run(config: textbridge_server.ServerConfig, channel: int = DEFAULT_TEXTBRIDGE_BLUETOOTH_CHANNEL) -> None:
    channel = validate_rfcomm_channel(channel)
    try:
        import dbus
        import dbus.mainloop.glib
        import dbus.service
        from gi.repository import GLib
    except ImportError as exc:
        raise SystemExit(f"Missing Bluetooth runtime dependency: {exc}") from exc

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    loop = GLib.MainLoop()
    profile_class = build_bluez_profile_class(dbus)
    profile_class(bus, PROFILE_PATH, config, loop)

    manager = dbus.Interface(
        bus.get_object(BLUEZ_SERVICE, BLUEZ_PROFILE_MANAGER_PATH),
        BLUEZ_PROFILE_MANAGER_IFACE,
    )
    options = dbus.Dictionary(
        {
            "Name": "TextBridge",
            "Service": TEXTBRIDGE_BLUETOOTH_UUID,
            "Role": "server",
            "Channel": dbus.UInt16(channel),
            "RequireAuthentication": True,
            "RequireAuthorization": False,
        },
        signature="sv",
    )
    manager.RegisterProfile(PROFILE_PATH, TEXTBRIDGE_BLUETOOTH_UUID, options)
    logging.info(
        "bluetooth profile registered uuid=%s channel=%s",
        TEXTBRIDGE_BLUETOOTH_UUID,
        channel,
    )
    try:
        loop.run()
    finally:
        try:
            manager.UnregisterProfile(PROFILE_PATH)
        except Exception:
            pass


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="TextBridge Bluetooth RFCOMM server")
    parser.add_argument("--config", type=Path, default=textbridge_server.default_config_path())
    parser.add_argument(
        "--channel",
        type=parse_rfcomm_channel,
        default=DEFAULT_TEXTBRIDGE_BLUETOOTH_CHANNEL,
        help=f"Bluetooth RFCOMM channel to advertise in SDP ({MIN_RFCOMM_CHANNEL}-{MAX_RFCOMM_CHANNEL})",
    )
    parser.add_argument("--log-level", default="INFO", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level), format="%(asctime)s %(levelname)s %(message)s")

    try:
        config = textbridge_server.load_config(args.config)
        run(config, channel=args.channel)
    except textbridge_server.ConfigError as exc:
        logging.error("configuration error: %s", exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
