FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache \
    bash \
    ca-certificates \
    curl \
    git
