import { check } from 'k6';

// Dựng frame STOMP 1.2: dòng lệnh, các header "k:v", dòng trống, body, kết thúc bằng NUL
export function frame(command, headers, body) {
  const lines = Object.entries(headers || {}).map(([k, v]) => `${k}:${v}`);
  return `${command}\n${lines.join('\n')}\n\n${body || ''}\0`;
}

// Tách một frame (có hoặc không có NUL cuối) thành { command, headers, body }; heartbeat chỉ có EOL thì command rỗng (ponytail: chưa giải mã escape header STOMP 1.2, thêm khi cần header có ký tự đặc biệt)
export function parse(text) {
  const raw = text.replace(/\0$/, '').replace(/^(\r?\n)+/, '');
  if (raw === '') return { command: '', headers: {}, body: '' };
  const sep = /\r?\n\r?\n/.exec(raw);
  const head = (sep ? raw.slice(0, sep.index) : raw).split(/\r?\n/);
  const headers = {};
  for (const line of head.slice(1)) {
    const i = line.indexOf(':');
    if (i > 0 && !(line.slice(0, i) in headers)) headers[line.slice(0, i)] = line.slice(i + 1);
  }
  return { command: head[0], headers, body: sep ? raw.slice(sep.index + sep[0].length) : '' };
}

// Cắt bộ đệm nhận được theo NUL: trả các frame đủ (bỏ heartbeat) và phần dư chưa đủ frame để nối với message WebSocket sau
export function split(buffer) {
  const parts = buffer.split('\0');
  const rest = parts.pop();
  const frames = parts.map(parse).filter((f) => f.command !== '');
  return { frames, rest: rest.replace(/^(\r?\n)+/, '') };
}

// Gửi CONNECT với STOMP 1.2, heartbeat 10 s hai chiều và Bearer token
export function connect(ws, token) {
  ws.send(frame('CONNECT', {
    'accept-version': '1.2',
    host: 'nexus',
    'heart-beat': '10000,10000',
    Authorization: `Bearer ${token}`,
  }));
}

// Gửi SUBSCRIBE tới destination với id cho trước, ack tự động
export function subscribe(ws, id, destination) {
  ws.send(frame('SUBSCRIBE', { id, destination, ack: 'auto' }));
}

// Tự kiểm frame/parse/split bằng check của k6, gọi trong setup() của script dùng thư viện này
export function selfCheck() {
  const f = parse(frame('SEND', { a: 'b' }, '{"x":1}'));
  const crlf = parse('MESSAGE\r\na:b\r\n\r\nx:y\0');
  const s = split(`\n${frame('MESSAGE', { a: '1' }, 'x')}\n${frame('MESSAGE', { a: '2' }, 'y')}MESS`);
  check(null, {
    'stomp: parse(frame) giữ command, header, body': () => f.command === 'SEND' && f.headers.a === 'b' && f.body === '{"x":1}',
    'stomp: parse frame CRLF tách đúng header và body': () => crlf.headers.a === 'b' && !('x' in crlf.headers) && crlf.body === 'x:y',
    'stomp: heartbeat cho command rỗng': () => parse('\n').command === '',
    'stomp: split tách 2 frame và giữ phần dư': () => s.frames.length === 2 && s.frames[1].body === 'y' && s.rest === 'MESS',
  });
}
