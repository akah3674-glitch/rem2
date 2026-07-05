/**
 * WebSocket terminal handler — mỗi connection = 1 PTY shell riêng.
 * Gắn vào http.Server sau khi Express đã được tạo.
 */
import { WebSocketServer, WebSocket } from "ws";
import type { Server as HttpServer } from "http";
import * as pty from "node-pty";
import { logger } from "./lib/logger";

export function attachTerminalWs(server: HttpServer, basePath: string = "") {
  const wsPath = `${basePath}/terminal/ws`;
  const wss = new WebSocketServer({ server, path: wsPath });
  logger.info({ wsPath }, "Terminal WebSocket server attached");

  wss.on("connection", (ws: WebSocket, req) => {
    const ip = req.socket.remoteAddress ?? "unknown";
    logger.info({ ip }, "Terminal: new connection");

    let shell: ReturnType<typeof pty.spawn>;
    try {
      shell = pty.spawn("/bin/bash", [], {
        name: "xterm-256color",
        cols: 80,
        rows: 24,
        cwd: process.env.HOME ?? "/tmp",
        env: {
          ...process.env,
          TERM: "xterm-256color",
          COLORTERM: "truecolor",
          // Gợi ý shell dùng tiếng Việt / UTF-8
          LANG: "en_US.UTF-8",
          LC_ALL: "en_US.UTF-8",
        } as Record<string, string>,
      });
    } catch (err) {
      logger.error({ err }, "Terminal: pty.spawn failed");
      ws.send("\r\n\x1b[31mLỗi khởi động shell. Kiểm tra node-pty đã build chưa.\x1b[0m\r\n");
      ws.close(1011, "pty spawn failed");
      return;
    }

    // Stdout pty → ws
    shell.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(data);
    });

    shell.onExit(({ exitCode }) => {
      logger.info({ exitCode }, "Terminal: shell exited");
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(`\r\n\x1b[33m[Shell thoát với code ${exitCode}. Đóng tab hoặc tải lại để mở shell mới.]\x1b[0m\r\n`);
        ws.close(1000, "shell exited");
      }
    });

    // ws → stdin pty
    ws.on("message", (raw) => {
      try {
        const msg = JSON.parse(raw.toString());
        if (msg.type === "data") {
          shell.write(msg.data);
        } else if (msg.type === "resize") {
          const cols = Math.max(1, Math.min(999, Number(msg.cols) || 80));
          const rows = Math.max(1, Math.min(999, Number(msg.rows) || 24));
          shell.resize(cols, rows);
        }
      } catch {
        // ignore malformed frame
      }
    });

    ws.on("close", () => {
      logger.info({ ip }, "Terminal: ws closed — killing shell");
      try { shell.kill(); } catch { /* already dead */ }
    });

    ws.on("error", (err) => {
      logger.warn({ err, ip }, "Terminal: ws error");
      try { shell.kill(); } catch { /* ignore */ }
    });
  });
}
