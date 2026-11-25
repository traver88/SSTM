import fs from "node:fs";
import http from "node:http";
import https from "node:https";

const listenPort = integerEnvironment("EDGE_PORT", 8443);
const certificatePath = process.env.TLS_CERT_FILE ?? "/opt/kaoyan-ai-planner/certs/fullchain.pem";
const privateKeyPath = process.env.TLS_KEY_FILE ?? "/opt/kaoyan-ai-planner/certs/privkey.pem";

const targets = new Map([
  ["supabase.iizjj.asia", { host: "127.0.0.1", port: 18000 }],
  ["mcp.iizjj.asia", { host: "127.0.0.1", port: 8787 }],
]);

const server = https.createServer({
  cert: fs.readFileSync(certificatePath),
  key: fs.readFileSync(privateKeyPath),
  minVersion: "TLSv1.2",
}, proxyRequest);

server.on("request", (request) => {
  console.info(`HTTPS proxy ${request.method} ${request.headers.host ?? "unknown"} ${request.url ?? "/"}`);
});
server.on("tlsClientError", (error) => {
  console.error(`TLS handshake failed: ${error.code ?? error.message}`);
});
server.on("upgrade", proxyUpgrade);
server.requestTimeout = 300_000;
server.headersTimeout = 60_000;
server.keepAliveTimeout = 5_000;

server.listen(listenPort, "0.0.0.0", () => {
  process.stdout.write(`HTTPS reverse proxy listening on 0.0.0.0:${listenPort}\n`);
});

function proxyRequest(request, response) {
  const target = targetForRequest(request);
  if (!target) {
    response.writeHead(421, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Unknown host\n");
    return;
  }

  const headers = forwardedHeaders(request);
  const upstreamRequest = http.request({
    hostname: target.host,
    port: target.port,
    method: request.method,
    path: request.url,
    headers,
  }, (upstreamResponse) => {
    response.writeHead(upstreamResponse.statusCode ?? 502, stripHopByHopHeaders(upstreamResponse.headers));
    upstreamResponse.pipe(response);
  });

  upstreamRequest.on("error", () => {
    if (response.headersSent) {
      response.destroy();
      return;
    }
    response.writeHead(502, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Upstream service unavailable\n");
  });
  request.pipe(upstreamRequest);
}

function proxyUpgrade(request, clientSocket, head) {
  const target = targetForRequest(request);
  if (!target) {
    clientSocket.end("HTTP/1.1 421 Misdirected Request\r\nConnection: close\r\n\r\n");
    return;
  }

  const headers = forwardedHeaders(request);
  headers.connection = "Upgrade";
  headers.upgrade = request.headers.upgrade ?? "websocket";
  const upstreamRequest = http.request({
    hostname: target.host,
    port: target.port,
    method: request.method,
    path: request.url,
    headers,
  });
  upstreamRequest.on("upgrade", (_response, upstreamSocket, upstreamHead) => {
    clientSocket.write("HTTP/1.1 101 Switching Protocols\r\n");
    for (const [name, value] of Object.entries(_response.headers)) {
      if (Array.isArray(value)) {
        for (const item of value) clientSocket.write(`${name}: ${item}\r\n`);
      } else if (value !== undefined) {
        clientSocket.write(`${name}: ${value}\r\n`);
      }
    }
    clientSocket.write("\r\n");
    if (head.length > 0) upstreamSocket.write(head);
    if (upstreamHead.length > 0) clientSocket.write(upstreamHead);
    upstreamSocket.pipe(clientSocket);
    clientSocket.pipe(upstreamSocket);
    clientSocket.on("error", () => upstreamSocket.destroy());
    upstreamSocket.on("error", () => clientSocket.destroy());
  });
  upstreamRequest.on("response", (upstreamResponse) => {
    clientSocket.end(`HTTP/1.1 ${upstreamResponse.statusCode ?? 502} ${upstreamResponse.statusMessage ?? "Bad Gateway"}\r\nConnection: close\r\n\r\n`);
  });
  upstreamRequest.on("error", () => {
    clientSocket.end("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");
  });
  upstreamRequest.end();
}

function targetForRequest(request) {
  const host = request.headers.host?.split(":", 1)[0]?.toLowerCase();
  return host ? targets.get(host) : undefined;
}

function forwardedHeaders(request) {
  const headers = stripHopByHopHeaders(request.headers);
  headers["x-forwarded-proto"] = "https";
  headers["x-forwarded-host"] = request.headers.host ?? "";
  const remoteAddress = request.socket.remoteAddress ?? "unknown";
  headers["x-forwarded-for"] = headers["x-forwarded-for"]
    ? `${headers["x-forwarded-for"]}, ${remoteAddress}`
    : remoteAddress;
  return headers;
}

function stripHopByHopHeaders(headers) {
  const forwarded = { ...headers };
  for (const name of ["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"]) {
    delete forwarded[name];
  }
  return forwarded;
}

function integerEnvironment(name, fallback) {
  const value = process.env[name];
  if (value === undefined) return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65_535) throw new Error(`${name} is invalid`);
  return parsed;
}
