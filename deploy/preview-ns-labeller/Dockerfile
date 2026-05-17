# syntax=docker/dockerfile:1.7
#
# Preview-namespace labeller. Watcht Applications in de argocd-namespace
# en zorgt voor twee dingen per preview-namespace pnf-pr-N:
#
#   1. De namespace bestaat met label `argocd.argoproj.io/managed-by=argocd`
#      (oude verantwoordelijkheid).
#   2. Een eigen Neon database-branch met naam `pr-N`, en de PNF_DATABASE_URL
#      in de gereflecteerde Secret van die namespace wijst naar die branch
#      (KAN-55 — isolatie van DB-schema/data per PR).
#
# Naast kubectl hebben we curl + jq nodig voor de Neon REST API.

FROM bitnami/kubectl:latest

USER 0
# bitnami's install_packages helper. curl + jq voor Neon API + JSON-parsing.
RUN install_packages curl jq ca-certificates \
    && install -d -m 0775 -o 1001 -g 0 /scripts

COPY labeller.sh /scripts/labeller.sh
RUN chmod +x /scripts/labeller.sh

USER 1001
ENTRYPOINT ["/scripts/labeller.sh"]
