# syntax=docker/dockerfile:1.7

# ─── Build stage ─────────────────────────────────────────────────────
# Compile + package via Maven. We pinnen JDK 21 omdat de pom java.version=21
# verwacht. `--mount=type=cache` houdt de ~/.m2 cache tussen builds aan.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Eerst alleen pom.xml zodat dependency-download ge-cached wordt tussen
# builds die alleen broncode wijzigen.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline

# Daarna de rest. -DskipTests omdat we tests in CI apart draaien
# (en je devcontainer-tests vereisen dingen die in een Docker-build niet
# beschikbaar zijn).
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
 && cp target/newsfeedbackend-*.jar /app.jar

# ─── Runtime stage ───────────────────────────────────────────────────
# Alleen JRE (geen JDK/Maven), kleiner image. Eclipse Temurin is de
# default keuze; "jre-alpine" zou nog kleiner zijn maar alpine + Java
# kan met sommige libs ongelukken geven.
FROM eclipse-temurin:21-jre AS runtime

# ffmpeg is nodig voor AudioTranscoder (KAN-60-followup): comprimeert
# podcast-MP3's die > 25 MiB zijn naar mono 32 kbps zodat Whisper ze
# wel accepteert. Eclipse Temurin JRE image is Ubuntu-based, dus apt.
#
# KAN-67: yt-dlp is de fallback voor de Nederlandse video-samenvatting —
# als YouTube geen ondertiteling teruggeeft, downloadt yt-dlp de audio
# als MP3 zodat WhisperClient 'm kan transcriberen. yt-dlp wordt vaak
# geüpdate (YouTube wijzigt z'n player); we installeren via pip i.p.v.
# apt om sneller op een nieuwere versie te kunnen ouderwetsen.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 python3-pip \
 && pip3 install --no-cache-dir --break-system-packages "yt-dlp>=2024.0.0" \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# OpenShift draait pods met een arbitrary UID die in de root-group
# zit. Files moeten group-writable zijn (gid=0). De /data-dir wordt
# door OpenShift gemount via een PVC; we leggen 'm hier al aan voor
# lokale runs.
RUN mkdir -p /data && chgrp -R 0 /data && chmod -R g=u /data

WORKDIR /opt/app
COPY --from=build /app.jar /opt/app/app.jar

# Niet als root draaien (UID 1001 is conventie; in OpenShift override't
# de SCC dit toch met een random UID, dat is OK want gid=0).
USER 1001

EXPOSE 8080

# Build-metadata: CI geeft deze via --build-arg mee. VersionController
# leest ze als env-var en exposeert ze via GET /api/version, zodat de
# frontend kan detecteren dat er een nieuwe versie live staat.
ARG BUILD_SHA=unknown
ARG BUILD_TIME=unknown

# Spring's BUILD_RUN-tijd: actuator-health-probe gebruikt deze poort.
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE= \
    APP_DATA_DIR=/data \
    BUILD_SHA=${BUILD_SHA} \
    BUILD_TIME=${BUILD_TIME}

ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]
