# Local browser QA

Full grounded consultation QA requires PostgreSQL, the KURE runtime, Spring Backend, and Next.js Frontend. OpenAI, Korean Law Open API, and OAuth credentials remain environment secrets and must not be printed by these utilities.

The `local` Spring profile enables the direct Korean Law Open API by default. It still requires the `LAW_OC` environment variable; set `LAW_OPEN_API_ENABLED=false` only when intentionally testing the fail-closed path.

From the Backend repository root, start KURE so the relative artifact root resolves correctly:

```powershell
py -m retrieval_runtime.runtime --host 127.0.0.1 --port 8091
```

The frozen local generation is `mvp-breadth-cc6cf90a40c3b013` (`53917d73-fb6c-4fdd-81a5-2e4fc28b0b3c`, 117 documents, dimension 128). Spring `bootRun` does not start KURE.

Check the stack without starting or stopping services:

```powershell
.\scripts\local\check-qa-stack.ps1
```

To reset only local user/runtime state, first inspect the plan and then opt in explicitly:

```powershell
.\scripts\local\reset-qa-state.ps1 -PlanOnly
.\scripts\local\reset-qa-state.ps1 -ConfirmReset RESET_LOCAL_QA
```

The reset refuses non-loopback PostgreSQL hosts and the `prod` Spring profile. It removes accounts, identities, sessions, consultations, derived analysis/report data, search contexts, quota/AI usage, and account emergency history. It preserves reviewed sources/chunks, procedures, retrieval generations and the active pointer, deterministic emergency scenarios, and Flyway history; preserved row counts are verified inside the reset transaction.
