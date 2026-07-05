import { Router } from "express";

const router = Router();

// Serve xterm.js terminal HTML page
router.get("/", (_req, res) => {
  res.setHeader("Content-Type", "text/html; charset=utf-8");
  res.send(TERMINAL_HTML);
});

const TERMINAL_HTML = `<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>Cloud Terminal</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.min.css" />
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body { width: 100%; height: 100%; background: #0d1117; overflow: hidden; }

    #topbar {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      background: #161b22;
      border-bottom: 1px solid #30363d;
      font-family: monospace;
      font-size: 13px;
      color: #8b949e;
      user-select: none;
    }
    #topbar .dot { width: 12px; height: 12px; border-radius: 50%; }
    #topbar .red    { background: #ff5f57; }
    #topbar .yellow { background: #febc2e; }
    #topbar .green  { background: #28c840; }
    #topbar .title  { flex: 1; text-align: center; }
    #topbar .conn   { font-size: 11px; }
    #topbar .conn.ok   { color: #3fb950; }
    #topbar .conn.err  { color: #f85149; }
    #topbar .conn.wait { color: #d29922; }

    #toolbar {
      display: flex;
      gap: 4px;
      padding: 4px 6px;
      background: #0d1117;
      border-bottom: 1px solid #21262d;
      overflow-x: auto;
      white-space: nowrap;
    }
    .tb-btn {
      padding: 4px 10px;
      border-radius: 4px;
      border: 1px solid #30363d;
      background: #21262d;
      color: #c9d1d9;
      font-size: 12px;
      font-family: monospace;
      cursor: pointer;
      flex-shrink: 0;
    }
    .tb-btn:active { background: #388bfd33; }

    #terminal-wrap {
      width: 100%;
      /* height = 100vh minus topbar(~33px) minus toolbar(~34px) */
      height: calc(100vh - 33px - 34px);
    }
    #terminal { width: 100%; height: 100%; padding: 4px; }
  </style>
</head>
<body>

<div id="topbar">
  <span class="dot red"></span>
  <span class="dot yellow"></span>
  <span class="dot green"></span>
  <span class="title">☁ Cloud Terminal</span>
  <span class="conn wait" id="connStatus">⟳ Đang kết nối...</span>
</div>

<div id="toolbar">
  <button class="tb-btn" onclick="sendText('python3\\n')">🐍 python3</button>
  <button class="tb-btn" onclick="sendText('ls -la\\n')">📁 ls</button>
  <button class="tb-btn" onclick="sendText('pwd\\n')">📍 pwd</button>
  <button class="tb-btn" onclick="sendText('htop\\n')">📊 htop</button>
  <button class="tb-btn" onclick="sendText('q\\n')">❌ q/exit</button>
  <button class="tb-btn" onclick="sendKey('\\x03')">⛔ Ctrl+C</button>
  <button class="tb-btn" onclick="sendKey('\\x1b')">⎋ Esc</button>
  <button class="tb-btn" onclick="sendText('clear\\n')">🗑 clear</button>
  <button class="tb-btn" onclick="sendText('pip list\\n')">📦 pip list</button>
  <button class="tb-btn" onclick="sendText('node --version\\n')">🟩 node</button>
</div>

<div id="terminal-wrap">
  <div id="terminal"></div>
</div>

<script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/xterm-addon-web-links@0.9.0/lib/xterm-addon-web-links.min.js"></script>
<script>
const term = new Terminal({
  theme: {
    background: '#0d1117', foreground: '#c9d1d9',
    cursor: '#58a6ff', cursorAccent: '#0d1117',
    black: '#484f58', red: '#ff7b72', green: '#3fb950',
    yellow: '#d29922', blue: '#58a6ff', magenta: '#bc8cff',
    cyan: '#39c5cf', white: '#b1bac4',
    brightBlack: '#6e7681', brightRed: '#ffa198', brightGreen: '#56d364',
    brightYellow: '#e3b341', brightBlue: '#79c0ff', brightMagenta: '#d2a8ff',
    brightCyan: '#56d4dd', brightWhite: '#f0f6fc',
    selectionBackground: '#388bfd4d',
  },
  cursorBlink: true,
  fontSize: 13,
  fontFamily: "'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Courier New', monospace",
  scrollback: 5000,
  allowProposedApi: true,
});
const fitAddon = new FitAddon.FitAddon();
const linksAddon = new WebLinksAddon.WebLinksAddon();
term.loadAddon(fitAddon);
term.loadAddon(linksAddon);
term.open(document.getElementById('terminal'));
fitAddon.fit();

const connStatus = document.getElementById('connStatus');
let ws, reconnectTimer, reconnectCount = 0;

function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const base = location.pathname.replace(/\\/terminal\\/?.*/, '');
  ws = new WebSocket(proto + '//' + location.host + base + '/terminal/ws');

  ws.onopen = () => {
    reconnectCount = 0;
    connStatus.textContent = '● Đã kết nối';
    connStatus.className = 'conn ok';
    ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
    term.write('\\x1b[32m✓ Kết nối thành công!\\x1b[0m\\r\\n');
  };

  ws.onmessage = (e) => term.write(e.data);

  ws.onerror = () => {
    connStatus.textContent = '✗ Lỗi kết nối';
    connStatus.className = 'conn err';
  };

  ws.onclose = (e) => {
    connStatus.textContent = '○ Mất kết nối';
    connStatus.className = 'conn err';
    if (e.code !== 1000) {
      const delay = Math.min(2000 * Math.pow(1.5, reconnectCount++), 15000);
      term.write('\\r\\n\\x1b[33m⟳ Tự kết nối lại sau ' + Math.round(delay/1000) + 's...\\x1b[0m\\r\\n');
      connStatus.textContent = '⟳ Đang kết nối lại...';
      connStatus.className = 'conn wait';
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(connect, delay);
    }
  };
}

term.onData(data => {
  if (ws && ws.readyState === WebSocket.OPEN)
    ws.send(JSON.stringify({ type: 'data', data }));
});

function sendText(t) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'data', data: t }));
  term.focus();
}
function sendKey(k) { sendText(k); }

window.addEventListener('resize', () => {
  fitAddon.fit();
  if (ws && ws.readyState === WebSocket.OPEN)
    ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
});

connect();
term.focus();
</script>
</body>
</html>`;

export default router;
