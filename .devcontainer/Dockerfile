# syntax=docker/dockerfile:1.7
#
# Devcontainer voor de Personal News Feed monorepo.
#
# Bevat alles wat in de container nodig is:
# - JDK 21 + Maven (backend: Spring Boot 4 / Kotlin 2.2)
# - Flutter SDK 3.35 + Dart 3.9 (frontend)
# - Android SDK + cmdline-tools + build-tools (voor APK-builds)
# - Node.js 20 (voor Claude Code zelf)
# - Claude Code CLI globaal geïnstalleerd
# - git, gh, curl, jq — handige tooling die Claude regelmatig nodig heeft
#
# Image is bewust "fat": je betaalt de download éénmalig en daarna heb
# je een stabiele reproduceerbare omgeving zonder gefriemel met versies.

FROM mcr.microsoft.com/devcontainers/base:ubuntu-22.04

ARG TARGETARCH

# Versies expliciet vastpinnen zodat een rebuild over een half jaar
# nog dezelfde toolchain levert.
ARG FLUTTER_VERSION=3.35.0
ARG ANDROID_CMDLINE_TOOLS=11076708        # latest stable per 2024-Q4
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0
ARG NODE_MAJOR=20

ENV DEBIAN_FRONTEND=noninteractive

# --- OS-pakketten + JDK 17/21 + Maven + Node + tools ---
# We installeren JDK 21 (default voor Spring Boot 4 / Kotlin / Gradle-AGP)
# én JDK 17 (alleen voor Android sdkmanager — die crasht op JDK 21 omdat
# hij interne JDK-API's gebruikt die in 21 zijn verwijderd).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jdk \
        openjdk-17-jdk-headless \
        maven \
        git \
        curl \
        wget \
        unzip \
        zip \
        jq \
        ca-certificates \
        gnupg \
        lsb-release \
        sudo \
        xz-utils \
        gh \
        ripgrep \
        fd-find \
        zsh \
        # Flutter heeft deze nodig voor Linux-builds en analyzer:
        clang cmake ninja-build pkg-config libgtk-3-dev liblzma-dev \
    # Node.js 20.x (NodeSource) — voor npm i -g @anthropic-ai/claude-code
    && curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# JAVA_HOME architectuur-onafhankelijk: maak een arch-onafhankelijk
# symlink naar de juiste JDK-installatie zodat we niet hoeven te weten
# of we op amd64 of arm64 draaien.
ENV JAVA_HOME=/opt/jdk-21
ENV JDK_17_HOME=/opt/jdk-17
RUN ln -sfn $(find /usr/lib/jvm -maxdepth 1 -name 'java-21-openjdk-*' | head -1) /opt/jdk-21 \
    && ln -sfn $(find /usr/lib/jvm -maxdepth 1 -name 'java-17-openjdk-*' | head -1) /opt/jdk-17
ENV PATH=${JAVA_HOME}/bin:${PATH}

# --- Claude Code CLI ---
RUN npm install -g @anthropic-ai/claude-code

# --- Flutter SDK ---
# Op een gedeelde locatie zodat de "vscode" user (uit de base-image) er
# bij kan; SDK-download cachen we via een named volume in devcontainer.json
# zodat een rebuild niet opnieuw downloadt.
ENV FLUTTER_HOME=/opt/flutter
RUN mkdir -p ${FLUTTER_HOME} \
    && curl -L -o /tmp/flutter.tar.xz \
        https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_${FLUTTER_VERSION}-stable.tar.xz \
    && tar -xf /tmp/flutter.tar.xz -C /opt \
    && rm /tmp/flutter.tar.xz \
    && chown -R vscode:vscode ${FLUTTER_HOME}
ENV PATH=${FLUTTER_HOME}/bin:${PATH}

# --- Android SDK ---
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -L -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-extract \
    && mv /tmp/cmdline-extract/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-extract \
    && chown -R vscode:vscode ${ANDROID_HOME}
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# Switch naar non-root user (komt uit de devcontainers base-image)
USER vscode

# Accept licenses + install minimaal benodigde Android-componenten.
# sdkmanager moet expliciet met JDK 17 draaien (zie comment hierboven).
# Gebruik `env` aan de rechterkant van de pipe — anders zou JAVA_HOME
# alleen voor `yes` gelden i.p.v. voor sdkmanager.
RUN yes | env JAVA_HOME=${JDK_17_HOME} sdkmanager --licenses \
    && env JAVA_HOME=${JDK_17_HOME} sdkmanager \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}"

# Pre-cache pub-packages-index zodat eerste `flutter pub get` sneller is.
# `flutter --version` triggert download van Dart SDK + initiele setup.
# `flutter doctor --android-licenses` roept ook sdkmanager intern aan,
# dus ook hier JDK 17 forceren via `env`.
RUN flutter --version \
    && flutter config --no-analytics --android-sdk ${ANDROID_HOME} \
    && flutter precache --android --linux \
    && yes | env JAVA_HOME=${JDK_17_HOME} flutter doctor --android-licenses

# Default werkmap — devcontainer.json mount de repo hier.
WORKDIR /workspaces/personal-news-feed-by-claude-code
