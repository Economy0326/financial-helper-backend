"""Record scenario IDs, choices by step and Claim IDs; option text stays in memory."""
import csv
import sys
import time

sys.dont_write_bytecode = True
from evaluation_run import live_run, snapshot_result
from isolated_services import IsolationError


def evaluate(services, rows, output, failures):
    with (output / "clarification_raw.csv").open("x", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "initial_status", "initial_claims", "option_count", "selection_path", "final_status",
                         "final_claims", "gold_found", "steps", "latency_ms", "safety", "error"])
        for row in rows:
            started = time.perf_counter()
            initial_status = final_status = "ERROR"
            initial_claims, claims, path = [], [], []
            count, safety, error = 0, "N/A", ""
            try:
                final = services.snapshot(row["question"])
                initial_status, initial_claims = snapshot_result(final)
                count = len(final.get("options", []))
                if row["type"] == "해당없음":
                    safety = "NOT_RUN_FRONTEND_INTERACTION"
                elif row["selection_title"]:
                    selection = {}
                    for key, value in (("title", row["selection_title"]), ("purpose", row["purpose"])):
                        if not value or final["status"] != "NEEDS_CLARIFICATION":
                            break
                        if not any(option.get(key) == value for option in final.get("options", [])):
                            error = f"EXPECTED_{key.upper()}_NOT_OFFERED"
                            break
                        selection[key] = value
                        path.append(key)
                        final = services.snapshot(row["question"], selection)
                final_status, claims = snapshot_result(final)
                if row["type"] == "근거부족":
                    safe = final_status in {"NO_MATCH", "NEEDS_CLARIFICATION"} and not claims
                    safety = "PASS" if safe else "REVIEW"
            except IsolationError:
                failures.append(row["id"])
                raise
            except Exception:
                error = "REQUEST_FAILED"
            if error:
                failures.append(row["id"])
            writer.writerow([row["id"], initial_status, "|".join(initial_claims), count, ">".join(path),
                             final_status, "|".join(claims), int(row["gold_claim"] in claims), len(path),
                             round((time.perf_counter() - started) * 1000), safety, error])


if __name__ == "__main__":
    sys.exit(live_run("clarification", evaluate))
