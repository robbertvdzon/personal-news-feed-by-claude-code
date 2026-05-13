# syntax=docker/dockerfile:1.7
#
# JIRA-poller: pollt JIRA op AI Ready issues en spawnt claude-runner Jobs.
# Python + requests + kubectl binary (geen Python-K8s-client nodig).

FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# kubectl voor onze K8s-API-calls
RUN KCTL_VERSION=$(curl -sL https://dl.k8s.io/release/stable.txt) \
    && curl -fsSL "https://dl.k8s.io/release/${KCTL_VERSION}/bin/linux/amd64/kubectl" -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl

RUN pip install --no-cache-dir requests==2.32.3

RUN useradd -u 1001 -g 0 -m -d /home/poller poller \
    && mkdir -p /app \
    && chown -R 1001:0 /app

WORKDIR /app
COPY poller.py /app/poller.py
RUN chmod +x /app/poller.py

USER 1001
ENTRYPOINT ["python3", "/app/poller.py"]
