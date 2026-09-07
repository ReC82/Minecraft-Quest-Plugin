#!/usr/bin/env python3
"""Minimal Source RCON client for the VeryGames RPGQuest DEV server (issues #10 / #51).

Reads RCON_HOST / RCON_PORT / RCON_PASSWORD from a local, non-versioned env file
(default ~/.config/rpgquest/verygames.env, or $RPGQUEST_VERYGAMES_ENV) — the SAME
file already used for FTP. The password is NEVER printed, logged or echoed.

Usage:
    scripts/verygames-rcon.py "list"
    scripts/verygames-rcon.py "stop"
    scripts/verygames-rcon.py --ping        # exit 0 if RCON connects + authenticates, else 1

Exit codes: 0 = ok, 2 = config missing, 3 = connect/auth failure, 4 = protocol error.
"""
import os
import socket
import struct
import sys
import time

DEFAULT_ENV = os.path.expanduser("~/.config/rpgquest/verygames.env")

_SERVERDATA_AUTH = 3
_SERVERDATA_EXECCOMMAND = 2
_SERVERDATA_RESPONSE_VALUE = 0


def _load_env():
    path = os.environ.get("RPGQUEST_VERYGAMES_ENV", DEFAULT_ENV)
    values = {}
    # env vars already exported win over the file (same rule as deploy-verygames.sh)
    for key in ("RCON_HOST", "RCON_PORT", "RCON_PASSWORD"):
        if os.environ.get(key):
            values[key] = os.environ[key]
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                if k in ("RCON_HOST", "RCON_PORT", "RCON_PASSWORD") and k not in values:
                    values[k] = v.strip().strip("'").strip('"')
    host = values.get("RCON_HOST")
    port = values.get("RCON_PORT")
    password = values.get("RCON_PASSWORD")
    if not host or not port or not password:
        sys.stderr.write(
            "rcon: RCON_HOST / RCON_PORT / RCON_PASSWORD manquant "
            "(fichier %s ou variables d'environnement).\n" % path)
        sys.exit(2)
    return host, int(port), password


def _send(sock, req_id, req_type, body):
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def _recv(sock):
    raw_len = _recv_exact(sock, 4)
    (length,) = struct.unpack("<i", raw_len)
    data = _recv_exact(sock, length)
    resp_id, resp_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return resp_id, resp_type, body


def _recv_exact(sock, count):
    buf = b""
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            raise ConnectionError("connexion RCON fermée pendant la lecture")
        buf += chunk
    return buf


def rcon_command(host, port, password, command, timeout=8.0):
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        _send(sock, 1, _SERVERDATA_AUTH, password)
        resp_id, _, _ = _recv(sock)
        # Some servers send an empty RESPONSE_VALUE before the auth reply.
        if resp_id == -1:
            raise PermissionError("authentification RCON refusée")
        if resp_id != 1:
            try:
                resp_id, _, _ = _recv(sock)
            except Exception:
                pass
            if resp_id == -1:
                raise PermissionError("authentification RCON refusée")
        if not command:
            return ""
        _send(sock, 2, _SERVERDATA_EXECCOMMAND, command)
        _, _, body = _recv(sock)
        return body


def main(argv):
    args = argv[1:]
    ping = False
    if args and args[0] == "--ping":
        ping = True
        args = args[1:]
    command = args[0] if args else ("" if ping else "list")

    host, port, password = _load_env()
    try:
        out = rcon_command(host, port, password, command)
    except PermissionError as exc:
        sys.stderr.write("rcon: %s\n" % exc)
        return 3
    except (OSError, ConnectionError) as exc:
        sys.stderr.write("rcon: %s\n" % exc)
        return 3
    except Exception as exc:  # noqa: BLE001 - protocol edge cases
        sys.stderr.write("rcon: erreur de protocole (%s)\n" % type(exc).__name__)
        return 4
    if ping:
        return 0
    if out:
        print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
