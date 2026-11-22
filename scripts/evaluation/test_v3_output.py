import argparse
import contextlib
import csv
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from uuid import UUID

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "docs/evaluation-v3-20261120"
sys.path.insert(0, str(BASE))

import evaluation_run as run
import isolated_services as isolated
import run_clarification
import run_retrieval
import summarize

CLAIM = "a" * 64
GEN = "15c16a93-2a2b-4186-a71d-2265c0faab66"
RUN = "b" * 32
CHUNK = "ad57b63a-49d7-4851-989d-0678b6c592d5"
PRIVATE = "SYNTHETIC_PRIVATE_TEXT_DO_NOT_EXPORT"


class OutputTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.output = self.root / "new-run"
        self.questions = self.root / "questions.csv"
        self.questions.write_text(f"id,question,gold_claim_ids\nQ01,{PRIVATE},{CLAIM}\n", encoding="utf-8")
        self.scenarios = self.root / "cases.csv"
        self.scenarios.write_text(f"id,type,question,gold_claim,selection_title,purpose\nC01,주제,{PRIVATE},{CLAIM},{PRIVATE},\n",
                                  encoding="utf-8")
        self.service_cls = self.enterContext(patch.object(isolated, "Services", autospec=True))
        self.services = self.service_cls.return_value
        self.services.claim_map.return_value = {CHUNK: CLAIM}
        self.services.sql.return_value = CHUNK
        self.services.query.return_value = [CLAIM]
        self.response = {"status": "READY_CANDIDATES", "evidence": [{"claimId": CLAIM, "excerpt": PRIVATE}]}
        self.services.snapshot.return_value = self.response

    def args(self, scope, output):
        return ["--output-dir", str(output), "--input", str(self.questions if scope == "retrieval" else self.scenarios),
                "--generation", GEN, "--generation-revision", "3", "--backend-port", "41001", "--kure-port", "41002",
                "--isolation-id", RUN, "--approve-test-snapshots"]

    def test_paths_fail_before_external_calls_or_output(self):
        other = self.root / "another-repo"
        other.mkdir()
        (other / ".git").touch()
        existing = self.root / "existing"
        existing.mkdir()
        result = existing / "retrieval_raw.csv"
        result.write_bytes(b"original")
        for scope, evaluate in (("retrieval", run_retrieval.evaluate), ("clarification", run_clarification.evaluate)):
            for path in (BASE, BASE / "new-run", ROOT / "new-run", other / "new-run", existing):
                with self.subTest(scope=scope, path=path), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    run.live_run(scope, evaluate, self.args(scope, path))
        self.service_cls.assert_not_called()
        self.assertEqual(result.read_bytes(), b"original")
        self.assertFalse((BASE / "active_corpus.json").exists())

    def test_invalid_settings_and_missing_consent_make_no_calls(self):
        for flag, value in (("--backend-port", "18080"), ("--kure-port", "18092"), ("--isolation-id", "demo")):
            args = self.args("retrieval", self.output)
            args[args.index(flag) + 1] = value
            with self.assertRaises(ValueError):
                run.live_run("retrieval", run_retrieval.evaluate, args)
        with self.assertRaises(ValueError):
            run.live_run("retrieval", run_retrieval.evaluate, self.args("retrieval", self.output)[:-1])
        self.service_cls.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_failed_environment_preflight_creates_nothing(self):
        self.services.preflight.side_effect = ValueError("unsafe")
        with self.assertRaises(ValueError):
            run.live_run("retrieval", run_retrieval.evaluate, self.args("retrieval", self.output))
        self.services.start_session.assert_not_called()
        self.services.snapshot.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_retrieval_and_summary_use_separate_exclusive_outputs(self):
        self.assertEqual(run.live_run("retrieval", run_retrieval.evaluate, self.args("retrieval", self.output)), 0)
        raw = self.output / "retrieval_raw.csv"
        before = raw.read_bytes()
        summary = self.root / "summary"
        args = ["--raw", str(raw), "--questions", str(self.questions), "--output-dir", str(summary)]
        summarize.main(args)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            summarize.main(args)
        with (summary / "retrieval_summary.csv").open(encoding="utf-8-sig") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual([float(r["known_gold_hit_at_3"]) for r in rows], [1, 1, 1])
        self.assertTrue((summary / "retrieval_summary.png").read_bytes().startswith(b"\x89PNG"))
        self.assertEqual(raw.read_bytes(), before)
        meta = json.loads((summary / "run.json").read_text())
        self.assertEqual(meta["generation"], GEN)
        self.assertEqual(meta["input_sha256"]["raw"], hashlib.sha256(before).hexdigest())
        self.assertFalse(any(p.name == "active_corpus.json" for p in self.root.rglob("*")))
        self.assertNotIn(PRIVATE.encode(), b"".join(p.read_bytes() for p in self.output.iterdir()))

    def test_clarification_never_exports_option_or_evidence_text(self):
        self.services.snapshot.side_effect = [
            {"status": "NEEDS_CLARIFICATION", "options": [{"title": PRIVATE, "excerpt": PRIVATE}]}, self.response]
        self.assertEqual(run.live_run("clarification", run_clarification.evaluate, self.args("clarification", self.output)), 0)
        self.assertNotIn(PRIVATE.encode(), b"".join(p.read_bytes() for p in self.output.iterdir()))
        self.assertEqual(run.read_input(self.output / "clarification_raw.csv")[0][0]["selection_path"], "title")

    def test_errors_record_only_case_and_method(self):
        self.services.query.side_effect = ValueError(PRIVATE)
        self.assertEqual(run.live_run("retrieval", run_retrieval.evaluate, self.args("retrieval", self.output)), 1)
        result = json.loads((self.output / "result.json").read_text())
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["failed_items"], ["Q01/KURE"])
        self.assertNotIn(PRIVATE.encode(), b"".join(p.read_bytes() for p in self.output.iterdir()))

    def test_database_generation_change_stops_before_next_snapshot(self):
        self.services.check_generation.side_effect = isolated.IsolationError("changed")
        with self.assertRaises(isolated.IsolationError):
            run.live_run("retrieval", run_retrieval.evaluate, self.args("retrieval", self.output))
        self.services.snapshot.assert_not_called()
        self.assertEqual(json.loads((self.output / "result.json").read_text())["status"], "FAILED")

    def test_direct_entry_points_require_output_directory(self):
        for script in ("run_retrieval.py", "run_clarification.py", "summarize.py"):
            result = subprocess.run([sys.executable, "-B", str(BASE / script)], cwd=self.root, capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn(b"--output-dir", result.stderr)


class IsolationTest(unittest.TestCase):
    def setUp(self):
        self.args = argparse.Namespace(isolation_id=RUN, generation=UUID(GEN), generation_revision=3,
                                       backend_port=41001, kure_port=41002, approve_test_snapshots=True)
        self.services = isolated.Services(self.args)
        self.container = {"Id": "c" * 64, "Name": "/fh-isolated-" + RUN, "State": {"Running": True},
                          "Config": {"Image": "postgres:16-alpine", "Labels": {"financial-helper.test-run": RUN},
                                     "Env": ["POSTGRES_DB=fh_test_" + RUN, "POSTGRES_USER=fh_user_" + RUN[:12]]},
                          "HostConfig": {"Tmpfs": {"/var/lib/postgresql/data": "rw"}},
                          "NetworkSettings": {"Ports": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "50001"}]}},
                          "Mounts": []}
        self.java, self.kure = Mock(), Mock()
        self.java.name.return_value = "java.exe"
        self.java.cwd.return_value = self.kure.cwd.return_value = str(ROOT)
        self.java.cmdline.return_value = ["java", "worker.org.gradle.process.internal.worker.GradleWorkerMain"]
        self.java.net_connections.return_value = [SimpleNamespace(raddr=SimpleNamespace(ip="127.0.0.1", port=50001),
                                                                  status="ESTABLISHED")]
        self.java.environ.return_value = {"FH_TEST_RUN_ID": RUN, "FH_TEST_CONTAINER_ID": self.container["Id"],
                                         "TEST_DB_URL": "jdbc:postgresql://127.0.0.1:50001/fh_test_" + RUN,
                                         "TEST_DB_USERNAME": "fh_user_" + RUN[:12],
                                         "KURE_RUNTIME_ENDPOINT": self.services.kure, "KURE_RUNTIME_ENABLED": "true"}
        self.kure.environ.return_value = {"FH_TEST_RUN_ID": RUN, "TEMP": tempfile.gettempdir()}
        self.kure.cmdline.return_value = ["python", "retrieval_runtime/runtime.py", "--index-root",
                                           str(Path(tempfile.gettempdir()) / ("fh-isolated-kure-" + RUN))]
        self.enterContext(patch.object(isolated, "listener", side_effect=lambda p: self.java if p == 41001 else self.kure))
        self.enterContext(patch.object(self.services, "docker", side_effect=lambda *args: json.dumps([self.container])))
        self.sql = self.enterContext(patch.object(self.services, "sql",
                    return_value="|".join([GEN, "3", run.MODEL, run.REVISION, run.MODEL, run.REVISION])))
        self.model = dict(modelIdentifier=run.MODEL, tokenizerIdentifier=run.MODEL,
                          modelRevision=run.REVISION, tokenizerRevision=run.REVISION)
        self.http = self.enterContext(patch.object(isolated, "request", side_effect=[
            {**self.model, "dependenciesAvailable": True},
            {"ready": True, "generationId": GEN, "indexMetadataJson": json.dumps({**self.model, "generationId": GEN})}]))

    def test_matching_isolated_topology(self):
        self.services.preflight()
        self.assertEqual(self.http.call_count, 2)

    def test_demo_database_or_wrong_backend_binding_rejected_before_http(self):
        self.container["Mounts"] = [{"Type": "volume"}]
        with self.assertRaises(ValueError):
            self.services.preflight()
        self.container["Mounts"] = []
        self.java.environ.return_value["SPRING_DATASOURCE_URL"] = "jdbc:postgresql://127.0.0.1:15439/demo"
        with self.assertRaises(ValueError):
            self.services.preflight()
        self.http.assert_not_called()

    def test_wrong_model_blocks_session(self):
        self.http.side_effect = [{**self.model, "modelRevision": "wrong", "dependenciesAvailable": True},
                                 {"generationId": GEN, "ready": True, "indexMetadataJson": json.dumps({**self.model, "generationId": GEN})}]
        with self.assertRaises(ValueError):
            self.services.preflight()
        self.assertEqual(self.http.call_count, 2)

    def test_generation_change_blocks_post(self):
        self.services.preflight()
        self.sql.return_value = "different"
        with self.assertRaises(isolated.IsolationError):
            self.services.snapshot("synthetic")
        self.assertEqual(self.http.call_count, 2)

    def test_claim_mapping_selects_only_identifiers(self):
        self.sql.return_value = CHUNK + "|" + CLAIM
        self.assertEqual(self.services.claim_map(), {CHUNK: CLAIM})
        projection, _ = self.sql.call_args.args[0].split(" FROM ", 1)
        self.assertEqual(projection, "SELECT c.source_chunk_id,c.candidate_id")


class BaselineTest(unittest.TestCase):
    def test_frozen_artifacts_match_recorded_git_and_checkout_hashes(self):
        manifest = json.loads((ROOT / "scripts/evaluation/v3-baseline-sha256.json").read_text())
        self.assertEqual(len(manifest), 12)
        for name, hashes in manifest.items():
            with self.subTest(name=name):
                self.assertIn(run.digest(BASE / name), hashes)
                data = subprocess.check_output(["git", "-C", str(ROOT), "show", f"HEAD:docs/evaluation-v3-20261120/{name}"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), hashes[0])
