import { createServer } from "http";
import app from "./app";
import { attachTerminalWs } from "./terminal-ws";
import { logger } from "./lib/logger";

const rawPort = process.env["PORT"];

if (!rawPort) {
  throw new Error(
    "PORT environment variable is required but was not provided.",
  );
}

const port = Number(rawPort);

if (Number.isNaN(port) || port <= 0) {
  throw new Error(`Invalid PORT value: "${rawPort}"`);
}

// Wrap Express trong http.Server để gắn WebSocket terminal
const server = createServer(app);

// Attach PTY WebSocket (path = /terminal/ws)
const basePath = (process.env["BASE_PATH"] ?? "").replace(/\/$/, "");
attachTerminalWs(server, basePath);

server.listen(port, () => {
  logger.info({ port, basePath }, "Server listening");
});

server.on("error", (err) => {
  logger.error({ err }, "Server error");
  process.exit(1);
});
