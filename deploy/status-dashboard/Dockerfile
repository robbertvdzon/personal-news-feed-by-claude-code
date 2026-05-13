# syntax=docker/dockerfile:1.7
#
# Status-dashboard image. Small Python service + kubectl binary.
# - flask + requests + gunicorn voor de HTTP-server
# - kubectl voor cluster-queries (Applications + pods)

FROM python:3.12-slim

ARG TARGETARCH
ARG KUBECTL_VERSION=v1.30.5

# kubectl meebrengen — alleen dat, geen oc nodig.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" \
       -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl \
    && apt-get purge -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py /app/

# Non-root user (UID 1000) zodat we niet als root draaien — OpenShift
# SCC restricted-v2 friendly. We hebben geen schrijfrechten op de FS
# nodig.
RUN useradd -u 1000 -m -s /sbin/nologin app
USER 1000

EXPOSE 8080
# Single worker — alle state in-memory, één route. Gunicorn vooral voor
# graceful shutdowns + access-logs.
CMD ["gunicorn", "-b", "0.0.0.0:8080", "--workers", "1", "--access-logfile", "-", "app:app"]
