"""Summarize an explicit retrieval CSV; input and frozen artifacts are never opened for writing."""
import argparse
import csv
import json
import math
import statistics
import sys
from pathlib import Path
from uuid import UUID

sys.dont_write_bytecode = True
from evaluation_run import MODEL, REVISION, output_path, output_run, read_input, require

METHODS = ("FTS", "KURE", "HYBRID_PRODUCT")


def percentile(values, fraction):
    ordered = sorted(values)
    pos = (len(ordered) - 1) * fraction
    lower = int(pos)
    return ordered[lower] + (ordered[math.ceil(pos)] - ordered[lower]) * (pos - lower)


def metrics(rows, questions):
    output = []
    for method in METHODS:
        subset = [r for r in rows if r["method"] == method]
        good = [r for r in subset if r["status"] != "ERROR"]
        positive = [r for r in good if questions[r["id"]]]
        negative = [r for r in good if not questions[r["id"]]]
        hits, ndcg = 0, 0.0
        for row in positive:
            ranks = [row["rank1"], row["rank2"], row["rank3"]]
            target = questions[row["id"]]
            if target in ranks:
                hits += 1
                ndcg += 1 / math.log2(2 + ranks.index(target))
        times = [int(r["latency_ms"]) for r in good]
        n = len(positive)
        output.append(dict(method=method, cases=len(subset), errors=len(subset) - len(good), positive_n=n,
                           known_gold_hit_at_3=round(hits / n, 4) if n else None,
                           single_label_p_at_3_proxy=round(hits / (3 * n), 4) if n else None,
                           single_label_ndcg_at_3_proxy=round(ndcg / n, 4) if n else None,
                           no_gold_candidates=sum(bool(r["rank1"]) for r in negative), negative_n=len(negative),
                           p50_ms=round(statistics.median(times)) if times else None,
                           p95_ms=round(percentile(times, .95)) if times else None))
    return output


def source_info(raw, raw_hash, question_hash):
    source = raw.parent / "run.json"
    info = dict(generation=None, generation_revision=None, model=None, model_revision=None, tokenizer_revision=None)
    if not source.exists():
        return info
    meta = json.loads(source.read_text(encoding="utf-8"))
    result = json.loads((raw.parent / "result.json").read_text(encoding="utf-8"))
    require(result.get("raw_sha256") == raw_hash and result.get("status") in {"COMPLETED", "FAILED"},
            "unfinished or modified retrieval output")
    require(meta["scope"] == "retrieval" and raw.name == "retrieval_raw.csv"
            and meta["input_sha256"]["questions"] == question_hash, "retrieval provenance mismatch")
    require(meta["model"] == MODEL and meta["model_revision"] == meta["tokenizer_revision"] == REVISION
            and type(meta["generation_revision"]) is int and meta["generation_revision"] >= 0,
            "unknown retrieval representation")
    return dict(generation=str(UUID(meta["generation"])), generation_revision=meta["generation_revision"],
                model=MODEL, model_revision=REVISION, tokenizer_revision=REVISION,
                source_run_id=str(UUID(meta["run_id"])), source_status=result["status"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=output_path)
    parser.add_argument("--raw", required=True, type=Path)
    parser.add_argument("--questions", required=True, type=Path)
    args = parser.parse_args(argv)
    cases, question_hash = read_input(args.questions)
    questions = {r["id"]: r["gold_claim_ids"] for r in cases}
    rows, raw_hash = read_input(args.raw)
    observed = [(r["id"], r["method"]) for r in rows]
    require(questions and len(set(observed)) == len(rows)
            and set(observed) == {(case, m) for case in questions for m in METHODS}, "incomplete or duplicate retrieval rows")
    results = metrics(rows, questions)
    info = source_info(args.raw, raw_hash, question_hash)
    with output_run(args.output_dir, "retrieval_summary", {"questions": question_hash, "raw": raw_hash}, info):
        from matplotlib.backends.backend_agg import FigureCanvasAgg
        from matplotlib.figure import Figure

        with (args.output_dir / "retrieval_summary.csv").open("x", encoding="utf-8-sig", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=results[0])
            writer.writeheader()
            writer.writerows(results)
        fig = Figure(figsize=(11, 4))
        FigureCanvasAgg(fig)
        ranks, latency = fig.subplots(1, 2)
        ranks.bar(METHODS, [x["known_gold_hit_at_3"] if x["positive_n"] else math.nan for x in results])
        ranks.set_ylim(0, 1)
        ranks.set_title("Known target Hit@3 (different stages)")
        latency.bar(METHODS, [x["p50_ms"] / 1000 if x["p50_ms"] is not None else math.nan for x in results])
        latency.set_title("p50 per call (seconds, includes guards)")
        fig.tight_layout()
        with (args.output_dir / "retrieval_summary.png").open("xb") as f:
            fig.savefig(f, format="png", dpi=140)


if __name__ == "__main__":
    main()
