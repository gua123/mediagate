// 对照实验：本地 HTTP 服务，单连接限速 RATE_MBPS，验证 HTTP 协议本身能否线性叠加。
// 用法：node control_stack.js <rateMBps> <port>
// 逻辑：每个响应最多 256KiB 窗口/20ms 节流（≈ 12.8 MB/s 上限/连接，可通过参数改），
//      同时返回 Range，避免客户端侧成为瓶颈。
const http = require('http');
const rateMBps = Number(process.argv[2] || 10);
const port = Number(process.argv[3] || 18080);
const TOTAL = 512 * 1024 * 1024; // 虚拟 512MiB 文件
const CHUNK = 64 * 1024;

const server = http.createServer((req, res) => {
  const m = /bytes=(\d+)-(\d+)/.exec(req.headers.range || '');
  let start = 0, end = TOTAL - 1;
  if (m) { start = Number(m[1]); end = Number(m[2]); }
  if (end >= TOTAL) end = TOTAL - 1;
  let remaining = end - start + 1;
  const headers = {
    'Content-Length': remaining,
    'Content-Type': 'application/octet-stream',
    'Accept-Ranges': 'bytes',
  };
  if (m) headers['Content-Range'] = `bytes ${start}-${end}/${TOTAL}`;
  res.writeHead(m ? 206 : 200, headers);
  const buf = Buffer.alloc(CHUNK, 0x41);
  let stopped = false;
  const pump = () => {
    if (stopped) return;
    if (remaining <= 0) { res.end(); return; }
    const n = Math.min(CHUNK, remaining);
    remaining -= n;
    const ok = res.write(buf.subarray(0, n));
    // 每写一块，等“应耗时间”再继续：CHUNK / (rateMBps MB/s)
    const wait = (n / (rateMBps * 1024 * 1024)) * 1000;
    if (ok) setTimeout(pump, wait);
    else res.once('drain', () => setTimeout(pump, wait));
  };
  req.on('aborted', () => { stopped = true; });
  pump();
});
server.listen(port, '127.0.0.1', () => console.log('listening ' + port + ' rate=' + rateMBps + 'MB/s per connection'));
