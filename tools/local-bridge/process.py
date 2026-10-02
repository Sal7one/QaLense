#!/usr/bin/env python3
"""Run a trusted QaLens processor on a component JSON file, without adb or the GUI."""
import argparse
import json
from pathlib import Path
from types import SimpleNamespace
from workbench import Workbench, MAX_DOCUMENT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", required=True, help="QaLens component v1 JSON file")
    parser.add_argument("--pipeline-config", required=True)
    parser.add_argument("--pipeline", required=True)
    parser.add_argument("--data-dir", default="~/.qalens/bridge")
    args = parser.parse_args()
    bench = None
    try:
        source = Path(args.component)
        if source.stat().st_size > MAX_DOCUMENT + 4096: raise ValueError("Component file exceeds limit")
        bench = Workbench(args.data_dir, pipeline_config=args.pipeline_config)
        digest = bench.preview(json.loads(source.read_text()))["hash"]
        bench.save(digest)
        job = bench.run_pipeline(args.pipeline, digest)
        bench.worker.join()
        result = bench.jobs[job["id"]]
        print(json.dumps(result))
        return 0 if result["status"] == "completed" else 1
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Processor setup failed: {error}\n")
    finally:
        if bench: bench.close(SimpleNamespace(device_port=None, token=None))


if __name__ == "__main__":
    raise SystemExit(main())
