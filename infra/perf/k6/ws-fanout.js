import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { setInterval, clearInterval, setTimeout } from 'k6/timers';
import { WebSocket } from 'k6/websockets';
import { state, authHeaders } from './lib/state.js';
import { connect, subscribe, split, selfCheck } from './lib/stomp.js';

const LISTENERS = 50;
const SENDERS = 5;
const SEND_MS = 120000;
const HOLD_MS = 5000 + SEND_MS + 5000;
const LISTENER_OFFSET = Number(__ENV.LISTENER_OFFSET || 0);
const DEST = __ENV.DEST || `/topic/channels/${state.channelId}`;
const MESSAGES_URL = `${__ENV.BASE_URL}/api/v1/channels/${state.channelId}/messages`;

const e2eLatency = new Trend('e2e_latency', true);
const framesReceived = new Counter('frames_received');
const messagesSent = new Counter('messages_sent');
const delivery = new Rate('delivery');
const listenersVerified = new Counter('listeners_verified');

export const options = {
  scenarios: {
    listeners: { executor: 'per-vu-iterations', vus: LISTENERS, iterations: 1, maxDuration: '3m', exec: 'listener' },
    senders: { executor: 'per-vu-iterations', vus: SENDERS, iterations: 1, maxDuration: '3m', startTime: '5s', exec: 'sender' },
  },
  thresholds: {
    e2e_latency: ['p(95)<1000'],
    checks: ['rate>0.99'],
    frames_received: ['count>0'],
    delivery: ['rate>=0.99'],
    listeners_verified: [`count>=${LISTENERS}`],
  },
};

// Tự kiểm lib/stomp.js và lấy id tin mới nhất của kênh làm mốc: tin sau mốc này là tin của lần chạy
export function setup() {
  selfCheck();
  verifySelfCheck();
  const res = http.get(`${MESSAGES_URL}?limit=1`, { headers: authHeaders(0) });
  if (res.status !== 200 || res.json().length !== 1) throw new Error(`không lấy được tin mốc: ${res.status}`);
  return { startId: res.json()[0].id };
}

// Đọc mọi tin sau mốc (tăng dần, trang 100) có nội dung "fanout "; trả null nếu bất kỳ trang lịch sử nào không phải 200 để bước đối chiếu không dùng dữ liệu thiếu
function runMessages(startId, userIndex, get = http.get) {
  const out = [];
  let after = startId;
  for (;;) {
    const res = get(`${MESSAGES_URL}?after=${after}&limit=100`, { headers: authHeaders(userIndex), tags: { name: 'verify' } });
    if (res.status !== 200) {
      console.error(`listener ${userIndex}: đối chiếu lịch sử lỗi ${res.status}, không tính listener này là đã đối chiếu`);
      return null;
    }
    const page = res.json();
    out.push(...page.filter((m) => m.content.startsWith('fanout ')));
    if (page.length < 100) return out;
    after = page[page.length - 1].id;
  }
}

// Tự kiểm runMessages bằng API giả: lỗi ở trang đầu hoặc trang sau đều phải trả null, đủ trang thì trả đúng tin "fanout "; sai thì dừng bài đo
function verifySelfCheck() {
  const full = Array.from({ length: 100 }, (_, i) => ({ id: `m${i}`, content: `fanout 0-${i}` }));
  const last = [{ id: 'x', content: 'fanout 0-100' }, { id: 'y', content: 'khác' }];
  const pages = (list) => { let i = 0; return () => list[i++]; };
  const ok = (body) => ({ status: 200, json: () => body });
  const firstFails = runMessages('s', 0, pages([{ status: 503 }]));
  const laterFails = runMessages('s', 0, pages([ok(full), { status: 503 }]));
  const complete = runMessages('s', 0, pages([ok(full), ok(last)]));
  if (firstFails !== null || laterFails !== null || !complete || complete.length !== 101) throw new Error('runMessages tự kiểm sai');
}

// Listener: user riêng mở STOMP, subscribe kênh, đo độ trễ mỗi MessageCreated "fanout ", cuối bài đối chiếu với lịch sử để tính tỉ lệ giao và chỉ đếm listener khi đọc đủ mọi trang lịch sử (LISTENER_OFFSET chỉ để thử listener chết)
export function listener(data) {
  const userIndex = exec.scenario.iterationInTest + LISTENER_OFFSET;
  const got = new Set();
  let buffer = '';
  let connected = false;
  let stompError = false;
  let closedEarly = false;
  let heartbeat = null;
  const ws = new WebSocket(__ENV.WS_URL);
  ws.onopen = () => connect(ws, state.users[userIndex].accessToken);
  ws.onmessage = (event) => {
    const now = Date.now();
    const parsed = split(buffer + event.data);
    buffer = parsed.rest;
    for (const f of parsed.frames) {
      if (f.command === 'CONNECTED') {
        connected = true;
        subscribe(ws, '0', DEST);
        heartbeat = setInterval(() => { if (ws.readyState === 1) ws.send('\n'); }, 10000);
      } else if (f.command === 'ERROR') {
        stompError = true;
        console.error(`listener ${userIndex}: STOMP ERROR ${f.headers.message}`);
      } else if (f.command === 'MESSAGE') {
        const frame = JSON.parse(f.body);
        if (frame.type === 'MessageCreated' && String(frame.data.content).startsWith('fanout ')) {
          e2eLatency.add(now - Date.parse(frame.data.createdAt));
          framesReceived.add(1);
          got.add(frame.data.messageId);
        }
      }
    }
  };
  ws.onclose = () => { closedEarly = true; };
  ws.onerror = (e) => console.error(`listener ${userIndex}: ${e.error}`);
  setTimeout(() => {
    if (heartbeat) clearInterval(heartbeat);
    check(null, {
      'listener: CONNECTED': () => connected,
      'listener: không có STOMP ERROR': () => !stompError,
      'listener: giữ kết nối tới hết bài': () => !closedEarly,
    });
    const expected = runMessages(data.startId, userIndex);
    if (expected) {
      expected.forEach((m) => delivery.add(got.has(m.id)));
      listenersVerified.add(1);
    }
    ws.close();
  }, HOLD_MS);
}

// Sender: user riêng (0..4) gửi một tin "fanout <sender>-<lần>" mỗi giây trong 2 phút, đếm tin tạo được (201)
export function sender() {
  const userIndex = exec.scenario.iterationInTest;
  const end = Date.now() + SEND_MS;
  for (let i = 0; Date.now() < end; i++) {
    const content = `fanout ${userIndex}-${i}`;
    const res = http.post(MESSAGES_URL, JSON.stringify({ content, nonce: `${content}-${Date.now()}` }),
      { headers: authHeaders(userIndex), tags: { name: 'send' } });
    if (check(res, { 'sender: status 201': (r) => r.status === 201 })) messagesSent.add(1);
    sleep(1);
  }
}

// In thêm tỉ lệ giao frames_received / (messages_sent * 50) sau bản tóm tắt mặc định của k6
export function handleSummary(data) {
  const frames = data.metrics.frames_received ? data.metrics.frames_received.values.count : 0;
  const sent = data.metrics.messages_sent ? data.metrics.messages_sent.values.count : 0;
  console.log(`tỉ lệ giao = frames_received / (messages_sent * ${LISTENERS}) = ${frames} / (${sent} * ${LISTENERS}) = ${sent ? frames / (sent * LISTENERS) : 0}`);
}
