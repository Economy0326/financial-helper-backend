"""Shared preflight and exclusive, per-run output for the frozen v3 evaluation."""
import argparse
import csv
import hashlib
import io
import json
import os
import re
import subprocess
from contextlib import contextmanager
from pathlib import Path
from uuid import UUID, uuid4

BASELINE = Path(__file__).resolve().parent
REPO = BASELINE.parents[1]
VERSION = 1
MODEL = "nlpai-lab/KURE-v2"
REVISION = "3431f86d399d666083890dbb882aced6708873bc"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def output_path(value):
    requested = Path(value).expanduser()
    require(not requested.is_symlink(), "output must not be a symbolic link")
    path = requested.resolve()
    require(not path.is_relative_to(REPO), "output must be outside the source repository")
    require(not any((p / ".git").exists() or ((p / "HEAD").is_file() and (p / "objects").is_dir())
                    for p in (path, *path.parents)), "output must be outside Git repositories")
    require(path.parent.is_dir() and os.access(path.parent, os.W_OK) and not path.exists(),
            "use a NEW directory under an existing writable parent")
    return path


def read_input(path):
    data = path.read_bytes()
    with io.StringIO(data.decode("utf-8-sig"), newline="") as f:
        return list(csv.DictReader(f)), hashlib.sha256(data).hexdigest()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    with path.open("x", encoding="utf-8") as f:
        json.dump(value, f, indent=2)
        f.write("\n")


@contextmanager
def output_run(path, scope, input_hashes, provenance):
    commit = subprocess.check_output(["git", "-C", str(REPO), "rev-parse", "HEAD"], text=True).strip()
    meta = dict(config_version=VERSION, run_id=str(uuid4()), git_commit=commit, scope=scope,
                runner_sha256={name: digest(BASELINE / name) for name in (
                    "run_retrieval.py", "run_clarification.py", "summarize.py", "evaluation_run.py", "isolated_services.py")},
                input_sha256=input_hashes, **provenance)
    path.mkdir(mode=0o700)  # 동시 실행도 새 디렉터리를 확보한 한 실행만 진행한다.
    write_json(path / "run.json", {**meta, "status": "STARTED"})
    failures = []
    try:
        yield failures
    except BaseException:
        write_json(path / "result.json", {"status": "FAILED", "failed_items": failures})
        raise
    else:
        result = {"status": "FAILED" if failures else "COMPLETED", "failed_items": failures}
        if scope in {"retrieval", "clarification"}:
            result["raw_sha256"] = digest(path / f"{scope}_raw.csv")
        write_json(path / "result.json", result)


def claim_id(value):
    require(isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value), "unexpected Claim ID")
    return value


def snapshot_result(response):
    status = response["status"]
    require(status in {"FOUND", "NO_MATCH", "NEEDS_CLARIFICATION", "READY_CANDIDATES", "STALE", "PARTIAL",
                       "TEMPORARILY_UNAVAILABLE"}, "unexpected snapshot status")
    return status, [claim_id(e["claimId"]) for e in response.get("evidence", [])]


def live_args(scope, argv):
    p = argparse.ArgumentParser(description="Use the labeled tmpfs test DB and its loopback Gradle test Backend and KURE runtime.",
                                epilog="Live preflight needs Docker CLI and psutil with access to both test processes. "
                                       "All three services must already be running. Each invocation owns a new output directory.")
    p.add_argument("--output-dir", type=output_path, required=True, help="New directory outside any repository")
    p.add_argument("--input", type=Path, default=BASELINE / ("questions.csv" if scope == "retrieval" else "clarification_scenarios.csv"))
    p.add_argument("--isolation-id", required=True, help="32-character ID from the isolated test harness")
    p.add_argument("--backend-port", required=True, type=int)
    p.add_argument("--kure-port", required=True, type=int)
    p.add_argument("--generation", type=UUID, required=True)
    p.add_argument("--generation-revision", type=int, required=True)
    p.add_argument("--approve-test-snapshots", action="store_true", help="Allow snapshot POSTs only in the verified test Backend")
    a = p.parse_args(argv)
    require(a.approve_test_snapshots, "explicit --approve-test-snapshots required")
    require(re.fullmatch(r"[a-f0-9]{32}", a.isolation_id), "invalid test run ID")
    require(a.generation_revision >= 0, "invalid generation revision")
    for port in (a.backend_port, a.kure_port):
        check_port(port)
    require(a.backend_port != a.kure_port, "service ports must differ")
    return a


def check_port(port):
    require(1024 <= port <= 65535 and port not in {3000, 5432, 8080, 8092, 13000, 15432, 15439, 18080, 18092},
            "unexpected service or database port")


def live_run(scope, evaluate, argv=None):
    from isolated_services import Services

    args = live_args(scope, argv)
    rows, input_hash = read_input(args.input)
    key = "gold_claim_ids" if scope == "retrieval" else "gold_claim"
    pattern = r"Q[0-9]+" if scope == "retrieval" else r"C[0-9]+"
    ids = set()
    for row in rows:
        require(re.fullmatch(pattern, row["id"]) and row["id"] not in ids and row["question"].strip(),
                "invalid or duplicate input case")
        ids.add(row["id"])
        if row[key]:
            claim_id(row[key])
        if scope == "clarification":
            require({"type", "selection_title", "purpose"} <= row.keys(), "missing scenario fields")
    require(rows, "empty evaluation input")
    services = Services(args)
    services.preflight()
    info = dict(generation=str(args.generation), generation_revision=args.generation_revision,
                model=MODEL, model_revision=REVISION, tokenizer_revision=REVISION)
    with output_run(args.output_dir, scope, {"questions": input_hash}, info) as failures:
        services.start_session()
        evaluate(services, rows, args.output_dir, failures)
        services.check_generation()
    return 1 if failures else 0
