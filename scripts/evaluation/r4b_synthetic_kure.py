"""Offline synthetic comparison, using the repository's actual KURE runtime.

Input is produced by FH_R4B_KEYWORD_EVAL during the guarded isolated DB test.
The temporary semantic generation is not an activated product generation.
"""
import hashlib
import json
import math
import os
from pathlib import Path
import tempfile
import uuid

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["KURE_DEVICE"] = "cpu"

from retrieval_runtime.runtime import MODEL_IDENTIFIER, MODEL_REVISION, RuntimeEngine

ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "build" / "reports"
DATA = json.loads((REPORTS / "r4b-synthetic-corpus.json").read_text(encoding="utf-8"))
DOCUMENTS = DATA["documents"]
if len(DOCUMENTS) != 2 or {d["label"] for d in DOCUMENTS} != {"A", "B"}:
    raise ValueError("expected exactly the two isolated synthetic claims")
by_id = {d["id"]: d["label"] for d in DOCUMENTS}
by_label = {d["label"]: d["id"] for d in DOCUMENTS}
keyword = {}
for line in (REPORTS / "r4b-keyword-evaluation.md").read_text(encoding="utf-8").splitlines():
    cols = [part.strip() for part in line.split("|")]
    if len(cols) >= 7 and cols[1] not in {"사례", "---"}:
        raw = cols[4].strip("[] ")
        keyword[cols[1]] = [x.strip() for x in raw.split(",") if x.strip()]
if set(keyword) != {c["id"] for c in DATA["cases"]}:
    raise ValueError("keyword ranking and fixed cases differ")

generation = str(uuid.uuid4())
docs = [{"id": d["id"], "text": d["text"]} for d in DOCUMENTS]
payload = {
    "generationId": generation, "generationKey": "isolated-synthetic-r4b",
    "modelIdentifier": MODEL_IDENTIFIER, "modelRevision": MODEL_REVISION,
    "tokenizerIdentifier": MODEL_IDENTIFIER, "tokenizerRevision": MODEL_REVISION,
    "chunkConfigVersion": "synthetic-claim-snapshot",
    "corpusSnapshotSha256": hashlib.sha256(json.dumps(docs, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
    "encodingConfigJson": json.dumps({"queryLength": 64, "documentMaxTokens": 8192,
                                      "doQueryExpansion": True, "queryPrefix": "", "documentPrefix": ""}),
    "indexConfigJson": json.dumps({"backend": "PLAID", "nbits": 4, "seed": 42,
                                   "useFast": True, "useTriton": False}),
    "documents": docs,
}

def metrics(ranked, relevant):
    hits = len(set(ranked[:3]) & set(relevant))
    recall = hits / len(relevant)
    precision = hits / 3
    dcg = sum(1 / math.log2(i + 2) for i, label in enumerate(ranked[:3]) if label in relevant)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(3, len(relevant))))
    return recall, precision, dcg / ideal

lines = ["# R4-B 합성 KURE 및 RRF 비교", "", "KURE는 실제 고정 모델·PLAID CPU. 키워드는 격리 PostgreSQL 제품 검색 결과. 동일 합성 Chunk ID·본문과 질문을 사용하지만 KURE는 별도 임시 Generation에서 실행했으며 제품 활성 Generation에 연결한 실험이 아니다. RRF는 제품의 k=60 순위 공식을 재현한 오프라인 계산이다.", "", "| 사례 | 질문 | 기대 | 키워드 | KURE | RRF |", "|---|---|---|---|---|---|"]
scores = {name: [] for name in ("keyword", "kure", "rrf")}
with tempfile.TemporaryDirectory(prefix="r4b-eval-", dir=ROOT / "build") as temporary:
    engine = RuntimeEngine(Path(temporary))
    built = engine.build(payload)
    if set(built["readyDocumentIds"]) != set(by_id):
        raise RuntimeError("KURE membership mismatch")
    for case in DATA["cases"]:
        semantic = [by_id[hit["sourceChunkId"]] for hit in engine.query({
            "generationId": generation, "query": case["query"], "topK": 3,
            "subset": list(by_id),
        })["results"]]
        key = keyword[case["id"]][:3]
        weight = {}
        for ranking in (semantic, key):
            for i, label in enumerate(ranking, 1):
                weight[label] = weight.get(label, 0.0) + 1 / (60 + i)
        fused = sorted(weight, key=lambda label: (-weight[label], by_label[label]))[:3]
        for name, ranking in (("keyword", key), ("kure", semantic), ("rrf", fused)):
            if case["relevant"]:
                scores[name].append(metrics(ranking, case["relevant"]))
        lines.append(f"| {case['id']} | {case['query']} | {case['relevant']} | {key} | {semantic} | {fused} |")
lines.extend(["", "양성 질문의 macro 평균. Precision@3의 분모는 항상 3이며 근거 없는 질문은 평균에서 제외한다.", "", "| 방식 | Recall@3 | Precision@3 | NDCG@3 |", "|---|---:|---:|---:|"])
for name, values in scores.items():
    means = [sum(row[i] for row in values) / len(values) for i in range(3)]
    lines.append(f"| {name} | {means[0]:.4f} | {means[1]:.4f} | {means[2]:.4f} |")
(REPORTS / "r4b-kure-rrf-evaluation.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("KURE and offline RRF comparison written to build/reports/r4b-kure-rrf-evaluation.md")
