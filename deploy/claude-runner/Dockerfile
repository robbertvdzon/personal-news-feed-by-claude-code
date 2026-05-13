# syntax=docker/dockerfile:1.7
#
# Claude-runner image: een autonome Claude Code-sessie die één story
# verwerkt. Image bevat:
#   - JDK 21 + Maven       (zodat Claude `mvn compile` kan draaien voor
#                            backend-changes)
#   - Node 22 + Claude Code CLI (de eigenlijke Claude-engine)
#   - Git + GitHub CLI (gh) (voor branch + PR-create)
#
# Geen Flutter — frontend-changes vereisen Flutter-SDK; later toevoegen.
#
# Runt als UID 1001 + GID 0 (OpenShift-vriendelijk).

FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

# OS deps — basis tooling
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg git jq maven \
    && rm -rf /var/lib/apt/lists/*

# Node 22 LTS (voor Claude Code)
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# GitHub CLI
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
       > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

# Werkmap + permissies voor random-UID-met-GID-0 (OpenShift)
RUN mkdir -p /work /home/runner /opt/m2 \
    && chgrp -R 0 /work /home/runner /opt/m2 \
    && chmod -R g=u /work /home/runner /opt/m2

# Runner-script
COPY runner.sh /usr/local/bin/runner.sh
RUN chmod +x /usr/local/bin/runner.sh

ENV HOME=/home/runner \
    MAVEN_CONFIG=/opt/m2 \
    NPM_CONFIG_CACHE=/home/runner/.npm \
    NODE_OPTIONS="--max-old-space-size=2048"

WORKDIR /work
USER 1001

ENTRYPOINT ["/usr/local/bin/runner.sh"]
