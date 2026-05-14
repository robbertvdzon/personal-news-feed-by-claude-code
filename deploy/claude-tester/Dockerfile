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

# Playwright global + Chromium met system-deps. `--with-deps` installeert
# alle OS-libs die Chromium nodig heeft (libnss3, libatk, libcairo, etc.)
# via apt-get; vereist root. Browser-binaries onder een globaal pad dat
# voor OpenShift's random-UID-met-GID-0 leesbaar blijft.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-cache
RUN apt-get update \
    && npm install -g playwright \
    && PLAYWRIGHT_BROWSERS_PATH=/opt/pw-cache npx playwright install --with-deps chromium \
    && chgrp -R 0 /opt/pw-cache \
    && chmod -R g=u /opt/pw-cache \
    && rm -rf /var/lib/apt/lists/*

# Screenshot-wrapper. Tester roept 'm aan met:
#   take-screenshot.sh URL OUTPUT.png [VIEWPORT]
COPY take-screenshot.sh /usr/local/bin/take-screenshot.sh
RUN chmod +x /usr/local/bin/take-screenshot.sh

# Terug naar non-root zoals de base-image (random-UID via OpenShift,
# maar de Dockerfile-declaration moet 1001 zijn om npm-cache etc. weer
# naar /home/runner te krijgen via base-image's ENV-config).
USER 1001

# ENTRYPOINT erven we van claude-runner — geen override nodig.
