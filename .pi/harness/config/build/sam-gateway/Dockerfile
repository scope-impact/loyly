FROM node:22-alpine
WORKDIR /gateway
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY gateway.mjs sam-template.mjs healthcheck.mjs ./
EXPOSE 8080
CMD ["node", "/gateway/gateway.mjs"]
