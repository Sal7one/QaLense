#!/usr/bin/env python3
"""Controlled, synthetic-only HTTP provider for Lens 2.0 integration checks. Not an LLM.

Emulates OpenAI-compatible and Ollama requests without weights, dependencies or payload logs.
Never use this fixture to evaluate model reasoning quality or diagnose a real host recording.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import signal
import threading
import time

MODEL = "qalens-synthetic-triage"
MAX_BODY = 512 * 1024


def evidence_from(messages):
    """Find the bundle in a structured or prompt-wrapped user message, with bounded work."""
    decoder = json.JSONDecoder()
    for message in reversed(messages):
        if not isinstance(message, dict) or message.get("role") != "user": continue
        content = message.get("content", "")
        if isinstance(content, list):
            content = "\n".join(part.get("text", "") for part in content if isinstance(part, dict) and part.get("type") == "text")
        if not isinstance(content, str): continue
        starts = [match.start() for match in re.finditer(r"\{", content)][:400]
        for start in starts:
            try: value, _ = decoder.raw_decode(content[start:])
            except ValueError: continue
            if isinstance(value, dict) and value.get("schema") == "qalens-insights-evidence/1": return value
            if isinstance(value, dict) and isinstance(value.get("bundle"), dict):
                if value["bundle"].get("schema") == "qalens-insights-evidence/1": return value["bundle"]
    raise ValueError("No synthetic evidence bundle in user messages")


def report_for(bundle):
    investigation = bundle.get("investigation", {})
    runtime = investigation.get("runtime")
    image_observations = [{"text": "One explicitly reviewed synthetic saved still was provided. This fixture only checks transport and does not interpret pixels.", "evidenceIds": ["images:0"]}] if bundle.get("images") else []
    if investigation.get("target") == "qalens-player" and isinstance(runtime, dict):
        return {
            "schema": "qalens-insights-report/1",
            "summary": "Synthetic test: investigate the current QaLens player separately from the app recorded in the archive. This fixture is not a model.",
            "observations": [{"text": "The submitted current-player snapshot describes media/clock/error state at review time, not a recorded host event.", "evidenceIds": ["player:runtime"]}] + image_observations,
            "hypotheses": [{"title": "Current replay state may explain the playback symptom", "confidence": "low",
                            "reasoning": "Inspect the reviewed player error, available media window and clock mapping. These do not prove the original app failed.",
                            "evidenceIds": ["player:runtime"], "nextChecks": ["Compare the same archive on a second supported player.", "Check decoder errors separately from the original app's telemetry."]}],
            "missingEvidence": ["Synthetic fixture cannot reproduce playback or evaluate reasoning.", "Player runtime is a point-in-time snapshot, not a trace of every seek."],
            "recommendedChecks": ["Reproduce the seek/scroll/playback action and review a fresh player snapshot."]
        }
    items = bundle.get("items", [])
    network = next((item for item in items if item.get("kind") == "network" and
                    str(item.get("details", {}).get("status", "")) == "503"), None)
    buffering = next((item for item in items if item.get("kind") == "logs" and
                      "buffer" in json.dumps(item).lower()), None)
    observed = list(image_observations)
    if network: observed.append({"text": "The synthetic stream request returned HTTP 503.", "evidenceIds": [network["id"]]})
    if buffering: observed.append({"text": "The synthetic player reported buffering near the request failure.", "evidenceIds": [buffering["id"]]})
    ids = [item["id"] for item in (network, buffering) if item]
    return {
        "schema": "qalens-insights-report/1",
        "summary": "Synthetic test: playback buffering coincides with an unsuccessful stream request. This fixture is not a model.",
        "observations": observed,
        "hypotheses": [{"title": "Playback may be waiting for the failed stream request", "confidence": "medium" if len(ids) == 2 else "low",
                        "reasoning": "The cited telemetry is consistent with failed stream delivery. Temporal association does not confirm a player defect or its cause.",
                        "evidenceIds": ids, "nextChecks": ["Inspect how the player handles an unsuccessful stream request.", "Capture decoder and buffering state to distinguish network delivery from decoding."]}],
        "missingEvidence": ["Synthetic fixture verifies image transport only; it does not analyze pixels." if bundle.get("images") else "No video pixels were sent.", "Decoder state and actual server implementation are not captured."],
        "recommendedChecks": ["Reproduce with a successful stream request and compare the same player state."]
    }


class ModelFixture(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, protocol="both", expose_slow=False):
        self.protocol = protocol
        self.expose_slow = expose_slow
        self.metrics_lock = threading.Lock()
        self.metrics = {"requests": 0, "chatRequests": 0, "lastEvidenceIds": [], "lastProtocol": None, "lastImageCount": 0, "lastTarget": None, "runtimeProvided": False}
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args): pass

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try: self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError): pass

    def do_OPTIONS(self): self.reply(200, {})

    def do_GET(self):
        with self.server.metrics_lock: self.server.metrics["requests"] += 1
        if self.path == "/fixture/status":
            with self.server.metrics_lock: self.reply(200, dict(self.server.metrics))
        elif self.path == "/v1/models" and self.server.protocol in {"both", "openai"}:
            models = [{"id": MODEL, "object": "model"}, {"id": "text-embedding-synthetic", "object": "model"}]
            if self.server.expose_slow: models.append({"id": "z-fixture-slow", "object": "model"})
            self.reply(200, {"object": "list", "data": models})
        elif self.path == "/api/tags" and self.server.protocol in {"both", "ollama"}:
            models = [{"name": MODEL, "model": MODEL}, {"name": "nomic-embed-text:synthetic", "model": "nomic-embed-text:synthetic"}]
            if self.server.expose_slow: models.append({"name": "z-fixture-slow", "model": "z-fixture-slow"})
            self.reply(200, {"models": models})
        else: self.reply(404, {"error": "Synthetic route unavailable"})

    def do_POST(self):
        supported = ((self.path == "/v1/chat/completions" and self.server.protocol in {"both", "openai"}) or
                     (self.path == "/api/chat" and self.server.protocol in {"both", "ollama"}))
        if not supported: self.reply(404, {"error": "Synthetic route unavailable"}); return
        try:
            count = int(self.headers.get("Content-Length", "0"))
            if not 0 < count <= MAX_BODY: raise ValueError("Synthetic request exceeds budget")
            self.connection.settimeout(5)
            body = json.loads(self.rfile.read(count))
            if not isinstance(body, dict) or not isinstance(body.get("messages"), list): raise ValueError("Expected messages")
            if body.get("stream") is not False: raise ValueError("Fixture checks non-streaming requests")
            bundle = evidence_from(body["messages"])
            report = report_for(bundle)
            model = body.get("model")
            if model == "fixture-invalid-json": content = "This synthetic response is deliberately malformed."
            else:
                if model == "fixture-unknown-reference":
                    report["observations"].append({"text": "Invented evidence must not become a fact.", "evidenceIds": ["logs:999999"]})
                    report["hypotheses"].append({"title": "Unsupported synthetic guess", "confidence": "high", "reasoning": "Unsupported reference.", "evidenceIds": ["crashes:999999"], "nextChecks": []})
                content = json.dumps(report)
            with self.server.metrics_lock:
                images = sum(len(message.get("images", [])) for message in body["messages"] if isinstance(message, dict))
                images += sum(sum(part.get("type") == "image_url" for part in message.get("content", []) if isinstance(part, dict))
                              for message in body["messages"] if isinstance(message, dict) and isinstance(message.get("content"), list))
                self.server.metrics.update(chatRequests=self.server.metrics["chatRequests"] + 1,
                    lastEvidenceIds=[item.get("id") for item in bundle.get("items", [])],
                    lastProtocol="openai" if self.path.startswith("/v1/") else "ollama", lastImageCount=images,
                    lastTarget=bundle.get("investigation", {}).get("target"), runtimeProvided=bool(bundle.get("investigation", {}).get("runtime")))
            if model in {"fixture-slow", "z-fixture-slow"}: time.sleep(15)
            if self.path.startswith("/v1/"):
                self.reply(200, {"id": "synthetic", "model": model, "choices": [{"message": {"role": "assistant", "content": content}, "finish_reason": "stop"}]})
            else: self.reply(200, {"model": model, "message": {"role": "assistant", "content": content}, "done": True})
        except (ValueError, OSError) as error: self.reply(400, {"error": str(error)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--protocol", choices=["both", "openai", "ollama"], default="both")
    parser.add_argument("--slow-model", action="store_true", help="Expose an additional 15-second synthetic chat model for UI cancellation checks")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535: parser.error("Invalid port")
    def stop(_signal, _frame): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    with ModelFixture(("127.0.0.1", args.port), args.protocol, expose_slow=args.slow_model) as server:
        print(f"QaLens synthetic model fixture (NOT an LLM): http://127.0.0.1:{server.server_port}", flush=True)
        try: server.serve_forever()
        except KeyboardInterrupt: pass


if __name__ == "__main__": main()
