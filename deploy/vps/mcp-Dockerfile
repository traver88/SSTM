FROM node:24-alpine@sha256:116b800c3754b440ee0aa3ed2f20f5bf3d99a554bfd9adeea09ea8017f6e85f5

WORKDIR /app
COPY package.json package-lock.json ./
COPY kaoyan-mcp-core-0.1.0.tgz ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY dist ./dist
ENV HOST=0.0.0.0
ENV PORT=8787
CMD ["node", "dist/index.js"]
