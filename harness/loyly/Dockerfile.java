FROM pi-harness/java:21-web

RUN apk add --no-cache \
    bash \
    ca-certificates \
    curl \
    git
