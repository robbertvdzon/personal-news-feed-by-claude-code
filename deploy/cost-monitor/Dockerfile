# syntax=docker/dockerfile:1.7
#
# Cost-monitor — one-shot script dat door een CronJob elke 5 min gedraaid
# wordt. Scant alle actieve story_runs, vergelijkt cumulatieve tokens met
# AI Token Budget en post markers / pauzeert bij overschrijding.
#
# Geen kubectl nodig — alleen DB + JIRA API.

FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt

COPY cost-monitor.py /app/

RUN useradd -u 1001 -g 0 -m -d /home/monitor monitor \
    && chown -R 1001:0 /app

USER 1001

ENTRYPOINT ["python3", "/app/cost-monitor.py"]
