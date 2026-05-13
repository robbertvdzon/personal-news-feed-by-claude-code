# syntax=docker/dockerfile:1.7
#
# Preview-namespace labeller: watcht Applications in de argocd-namespace
# en zorgt dat preview-namespaces (pnf-pr-*) bestaan met de
# `argocd.argoproj.io/managed-by=argocd` label.

FROM bitnami/kubectl:latest

USER 0
RUN install -d -m 0775 -o 1001 -g 0 /scripts

COPY labeller.sh /scripts/labeller.sh
RUN chmod +x /scripts/labeller.sh

USER 1001
ENTRYPOINT ["/scripts/labeller.sh"]
