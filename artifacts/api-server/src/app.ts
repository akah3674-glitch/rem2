import express, { type Express } from "express";
import cors from "cors";
import pinoHttp from "pino-http";
import router from "./routes";
import terminalRouter from "./routes/terminal";
import { logger } from "./lib/logger";

const app: Express = express();

app.use(
  pinoHttp({
    logger,
    serializers: {
      req(req) {
        return {
          id: req.id,
          method: req.method,
          url: req.url?.split("?")[0],
        };
      },
      res(res) {
        return {
          statusCode: res.statusCode,
        };
      },
    },
  }),
);
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use("/api", router);

// Web terminal UI — phục vụ trang xterm.js
const basePath = (process.env["BASE_PATH"] ?? "").replace(/\/$/, "");
app.use(`${basePath}/terminal`, terminalRouter);
// Alias không có basePath (cho local dev)
if (basePath) app.use("/terminal", terminalRouter);

export default app;
