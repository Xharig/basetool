# hadolint ignore=DL3006
ARG BASE
FROM ${BASE}

COPY --chown=10001:10001 docker/sandbox/SANDBOX /app/SANDBOX
