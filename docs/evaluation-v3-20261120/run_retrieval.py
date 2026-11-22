"""Evaluate only on a verified isolated stack. See --help for required arguments."""
import base64
import csv
import re
import sys
import time

sys.dont_write_bytecode = True
from evaluation_run import live_run, snapshot_result
from isolated_services import IsolationError


def quoted(text):
    encoded = base64.b64encode(text.encode()).decode("ascii")
    return f"convert_from(decode('{encoded}','base64'),'UTF8')"


def keyword(sql, question, ids):
    query = quoted(question)
    base = (" FROM source_chunk s JOIN source_document d ON d.id=s.source_document_id "
            "WHERE s.review_status='APPROVED' AND d.status='ACTIVE' AND ")
    vector_query = f"websearch_to_tsquery('simple',{query})"

    def ranked(expression):
        return sql("SELECT s.id::text" + base + f"s.keyword_search_vector @@ {expression} "
                   f"ORDER BY ts_rank_cd(s.keyword_search_vector,{expression}) DESC,s.id LIMIT 100").splitlines()

    found = ranked(vector_query)
    if not found:
        terms = []
        for word in re.findall(r"[^\W_]+", question):
            if word in {"얼마", "얼마인가요", "얼마입니까", "얼마예요", "어떻게"}:
                continue
            term = re.sub(r"(에서도|에게도|으로는|에는|에도|에서|으로|에게|이나|이나요|은|는|이|가|을|를|의|에|도)$", "", word)
            if len(term) >= 2 and term not in terms:
                terms.append(term)
        if len(terms) >= 2:
            prefix = quoted(" & ".join(term + ":*" for term in terms))
            found = ranked(f"to_tsquery('simple',{prefix})")
    context = sql("SELECT s.id::text" + base + "to_tsvector('simple',coalesce(d.title,'') || ' ' || "
                  f"coalesce(s.parent_section,'') || ' ' || s.body) @@ {vector_query} ORDER BY s.id LIMIT 100")
    found = list(dict.fromkeys(found + context.splitlines()))
    return [ids[chunk] for chunk in found[:100] if chunk in ids][:3]


def evaluate(services, rows, output, failures):
    ids = services.claim_map()
    with (output / "retrieval_raw.csv").open("x", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "method", "status", "latency_ms", "rank1", "rank2", "rank3", "gold_found_at3", "error"])
        for row in rows:
            for method in ("FTS", "KURE", "HYBRID_PRODUCT"):
                started = time.perf_counter()
                hits, status, error = [], "OK", ""
                try:
                    if method == "FTS":
                        services.check_generation()
                        hits = keyword(services.sql, row["question"], ids)
                    elif method == "KURE":
                        hits = services.query(row["question"], ids)
                    else:
                        status, hits = snapshot_result(services.snapshot(row["question"]))
                except IsolationError:
                    failures.append(row["id"] + "/" + method)
                    raise
                except Exception:
                    status, error = "ERROR", "REQUEST_FAILED"
                    failures.append(row["id"] + "/" + method)
                ranks = hits[:3]
                writer.writerow([row["id"], method, status, round((time.perf_counter() - started) * 1000),
                                 *(ranks + [""] * (3 - len(ranks))), int(row["gold_claim_ids"] in ranks), error])


if __name__ == "__main__":
    sys.exit(live_run("retrieval", evaluate))
