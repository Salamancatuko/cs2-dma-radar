#!/usr/bin/env bash
# CS2 DMA Radar Client - start (Linux)
# Requires: JDK 17+, a built jar (./mvnw package) and vmm/*.so libraries.
cd "$(dirname "$0")"
exec java -jar target/cs2-dma-client.jar
