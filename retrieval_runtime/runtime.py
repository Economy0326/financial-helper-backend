"""Small internal HTTP boundary for KURE-v2 document indexing.

The JVM remains the system of record.  This process owns only model loading
and generation-scoped PLAID artifacts.  Heavy dependencies and model weights
are loaded lazily so a backend can start with the runtime disabled.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

RUNTIME_VERSION = "kure-runtime-foundation-v1"
MODEL_IDENTIFIER = "nlpai-lab/KURE-v2"
MODEL_REVISION = "3431f86d399d666083890dbb882aced6708873bc"
TOKENIZER_IDENTIFIER = MODEL_IDENTIFIER
TOKENIZER_REVISION = MODEL_REVISION
TOKEN_VECTOR_DIMENSION = 128
MAX_DOCUMENT_TOKENS = 8192
QUERY_LENGTH = 64
QUERY_EXPANSION = True
INSTRUCTION_PREFIXES = False
INDEX_BACKEND = "PLAID"
DEFAULT_BATCH_SIZE = 32


class ContractError(ValueError):
    pass


def _require_equal(payload: dict[str, Any], key: str, expected: Any) -> None:
    if payload.get(key) != expected:
        raise ContractError(f"{key} does not match the pinned KURE-v2 contract")


def _validate_build(payload: dict[str, Any]) -> tuple[uuid.UUID, list[dict[str, str]]]:
    try:
        generation_id = uuid.UUID(str(payload["generationId"]))
    except (KeyError, ValueError, TypeError) as exc:
        raise ContractError("generationId must be a UUID") from exc

    for key, expected in (
        ("modelIdentifier", MODEL_IDENTIFIER),
        ("modelRevision", MODEL_REVISION),
        ("tokenizerIdentifier", TOKENIZER_IDENTIFIER),
        ("tokenizerRevision", TOKENIZER_REVISION),
    ):
        _require_equal(payload, key, expected)
    if not isinstance(payload.get("generationKey"), str) or not payload["generationKey"].strip():
        raise ContractError("generationKey is required")
    if not payload.get("chunkConfigVersion"):
        raise ContractError("chunkConfigVersion is required")
    snapshot = payload.get("corpusSnapshotSha256")
    if not isinstance(snapshot, str) or len(snapshot) != 64:
        raise ContractError("corpusSnapshotSha256 must be a SHA-256 value")
    try:
        int(snapshot, 16)
    except ValueError as exc:
        raise ContractError("corpusSnapshotSha256 must be hexadecimal") from exc
    try:
        encoding = json.loads(payload.get("encodingConfigJson", "{}"))
        index = json.loads(payload.get("indexConfigJson", "{}"))
    except (TypeError, json.JSONDecodeError) as exc:
        raise ContractError("encodingConfigJson and indexConfigJson must be JSON objects") from exc
    if (
        not isinstance(encoding, dict)
        or encoding.get("queryLength") != QUERY_LENGTH
        or encoding.get("documentMaxTokens") != MAX_DOCUMENT_TOKENS
        or encoding.get("doQueryExpansion") is not QUERY_EXPANSION
        or encoding.get("queryPrefix", "x") != ""
        or encoding.get("documentPrefix", "x") != ""
        or not isinstance(index, dict)
        or index.get("backend") != INDEX_BACKEND
        or index.get("nbits") != 4
        or index.get("seed") != 42
        or index.get("useFast") is not True
        or index.get("useTriton") is not False
    ):
        raise ContractError("encoding/index configuration does not match the pinned KURE-v2 contract")

    documents = payload.get("documents")
    if not isinstance(documents, list) or not documents:
        raise ContractError("documents must be a non-empty list")
    seen: set[str] = set()
    normalized: list[dict[str, str]] = []
    for document in documents:
        if not isinstance(document, dict):
            raise ContractError("each document must be an object")
        try:
            document_id = str(uuid.UUID(str(document["id"])))
            text = document["text"]
        except (KeyError, ValueError, TypeError) as exc:
            raise ContractError("each document needs a UUID id and text") from exc
        if document_id in seen or not isinstance(text, str) or not text.strip():
            raise ContractError("document ids must be unique and text nonblank")
        seen.add(document_id)
        normalized.append({"id": document_id, "text": text})
    return generation_id, normalized


class RuntimeEngine:
    def __init__(self, index_root: Path, batch_size: int = DEFAULT_BATCH_SIZE) -> None:
        self.index_root = index_root
        self.batch_size = batch_size
        self._model: Any = None
        self._tokenizer: Any = None
        self._dependency_error: str | None = None

    def metadata(self) -> dict[str, Any]:
        self._load_dependencies()
        return {
            "runtimeVersion": RUNTIME_VERSION,
            "dependenciesAvailable": self._dependency_error is None,
            "modelIdentifier": MODEL_IDENTIFIER,
            "modelRevision": MODEL_REVISION,
            "tokenizerIdentifier": TOKENIZER_IDENTIFIER,
            "tokenizerRevision": TOKENIZER_REVISION,
            "tokenVectorDimension": TOKEN_VECTOR_DIMENSION,
            "maxDocumentTokens": MAX_DOCUMENT_TOKENS,
            "queryLength": QUERY_LENGTH,
            "queryExpansion": QUERY_EXPANSION,
            "instructionPrefixes": INSTRUCTION_PREFIXES,
            "indexBackend": INDEX_BACKEND,
            "pylateVersion": self._package_version("pylate"),
            "fastPlaidVersion": self._package_version("fast-plaid"),
        }

    def tokenize(self, text: str) -> int:
        self._load_dependencies(require_runtime=True)
        encoded = self._tokenizer(
            text,
            add_special_tokens=True,
            truncation=False,
            return_attention_mask=False,
        )
        tokens = encoded["input_ids"]
        count = len(tokens)
        if count > MAX_DOCUMENT_TOKENS:
            raise ContractError("document exceeds maxDocumentTokens")
        return count

    def build(self, payload: dict[str, Any]) -> dict[str, Any]:
        generation_id, documents = _validate_build(payload)
        self._load_dependencies(require_runtime=True)
        self.index_root.mkdir(parents=True, exist_ok=True)
        final_dir = self.index_root / str(generation_id)
        manifest_path = final_dir / "manifest.json"
        if manifest_path.exists():
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            existing_ids = manifest.get("documentIds", [])
            requested_ids = [doc["id"] for doc in documents]
            if manifest.get("corpusSnapshotSha256") == payload["corpusSnapshotSha256"] and existing_ids == requested_ids:
                return {"generationId": str(generation_id), "readyDocumentIds": requested_ids, "indexMetadata": manifest}
            raise ContractError("generation artifact already exists with a different definition")

        texts = [doc["text"] for doc in documents]
        for text in texts:
            self.tokenize(text)
        staging = self.index_root / f".{generation_id}.staging"
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir(parents=True)
        try:
            from pylate import indexes

            index = indexes.PLAID(
                index_folder=str(staging),
                index_name="index",
                override=True,
                nbits=4,
                seed=42,
                use_fast=True,
                use_triton=False,
                show_progress=False,
                device=os.getenv("KURE_DEVICE", "cpu"),
            )
            embeddings = self._model.encode(
                texts,
                is_query=False,
                batch_size=self.batch_size,
                show_progress_bar=False,
            )
            index.add_documents(
                documents_ids=[doc["id"] for doc in documents],
                documents_embeddings=embeddings,
            )
            metadata = {
                "runtimeVersion": RUNTIME_VERSION,
                "generationId": str(generation_id),
                "documentIds": [doc["id"] for doc in documents],
                "documentCount": len(documents),
                "modelIdentifier": MODEL_IDENTIFIER,
                "modelRevision": MODEL_REVISION,
                "tokenizerIdentifier": TOKENIZER_IDENTIFIER,
                "tokenizerRevision": TOKENIZER_REVISION,
                "chunkConfigVersion": payload["chunkConfigVersion"],
                "corpusSnapshotSha256": payload["corpusSnapshotSha256"],
                "indexBackend": INDEX_BACKEND,
                "indexNbits": 4,
                "indexSeed": 42,
                "indexUseFast": True,
                "indexUseTriton": False,
                "tokenVectorDimension": TOKEN_VECTOR_DIMENSION,
                "maxDocumentTokens": MAX_DOCUMENT_TOKENS,
                "queryLength": QUERY_LENGTH,
                "queryExpansion": QUERY_EXPANSION,
                "instructionPrefixes": INSTRUCTION_PREFIXES,
            }
            (staging / "manifest.json").write_text(
                json.dumps(metadata, ensure_ascii=False, sort_keys=True),
                encoding="utf-8",
            )
            staging.replace(final_dir)
            return {
                "generationId": str(generation_id),
                "readyDocumentIds": metadata["documentIds"],
                "indexMetadata": metadata,
            }
        except Exception:
            if staging.exists():
                shutil.rmtree(staging)
            raise

    def readiness(self, generation_id: uuid.UUID) -> dict[str, Any]:
        manifest_path = self.index_root / str(generation_id) / "manifest.json"
        if not manifest_path.exists():
            raise FileNotFoundError(str(generation_id))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        return {
            "generationId": str(generation_id),
            "ready": True,
            "documentCount": int(manifest.get("documentCount", 0)),
            "indexMetadataJson": json.dumps(manifest, ensure_ascii=False, sort_keys=True),
        }

    def _load_dependencies(self, require_runtime: bool = False) -> None:
        if self._model is not None and self._tokenizer is not None:
            return
        try:
            from pylate import models
            from transformers import AutoTokenizer

            self._model = models.ColBERT(
                model_name_or_path=MODEL_IDENTIFIER,
                revision=MODEL_REVISION,
                query_prefix="",
                document_prefix="",
                query_length=QUERY_LENGTH,
                document_length=MAX_DOCUMENT_TOKENS,
                do_query_expansion=QUERY_EXPANSION,
                device=os.getenv("KURE_DEVICE", "cpu"),
            )
            self._tokenizer = AutoTokenizer.from_pretrained(
                MODEL_IDENTIFIER,
                revision=TOKENIZER_REVISION,
            )
            self._dependency_error = None
        except Exception as exc:  # dependencies/weights may intentionally be absent
            self._dependency_error = f"{type(exc).__name__}: {exc}"
            if require_runtime:
                raise RuntimeError("KURE runtime dependencies are unavailable") from exc

    @staticmethod
    def _package_version(module_name: str) -> str | None:
        try:
            from importlib.metadata import version
            return version(module_name)
        except Exception:
            return None


class Handler(BaseHTTPRequestHandler):
    engine: RuntimeEngine

    def _send(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self) -> None:  # noqa: N802
        try:
            if self.path == "/v1/health" or self.path == "/v1/runtime":
                self._send(HTTPStatus.OK, self.engine.metadata())
                return
            prefix = "/v1/generations/"
            suffix = "/readiness"
            if self.path.startswith(prefix) and self.path.endswith(suffix):
                generation_id = uuid.UUID(self.path[len(prefix):-len(suffix)])
                self._send(HTTPStatus.OK, self.engine.readiness(generation_id))
                return
            self._send(HTTPStatus.NOT_FOUND, {"error": "not found"})
        except FileNotFoundError:
            self._send(HTTPStatus.NOT_FOUND, {"error": "generation artifact not found"})
        except Exception as exc:
            self._send(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exc)})

    def do_POST(self) -> None:  # noqa: N802
        try:
            payload = self._json()
            if self.path == "/v1/tokenize":
                text = payload.get("text")
                if not isinstance(text, str) or not text.strip():
                    raise ContractError("text must be nonblank")
                self._send(HTTPStatus.OK, {"documentTokenCount": self.engine.tokenize(text)})
                return
            prefix = "/v1/generations/"
            suffix = "/build"
            if self.path.startswith(prefix) and self.path.endswith(suffix):
                path_id = uuid.UUID(self.path[len(prefix):-len(suffix)])
                body_id, _ = _validate_build(payload)
                if path_id != body_id:
                    raise ContractError("generation path and payload do not match")
                self._send(HTTPStatus.OK, self.engine.build(payload))
                return
            self._send(HTTPStatus.NOT_FOUND, {"error": "not found"})
        except ContractError as exc:
            self._send(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except Exception as exc:
            self._send(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exc)})

    def log_message(self, format: str, *args: Any) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("KURE_RUNTIME_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("KURE_RUNTIME_PORT", "8091")))
    parser.add_argument("--index-root", default=os.getenv("KURE_INDEX_ROOT", "var/retrieval-indexes"))
    parser.add_argument("--batch-size", type=int, default=int(os.getenv("KURE_BATCH_SIZE", str(DEFAULT_BATCH_SIZE))))
    args = parser.parse_args()
    Handler.engine = RuntimeEngine(Path(args.index_root), args.batch_size)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
