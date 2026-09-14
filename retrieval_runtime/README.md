# KURE-v2 internal indexing runtime

This directory is the internal Python boundary for the approved KURE-v2
late-interaction document index. Install the pinned dependencies from
`requirements.txt`, then run:

```text
py -m retrieval_runtime.runtime --host 127.0.0.1 --port 8091
```

The runtime pins `nlpai-lab/KURE-v2` to revision
`3431f86d399d666083890dbb882aced6708873bc`, uses 128-dimensional token
vectors, 64-token query encoding, 8192-token document encoding, query
expansion, and a generation-scoped PLAID artifact. The JVM remains the source
of truth and passes stable SourceChunk UUIDs as document IDs.

Model weights are loaded lazily. `/v1/runtime` reports unavailable
dependencies rather than silently substituting another model or tokenizer.

