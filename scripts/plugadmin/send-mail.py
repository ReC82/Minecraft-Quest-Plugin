#!/usr/bin/env python3
"""Minimal SMTP notifier for PlugAdmin (issues #29 / #51).

Reads SMTP settings from a local, non-versioned env file
(default ~/.config/plugadmin/smtp.env, or $PLUGADMIN_SMTP_ENV):

    PLUGADMIN_SMTP_HOST=send.one.com
    PLUGADMIN_SMTP_PORT=465            # implicit TLS (SMTPS)
    PLUGADMIN_SMTP_USERNAME=...
    PLUGADMIN_SMTP_PASSWORD=...        # never printed
    PLUGADMIN_SMTP_FROM=...
    PLUGADMIN_SMTP_TO=...              # default recipient

Usage:
    scripts/plugadmin/send-mail.py --subject "..." --body-file /path/to/body.txt [--to addr]
    echo "body" | scripts/plugadmin/send-mail.py --subject "..."

Exit: 0 sent, 2 config missing, 3 send failure.
"""
import argparse
import os
import smtplib
import ssl
import sys
from email.message import EmailMessage

DEFAULT_ENV = os.path.expanduser("~/.config/plugadmin/smtp.env")


def _load_env():
    path = os.environ.get("PLUGADMIN_SMTP_ENV", DEFAULT_ENV)
    keys = ("PLUGADMIN_SMTP_HOST", "PLUGADMIN_SMTP_PORT", "PLUGADMIN_SMTP_USERNAME",
            "PLUGADMIN_SMTP_PASSWORD", "PLUGADMIN_SMTP_FROM", "PLUGADMIN_SMTP_TO")
    values = {k: os.environ[k] for k in keys if os.environ.get(k)}
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                if k in keys and k not in values:
                    values[k] = v.strip().strip("'").strip('"')
    missing = [k for k in ("PLUGADMIN_SMTP_HOST", "PLUGADMIN_SMTP_PORT", "PLUGADMIN_SMTP_USERNAME",
                           "PLUGADMIN_SMTP_PASSWORD", "PLUGADMIN_SMTP_FROM") if not values.get(k)]
    if missing:
        sys.stderr.write("send-mail: config manquante (%s) dans %s\n" % (", ".join(missing), path))
        sys.exit(2)
    return values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--subject", required=True)
    parser.add_argument("--body-file")
    parser.add_argument("--to")
    args = parser.parse_args()

    env = _load_env()
    recipient = args.to or env.get("PLUGADMIN_SMTP_TO")
    if not recipient:
        sys.stderr.write("send-mail: aucun destinataire (--to ou PLUGADMIN_SMTP_TO).\n")
        sys.exit(2)

    if args.body_file:
        with open(args.body_file, "r", encoding="utf-8") as handle:
            body = handle.read()
    else:
        body = sys.stdin.read()

    msg = EmailMessage()
    msg["Subject"] = args.subject
    msg["From"] = env["PLUGADMIN_SMTP_FROM"]
    msg["To"] = recipient
    msg.set_content(body)

    host = env["PLUGADMIN_SMTP_HOST"]
    port = int(env["PLUGADMIN_SMTP_PORT"])
    try:
        context = ssl.create_default_context()
        with smtplib.SMTP_SSL(host, port, context=context, timeout=30) as smtp:
            smtp.login(env["PLUGADMIN_SMTP_USERNAME"], env["PLUGADMIN_SMTP_PASSWORD"])
            smtp.send_message(msg)
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("send-mail: échec d'envoi (%s: %s)\n" % (type(exc).__name__, exc))
        sys.exit(3)
    print("send-mail: envoyé à %s (sujet: %s)" % (recipient, args.subject))
    return 0


if __name__ == "__main__":
    sys.exit(main())
