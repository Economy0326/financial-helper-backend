# Render Private Service image for the CPU-only KURE-v2 runtime.
# The model cache and generation artifacts are supplied through the mounted
# persistent disk; they are deliberately not copied from the Git repository.
FROM python:3.13-slim

WORKDIR /app
COPY retrieval_runtime/requirements.txt /app/retrieval_runtime/requirements.txt
RUN pip install --no-cache-dir -r /app/retrieval_runtime/requirements.txt
COPY retrieval_runtime /app/retrieval_runtime

ENV KURE_RUNTIME_HOST=0.0.0.0 \
    KURE_RUNTIME_PORT=10000 \
    KURE_INDEX_ROOT=/var/lib/kure/retrieval-indexes \
    HF_HOME=/var/lib/kure/huggingface \
    TRANSFORMERS_CACHE=/var/lib/kure/huggingface/transformers

EXPOSE 10000
ENTRYPOINT ["python", "-m", "retrieval_runtime.runtime"]
