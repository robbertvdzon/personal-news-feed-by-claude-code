# syntax=docker/dockerfile:1.7
#
# Claude-tester image: extends claude-runner met Playwright + Chromium
# voor de tester-agent (Fase 5). Tester maakt screenshots van de live
# preview-deploy en upload'et die naar JIRA.
#
# Waarom een eigen image i.p.v. alles in claude-runner:
#   * Refiner/developer/reviewer hebben geen browser nodig — die pulled
#     onnodig ~300MB Chromium-binary bij elke pod-start.
#   * Tester is qua tools al een aparte categorie (browser-automation +
#     later cluster-read RBAC).
#
# De poller kiest welk image te gebruiken op basis van `AGENT_ROLE`.

FROM ghcr.io/robbertvdzon/claude-runner:main

# Base-image eindigt met USER 1001 — terug naar root voor de installs.
USER root

ENV DEBIAN_FRONTEND=noninteractive

# Playwright global + browser-binaries. `--with-deps` installeert alle
# OS-libs die Chromium nodig heeft (libnss3, libatk, libcairo, etc.) via
# apt-get; vereist root. We installeren ZOWEL chromium (volledige variant)
# ALS chromium-headless-shell — recente Playwright-API's gebruiken die
# laatste als default voor headless launch. Browser-binaries onder een
# globaal pad dat voor OpenShift's random-UID-met-GID-0 leesbaar blijft.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-cache

# NODE_PATH=/usr/lib/node_modules zodat `require('playwright')` werkt
# ongeacht de CWD van het script. Zonder dit faalt elke ad-hoc /tmp/flow.js
# met "Cannot find module 'playwright'" omdat Node niet in de global
# npm-prefix kijkt by default.
ENV NODE_PATH=/usr/lib/node_modules

RUN apt-get update \
    && npm install -g playwright \
    && PLAYWRIGHT_BROWSERS_PATH=/opt/pw-cache npx playwright install --with-deps chromium chromium-headless-shell \
    && chgrp -R 0 /opt/pw-cache \
    && chmod -R g=u /opt/pw-cache \
    && rm -rf /var/lib/apt/lists/*

# KAN-44: extra CLI's voor cluster + DB-inspectie tijdens tests.
#   * postgresql-client → `psql` voor preview-DB queries (PREVIEW_DB_URL)
#   * curl + jq → al gebruikt door de runner-flow; expliciet zodat het
#     niet wegvalt bij een base-image-refresh.
#   * oc + kubectl → cluster-state inspecteren in pnf-pr-N namespaces.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
         postgresql-client curl jq ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# kubectl — versie tracken op upstream-stable.
RUN KCTL_VERSION=$(curl -sL https://dl.k8s.io/release/stable.txt) \
    && curl -fsSL "https://dl.k8s.io/release/${KCTL_VERSION}/bin/linux/amd64/kubectl" \
         -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl

# oc — OpenShift-CLI. Mirror van Red Hat; tarball met daarin het binary.
RUN curl -fsSL https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable/openshift-client-linux.tar.gz \
         -o /tmp/oc.tar.gz \
    && tar -xzf /tmp/oc.tar.gz -C /usr/local/bin oc \
    && chmod +x /usr/local/bin/oc \
    && rm -f /tmp/oc.tar.gz

# Screenshot-wrapper. Tester roept 'm aan met:
#   take-screenshot.sh URL OUTPUT.png [VIEWPORT]
COPY take-screenshot.sh /usr/local/bin/take-screenshot.sh
RUN chmod +x /usr/local/bin/take-screenshot.sh

# Terug naar non-root zoals de base-image (random-UID via OpenShift,
# maar de Dockerfile-declaration moet 1001 zijn om npm-cache etc. weer
# naar /home/runner te krijgen via base-image's ENV-config).
USER 1001

# ENTRYPOINT erven we van claude-runner — geen override nodig.
