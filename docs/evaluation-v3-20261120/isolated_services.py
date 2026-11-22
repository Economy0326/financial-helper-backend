"""Verify the local test harness before permitting snapshot writes."""
import http.cookiejar
import json
import os
import subprocess
import urllib.request
from pathlib import Path
from uuid import UUID

from evaluation_run import MODEL, REPO, REVISION, check_port, claim_id, require


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def request(opener, url, data=None, headers=None):
    req = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with opener.open(req, timeout=90) as response:
        return json.load(response)


def listener(port):
    import psutil  # 로컬 테스트 프로세스의 환경과 연결을 확인할 때만 필요하다.

    sockets = [s for s in psutil.net_connections(kind="tcp") if s.status == psutil.CONN_LISTEN and s.laddr.port == port]
    require(len(sockets) == 1 and sockets[0].laddr.ip == "127.0.0.1" and sockets[0].pid, "unverified loopback listener")
    return psutil.Process(sockets[0].pid)


class IsolationError(RuntimeError):
    pass


class Services:
    def __init__(self, args):
        self.args = args
        self.generation = str(args.generation)
        self.backend = f"http://127.0.0.1:{args.backend_port}"
        self.kure = f"http://127.0.0.1:{args.kure_port}"
        self.db_name = "fh_test_" + args.isolation_id
        self.user = "fh_user_" + args.isolation_id[:12]
        self.cookies = http.cookiejar.CookieJar()
        self.api = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                              urllib.request.HTTPCookieProcessor(self.cookies))
        self.runtime = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    @staticmethod
    def docker(*args, sql=None):
        host = "npipe:////./pipe/docker_engine" if os.name == "nt" else "unix:///var/run/docker.sock"
        proc = subprocess.run(["docker", "--host", host, *args], input=sql, capture_output=True,
                              encoding="utf-8", timeout=30)
        require(proc.returncode == 0, "isolated Docker operation failed")
        return proc.stdout.strip()

    def preflight(self):
        run = self.args.isolation_id
        container = json.loads(self.docker("inspect", "fh-isolated-" + run))[0]
        self.container = container["Id"]
        config = container["Config"]
        binding, = container["NetworkSettings"]["Ports"]["5432/tcp"]
        self.db_port = int(binding["HostPort"])
        check_port(self.db_port)
        require(container["Name"] == "/fh-isolated-" + run and container["State"]["Running"]
                and config["Labels"].get("financial-helper.test-run") == run
                and config["Image"] == "postgres:16-alpine" and binding["HostIp"] == "127.0.0.1"
                and container["HostConfig"]["Tmpfs"].get("/var/lib/postgresql/data") and not container["Mounts"]
                and "POSTGRES_DB=" + self.db_name in config["Env"] and "POSTGRES_USER=" + self.user in config["Env"],
                "database isolation not verified")
        java = listener(self.args.backend_port)
        kure = listener(self.args.kure_port)
        env = java.environ()
        jdbc = f"jdbc:postgresql://127.0.0.1:{self.db_port}/{self.db_name}"
        peers = [c.raddr for c in java.net_connections(kind="tcp") if c.raddr and c.status == "ESTABLISHED"]
        require(java.name().lower() in {"java", "java.exe"} and "worker.org.gradle.process.internal.worker.GradleWorkerMain" in java.cmdline()
                and Path(java.cwd()).resolve() == REPO and env.get("FH_TEST_RUN_ID") == run
                and env.get("FH_TEST_CONTAINER_ID") == self.container and env.get("TEST_DB_URL") == jdbc
                and env.get("TEST_DB_USERNAME") == self.user and env.get("KURE_RUNTIME_ENDPOINT") == self.kure
                and env.get("KURE_RUNTIME_ENABLED", "").lower() == "true"
                and all(env.get(key, jdbc) == jdbc for key in ("SPRING_DATASOURCE_URL", "SPRING_FLYWAY_URL"))
                and any(peer.ip == "127.0.0.1" and peer.port == self.db_port for peer in peers)
                and not any(peer.port in {5432, 15432, 15439} for peer in peers),
                "Backend is not bound to the isolated test database and KURE endpoint")
        cmd = kure.cmdline()
        kure_env = kure.environ()
        temp_root = kure_env.get("TEMP") or kure_env.get("TMPDIR")
        require(temp_root and Path(temp_root).is_absolute(), "unverified KURE temporary directory")
        index = Path(temp_root).resolve() / ("fh-isolated-kure-" + run)
        require(any((Path(kure.cwd()) / arg).resolve() == REPO / "retrieval_runtime/runtime.py"
                    for arg in cmd if arg.endswith("runtime.py"))
                and "--index-root" in cmd and Path(cmd[cmd.index("--index-root") + 1]).resolve() == index
                and kure_env.get("FH_TEST_RUN_ID") == run, "KURE isolation not verified")
        self.java, self.kure_process = java, kure
        self.check_generation()
        meta = request(self.runtime, self.kure + "/v1/runtime")
        ready = request(self.runtime, self.kure + f"/v1/generations/{self.generation}/readiness")
        manifest = json.loads(ready["indexMetadataJson"])
        require(meta.get("dependenciesAvailable") is True and ready.get("ready") is True
                and ready["generationId"] == manifest["generationId"] == self.generation, "KURE generation unavailable")
        for item in (meta, manifest):
            require(item["modelIdentifier"] == item["tokenizerIdentifier"] == MODEL
                    and item["modelRevision"] == item["tokenizerRevision"] == REVISION, "KURE model mismatch")

    def sql(self, query):
        return self.docker("exec", "-i", "-e", "PGOPTIONS=-c default_transaction_read_only=on", self.container,
                           "psql", "-XAt", "-h", "/var/run/postgresql", "-U", self.user, "-d", self.db_name,
                           "-v", "ON_ERROR_STOP=1", sql=query)

    def check_generation(self):
        observed = self.sql("SELECT g.id,a.activation_revision,g.model_identifier,g.model_revision,"
                            "g.tokenizer_identifier,g.tokenizer_revision FROM active_retrieval_generation a "
                            "JOIN retrieval_generation g ON g.id=a.retrieval_generation_id WHERE a.singleton_key")
        expected = [self.generation, str(self.args.generation_revision), MODEL, REVISION, MODEL, REVISION]
        if observed.split("|") != expected:
            raise IsolationError("isolated generation or representation changed")

    def guard(self):
        try:
            require(listener(self.args.backend_port) == self.java and listener(self.args.kure_port) == self.kure_process,
                    "service process changed")
            self.check_generation()
        except Exception:
            raise IsolationError("test service or generation no longer verified") from None

    def start_session(self):
        self.guard()
        request(self.api, self.backend + "/api/v1/session")
        self.token = next(c.value for c in self.cookies if c.name == "XSRF-TOKEN")

    def snapshot(self, question, selection=None):
        require(self.args.approve_test_snapshots, "snapshot writes were not approved")
        self.guard()
        payload = {"question": question}
        if selection:
            payload["selection"] = selection
        return request(self.api, self.backend + "/api/v1/information-evidence-snapshots", payload, {"X-XSRF-TOKEN": self.token})

    def query(self, question, ids):
        self.guard()
        result = request(self.runtime, self.kure + f"/v1/generations/{self.generation}/query",
                         {"generationId": self.generation, "query": question, "topK": 100, "subset": list(ids)})
        if result["generationId"] != self.generation:
            raise IsolationError("unexpected KURE result generation")
        return [ids[str(UUID(hit["sourceChunkId"]))] for hit in result["results"]][:3]

    def claim_map(self):
        rows = self.sql("SELECT c.source_chunk_id,c.candidate_id FROM source_chunk_indexing i "
                        "JOIN source_document_claim c ON c.source_chunk_id=i.source_chunk_id "
                        f"WHERE i.retrieval_generation_id='{self.generation}' AND i.indexing_status='READY' "
                        "AND c.extractor_version='generic-claim-v3'")
        ids = {}
        for row in rows.splitlines():
            chunk, claim = row.split("|")
            ids[str(UUID(chunk))] = claim_id(claim)
        require(ids, "empty v3 evaluation corpus")
        return ids
