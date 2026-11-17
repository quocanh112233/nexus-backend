import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { state, authHeaders } from './lib/state.js';

const accepted = new Counter('accepted');
const limited = new Counter('limited');
const other = new Counter('other');

export const options = {
  scenarios: { burst: { executor: 'per-vu-iterations', vus: 1, iterations: 1 } },
  thresholds: {
    accepted: ['count>=5', 'count<=8'],
    limited: ['count>=12'],
    checks: ['rate==1'],
  },
};

// Gửi một tin bằng token của user thứ userIndex vào kênh đã seed, trả response
function send(userIndex, tag) {
  return http.post(
    `${__ENV.BASE_URL}/api/v1/channels/${state.channelId}/messages`,
    JSON.stringify({ content: `k6 rl ${tag}`, nonce: `rl-${tag}-${Date.now()}` }),
    { headers: authHeaders(userIndex), tags: { name: 'send' } },
  );
}

// users[10] gửi 20 tin liền kề, users[11] gửi ngay sau đó khi bucket users[10] đang cạn, rồi đợi 6 giây cho users[10] gửi lại
export default function () {
  for (let i = 0; i < 20; i++) {
    const res = send(10, `burst-${i}`);
    if (res.status === 201) {
      accepted.add(1);
    } else if (res.status === 429) {
      limited.add(1);
      console.log(`429 lần ${i} Retry-After=${res.headers['Retry-After']}`);
      check(res, { '429 có Retry-After': (r) => !!r.headers['Retry-After'] });
    } else {
      other.add(1);
      console.log(`khác 201/429 lần ${i} status=${res.status}`);
    }
  }
  const other11 = send(11, 'user-khac');
  console.log(`users[11] status=${other11.status}`);
  check(other11, { 'user khác có bucket riêng, 201': (r) => r.status === 201 });
  sleep(6);
  check(send(10, 'sau-6s'), { 'sau 6 giây bucket nạp lại, 201': (r) => r.status === 201 });
}
