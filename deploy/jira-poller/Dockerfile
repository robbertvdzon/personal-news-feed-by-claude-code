# syntax=docker/dockerfile:1.7
#
# JIRA-poller: pollt JIRA op AI Ready issues en spawnt claude-runner Jobs.
# Sinds Fase 1 ook een kleine HTTP-server (Flask + gunicorn) voor de
# /agent-run/complete-endpoint die runner-pods aanroepen na hun run.
#
# `--workers 1` is essentieel: meer workers betekent meer poll-threads
# en dat geeft dubbele JIRA-transitions.

FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# kubectl voor onze K8s-API-calls (Jobs spawnen, pods checken).
RUN KCTL_VERSION=$(curl -sL https://dl.k8s.io/release/stable.txt) \
    && curl -fsSL "https://dl.k8s.io/release/${KCTL_VERSION}/bin/linux/amd64/kubectl" -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl

WORKDIR /app
COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt

COPY poller.py /app/

RUN useradd -u 1001 -g 0 -m -d /home/poller poller \
    && chown -R 1001:0 /app

USER 1001

EXPOSE 8080
# Single worker: anders krijg je twee poll-threads en dubbele transitions.
CMD ["gunicorn", "-b", "0.0.0.0:8080", "--workers", "1", "--threads", "4", "--access-logfile", "-", "poller:app"]
