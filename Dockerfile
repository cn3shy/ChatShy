# syntax=docker/dockerfile:1

# ---- 前端构建 ----
FROM node:24-slim AS web-build
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- 服务端构建 ----
FROM node:24-slim AS server-build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY tsconfig.json ./
COPY src/ ./src/
RUN npm run build

# ---- 运行镜像 ----
FROM node:24-slim
ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
# --ignore-scripts：依赖自带预编译二进制（better-sqlite3 为 prebuildify），无需构建工具链
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY --from=server-build /build/dist ./dist
COPY --from=web-build /build/web/dist ./web/dist
EXPOSE 8088
CMD ["node", "dist/index.js"]
