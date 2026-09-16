# Production deployment preparation

This runbook prepares the approved CARD MVP topology. It does not create
cloud services, change billing, configure DNS, or store credentials.

## Topology

```text
Vercel Next.js
  -> Render Web Service (Spring Boot, Singapore)
       -> Render Postgres (Singapore, private connection)
       -> Render Private Service (KURE-v2, Singapore)
       -> OpenAI API
       -> Korean Law Open API
```

The KURE service is separate from the JVM. The measured local KURE peak was
about 755 MiB, so `1c-2g` is the initial Render candidate. This is a plan
candidate, not a purchase decision.

## Render preparation

`render.yaml` is a preparation-only Blueprint. Review every plan and every
`sync: false` value in the Render dashboard before syncing it. The Blueprint
uses Singapore for all Render services/datastores and attaches a 1 GB disk to
the KURE service at `/var/lib/kure`.

The KURE disk must contain the reviewed `card-kb-d327ba8ebb24b484` generation
artifact and the model cache. The artifact is not committed to Git. Populate
the disk from an approved artifact transfer/bootstrap process, then verify:

```text
GET /v1/runtime
GET /v1/generations/{generationUuid}/readiness
```

The generation UUID in the runtime manifest, the PostgreSQL active generation
row, and the runtime response must match. A missing or mismatched manifest is
not a reason to fall back to keyword-only retrieval.

The Backend image binds Spring Boot to Render's `PORT` (default 10000) and
exposes `/health` for liveness and `/health/readiness` for the web-service
HTTP health check. Flyway runs through the normal application startup path;
`ddl-auto` remains disabled in the production profile.

`DB_URL` must be entered as a JDBC URL even when it is derived from Render's
internal Postgres connection details, for example
`jdbc:postgresql://<internal-host>:5432/<database>`.

## Vercel preparation

Next.js deploys without a custom adapter. Create a Vercel project from the
frontend repository and set only this public variable:

```text
NEXT_PUBLIC_API_BASE_URL=https://<api-domain>/api/v1
```

Use Preview and Production values separately. No OpenAI, LAW_OC, OAuth
secret, database, or KURE variable belongs in `NEXT_PUBLIC_*`.

## OAuth and browser security

After the final API and app domains are chosen, register these exact callback
forms with Kakao and Naver:

```text
https://<api-domain>/api/v1/auth/kakao/callback
https://<api-domain>/api/v1/auth/naver/callback
```

Set the corresponding frontend success URIs to
`https://<app-domain>/account`. Keep `Secure`, `HttpOnly`, CSRF, and the
single explicit `FRONTEND_ORIGIN` allowlist enabled. The recommended
`app.<domain>` and `api.<domain>` arrangement keeps the cookies same-site;
using unrelated provider domains requires a deliberate SameSite/CORS review.

## Smoke gate for Work 8.1

Do not call the system production-ready until the deployed smoke covers:

1. backend `/health` and `/health/readiness`;
2. KURE runtime metadata and generation readiness;
3. Postgres/Flyway connectivity;
4. session bootstrap and CSRF first mutation;
5. Kakao and Naver callback/session/logout;
6. Emergency guest deterministic response;
7. authenticated CARD consultation through stored grounded report.

This file records preparation only. It does not claim a cloud deployment.
