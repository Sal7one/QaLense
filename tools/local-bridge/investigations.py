"""Reviewed phone investigations: bounded memory ownership and explicit private saves.

This module never calls a model, captures media, connects a phone or invokes scripts.
An image authorized for handoff still requires separate model-send consent.
"""
import copy
import hashlib
import json
from pathlib import Path
import re
import threading
import time

import insights
from workbench import write_json

SCHEMA = "qalens-investigation-transfer/1"
MAX_DOCUMENT = 256 * 1024
MAX_PENDING = 10
MAX_PENDING_BYTES = 2 * 1024 * 1024
MAX_SAVED_LIST = 500
HASH = re.compile(r"[0-9a-f]{64}\Z")
TRANSFER_ID = re.compile(r"[A-Za-z0-9_.:-]{1,128}\Z")


def _numbers(value):
    if isinstance(value, dict):
        return {key: _numbers(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_numbers(item) for item in value]
    if type(value) is float and value == int(value):
        return int(value)
    return value


def encoded(document):
    return json.dumps(document, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def validate_document(value):
    """Normalize one portable case, ground any report and compute its complete content hash."""
    if not isinstance(value, dict) or value.get("schema") != SCHEMA or set(value) - {"schema", "bundle", "report"} or "bundle" not in value:
        raise ValueError("Use a reviewed qalens-investigation-transfer/1 document with a bundle and optional report.")
    try:
        if len(encoded(value)) > MAX_DOCUMENT:
            raise ValueError("Investigation exceeds the 256 KiB handoff limit.")
        # Consent for this explicit handoff is not consent to contact a model.
        bundle = insights.validate_bundle(value["bundle"], include_image=True)
        document = {"schema": SCHEMA, "bundle": bundle}
        if "report" in value:
            ids = {item["id"] for item in bundle["items"]} | {image["id"] for image in bundle.get("images", [])}
            runtime = bundle.get("investigation", {}).get("runtime")
            if runtime:
                ids.add(runtime["id"])
            report = insights.validate_report(value["report"], ids, bool(bundle.get("images")))
            document["report"] = insights.add_qa_report(report, bundle)
        document = _numbers(document)
        data = encoded(document)
        if len(data) > MAX_DOCUMENT:
            raise ValueError("Normalized investigation exceeds the 256 KiB handoff limit.")
    except (TypeError, RecursionError, UnicodeError, OverflowError):
        raise ValueError("Investigation must contain bounded UTF-8 JSON data.") from None
    return hashlib.sha256(data).hexdigest(), document, len(data)


def validate_inbox(value):
    if not isinstance(value, dict) or value.get("ok") is not True or not isinstance(value.get("transfers"), list) or len(value["transfers"]) > 10:
        raise ValueError("Phone returned an invalid investigation inbox; update its QaLens SDK.")
    dropped = value.get("dropped", 0)
    if type(dropped) is not int or not 0 <= dropped <= 9_000_000_000_000_000:
        raise ValueError("Phone returned an invalid inbox retention count.")
    seen = set()
    for transfer in value["transfers"]:
        ident = transfer.get("id") if isinstance(transfer, dict) else None
        if isinstance(ident, str) and TRANSFER_ID.fullmatch(ident):
            if ident in seen:
                raise ValueError("Phone returned duplicate transfer IDs; no cases were acknowledged.")
            seen.add(ident)
    return value["transfers"], dropped


class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.lock = threading.RLock()
        self.receive_lock = threading.Lock()
        self.identity = None
        self.pending = {}
        self.pending_bytes = 0

    def clear(self):
        with self.lock:
            self.pending.clear()
            self.pending_bytes = 0
            self.identity = None

    def bind(self, identity):
        with self.lock:
            if self.identity != identity:
                self.clear()
                self.identity = identity

    @staticmethod
    def metadata(digest, document, size, received_at, saved=False):
        bundle = document["bundle"]
        return {"hash": digest, "recordingName": bundle["recording"]["name"],
                "question": bundle["question"], "target": bundle.get("investigation", {}).get("target", "recorded-app"),
                "receivedAt": received_at, "byteSize": size, "hasReport": "report" in document,
                "hasImage": bool(bundle.get("images")),
                "qaTitle": document.get("report", {}).get("qaReport", {}).get("title", ""), "saved": saved}

    def previews(self):
        with self.lock:
            return {"ok": True, "previews": [self.metadata(digest, item["document"], item["size"], item["receivedAt"])
                                             for digest, item in reversed(list(self.pending.items()))],
                    "pendingBytes": self.pending_bytes, "capacity": MAX_PENDING, "byteCapacity": MAX_PENDING_BYTES}

    def _path(self, digest):
        if not isinstance(digest, str) or not HASH.fullmatch(digest):
            raise ValueError("Choose a valid investigation hash.")
        path = self.directory / (digest + ".json")
        if path.is_symlink() or path.resolve().parent != self.directory.resolve():
            raise ValueError("Investigation file must remain inside its private workspace.")
        return path

    def _saved_document(self, digest):
        path = self._path(digest)
        try:
            with path.open("rb") as stream:
                raw = stream.read(MAX_DOCUMENT + 2)
        except FileNotFoundError:
            raise ValueError("Investigation is not saved or its preview expired.") from None
        if len(raw) > MAX_DOCUMENT + 1:
            raise ValueError("Saved investigation exceeds its size limit.")
        try:
            actual, document, size = validate_document(insights._parse_json(raw))
        except (ValueError, UnicodeError):
            raise ValueError("Saved investigation is invalid or corrupted; it was not accepted.") from None
        if actual != digest:
            raise ValueError("Saved investigation content hash does not match its filename.")
        return document, size, int(path.stat().st_mtime * 1000)

    def accept(self, digest, document, size):
        """Caller already owns validated content; never evict an unseen case to acknowledge a new one."""
        with self.lock:
            if digest in self.pending:
                return "duplicate"
            path = self._path(digest)
            if path.exists():
                self._saved_document(digest)  # Corruption never counts as an owned duplicate.
                return "duplicate"
            if len(self.pending) >= MAX_PENDING or self.pending_bytes + size > MAX_PENDING_BYTES:
                return "blocked"
            self.pending[digest] = {"document": document, "size": size, "receivedAt": int(time.time() * 1000)}
            self.pending_bytes += size
            return "received"

    def document(self, digest):
        self._path(digest)
        with self.lock:
            item = self.pending.get(digest)
            if item:
                return {"ok": True, "hash": digest, "document": copy.deepcopy(item["document"]), "saved": False}
        document, _, _ = self._saved_document(digest)
        return {"ok": True, "hash": digest, "document": document, "saved": True}

    def save(self, digest):
        path = self._path(digest)
        with self.lock:
            item = self.pending.get(digest)
            if item is None:
                document, size, received_at = self._saved_document(digest)
                duplicate = True
            else:
                document, size, received_at = item["document"], item["size"], item["receivedAt"]
                duplicate = path.exists()
                if duplicate:
                    self._saved_document(digest)
                else:
                    write_json(path, document)
                    self._saved_document(digest)  # Confirm integrity before releasing the owned preview.
                self.pending.pop(digest)
                self.pending_bytes -= size
            metadata = self.metadata(digest, document, size, received_at, saved=True)
        return {"ok": True, "hash": digest, "duplicate": duplicate, "saved": metadata}

    def saved(self):
        try:
            paths = sorted(self.directory.glob("*.json"), key=lambda path: path.stat().st_mtime, reverse=True)
        except OSError:
            raise ValueError("Saved investigation directory is unavailable.") from None
        items, corrupt = [], 0
        for path in paths[:MAX_SAVED_LIST]:
            if not HASH.fullmatch(path.stem):
                corrupt += 1
                continue
            try:
                document, size, received_at = self._saved_document(path.stem)
                items.append(self.metadata(path.stem, document, size, received_at, saved=True))
            except (ValueError, OSError):
                corrupt += 1
        return {"ok": True, "saved": items, "corrupt": corrupt,
                "omitted": max(0, len(paths) - MAX_SAVED_LIST)}
