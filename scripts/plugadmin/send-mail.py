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
    scripts/plugadmin/send-mail.py --subject "..." --body-file body.txt [--to addr]
    scripts/plugadmin/send-mail.py --subject "..." --body-file body.txt \\
        --attach docs/claude-reports/2026-09-08_1325_report.md
    echo "body" | scripts/plugadmin/send-mail.py --subject "..."

`--attach PATH` may be repeated. Attachments are sent with a MIME type guessed
from the extension (`.md` -> text/markdown, `.txt` -> text/plain, otherwise
application/octet-stream).

Every message is sent with explicit `Date` and `Message-ID` headers (a message
without them is routinely dropped or spam-filed by large providers such as
Gmail — this was the cause of a silently lost end-of-task report on
2026-09-08). On success the script prints the SMTP server's final acceptance
line (the queue id) and the set of refused recipients (empty = all accepted).

Exit: 0 sent, 2 config missing / bad arguments, 3 send failure.
"""
import argparse
import mimetypes
import os
import smtplib
import ssl
import sys
from email.message import EmailMessage
from email.utils import formatdate, make_msgid

DEFAULT_ENV = os.path.expanduser("~/.config/plugadmin/smtp.env")

# Small explicit table so a report always travels as readable text, not as an
# opaque octet-stream attachment.
_EXT_MIME = {
    ".md": ("text", "markdown"),
    ".markdown": ("text", "markdown"),
    ".txt": ("text", "plain"),
    ".log": ("text", "plain"),
    ".json": ("application", "json"),
    ".csv": ("text", "csv"),
}


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


def _guess_mime(path):
    ext = os.path.splitext(path)[1].lower()
    if ext in _EXT_MIME:
        return _EXT_MIME[ext]
    guessed, _ = mimetypes.guess_type(path)
    if guessed and "/" in guessed:
        maintype, subtype = guessed.split("/", 1)
        return maintype, subtype
    return "application", "octet-stream"


def _attach(msg, path):
    if not os.path.isfile(path):
        sys.stderr.write("send-mail: pièce jointe introuvable: %s\n" % path)
        sys.exit(2)
    maintype, subtype = _guess_mime(path)
    with open(path, "rb") as handle:
        data = handle.read()
    msg.add_attachment(data, maintype=maintype, subtype=subtype,
                       filename=os.path.basename(path))
    return len(data), "%s/%s" % (maintype, subtype)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--subject", required=True)
    parser.add_argument("--body-file")
    parser.add_argument("--to")
    parser.add_argument("--attach", action="append", default=[],
                        metavar="PATH", help="fichier à joindre (répétable)")
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

    sender = env["PLUGADMIN_SMTP_FROM"]
    msg = EmailMessage()
    msg["Subject"] = args.subject
    msg["From"] = sender
    msg["To"] = recipient
    # RFC 5322 requires Date; Gmail & co. penalise / drop mail without a
    # Message-ID. smtplib.send_message() adds neither — set them explicitly.
    msg["Date"] = formatdate(localtime=True)
    msg["Message-ID"] = make_msgid(domain=sender.split("@", 1)[-1])
    msg.set_content(body)

    attached = []
    for path in args.attach:
        size, mime = _attach(msg, path)
        attached.append("%s (%s, %d o)" % (os.path.basename(path), mime, size))

    host = env["PLUGADMIN_SMTP_HOST"]
    port = int(env["PLUGADMIN_SMTP_PORT"])
    try:
        context = ssl.create_default_context()
        with smtplib.SMTP_SSL(host, port, context=context, timeout=30) as smtp:
            smtp.login(env["PLUGADMIN_SMTP_USERNAME"], env["PLUGADMIN_SMTP_PASSWORD"])
            refused = smtp.send_message(msg)
            last_code, last_resp = smtp.noop()
    except Exception as exc:  # noqa: BLE001
        sys.stderr.write("send-mail: échec d'envoi (%s: %s)\n" % (type(exc).__name__, exc))
        sys.exit(3)

    if refused:
        sys.stderr.write("send-mail: destinataires refusés: %r\n" % (refused,))
        sys.exit(3)

    print("send-mail: envoyé à %s" % recipient)
    print("  sujet       : %s" % args.subject)
    print("  message-id  : %s" % msg["Message-ID"])
    if attached:
        print("  pièces      : %s" % "; ".join(attached))
    print("  refusés     : {} (aucun)")
    print("  session SMTP : OK (relais %s, dernier code %s)" % (host, last_code))
    return 0


if __name__ == "__main__":
    sys.exit(main())
