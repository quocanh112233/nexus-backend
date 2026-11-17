import http from 'k6/http';
import { check, sleep } from 'k6';
import { state, authHeaders } from './lib/state.js';

export const options = {
  scenarios: { send: { executor: 'constant-vus', vus: 50, duration: '2m' } },
  thresholds: {
    'http_req_duration{name:send}': ['p(95)<150'],
    'checks': ['rate>0.99'],
  },
};

// Mỗi VU gửi một tin mỗi giây bằng token của user riêng, kiểm 201 và tin trả về đúng kênh, đúng nội dung
export default function () {
  const tag = `${__VU}-${__ITER}`;
  const res = http.post(
    `${__ENV.BASE_URL}/api/v1/channels/${state.channelId}/messages`,
    JSON.stringify({ content: `k6 ${tag}`, nonce: `${tag}-${Date.now()}` }),
    { headers: authHeaders(__VU - 1), tags: { name: 'send' } },
  );
  check(res, {
    'status 201': (r) => r.status === 201,
    'đúng kênh và nội dung': (r) => {
      if (r.status !== 201) return false;
      const body = r.json();
      return body.channelId === state.channelId && body.content === `k6 ${tag}` && !!body.id;
    },
  });
  sleep(1);
}
