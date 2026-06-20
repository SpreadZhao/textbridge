#!/usr/bin/env python3
"""Send a TextBridge request directly to the Fcitx5 addon Unix socket."""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import tempfile
import uuid
from pathlib import Path


def default_runtime_dir() -> Path:
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if runtime:
        return Path(runtime) / "textbridge"
    return Path(tempfile.gettempdir()) / f"textbridge-{os.getuid()}" / "textbridge"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send text to fcitx5-textbridge over Unix datagram")
    parser.add_argument("text", nargs="?", help="text to send; stdin is used when omitted")
    parser.add_argument("--socket", type=Path, default=default_runtime_dir() / "fcitx.sock")
    parser.add_argument("--timeout-ms", type=int, default=2000)
    parser.add_argument("--id", default=str(uuid.uuid4()))
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    text = args.text if args.text is not None else sys.stdin.read()
    payload = {"v": 1, "id": str(uuid.UUID(args.id)), "text": text}
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    runtime_dir = args.socket.parent
    runtime_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(runtime_dir, 0o700)
    client_path = runtime_dir / f"send-test-{os.getpid()}-{uuid.uuid4()}.sock"

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    try:
        sock.settimeout(max(args.timeout_ms, 1) / 1000)
        sock.bind(str(client_path))
        os.chmod(client_path, 0o600)
        sock.sendto(data, str(args.socket))
        response, _ = sock.recvfrom(65536)
    finally:
        sock.close()
        try:
            client_path.unlink()
        except FileNotFoundError:
            pass

    decoded = json.loads(response.decode("utf-8"))
    print(json.dumps(decoded, ensure_ascii=False, indent=2))
    return 0 if decoded.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
