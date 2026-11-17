import http from 'k6/http';
import { check, sleep } from 'k6';
import { state, authHeaders } from './lib/state.js';

export const options = {
  scenarios: { history: { executor: 'constant-vus', vus: 50, duration: '1m' } },
  thresholds: {
    'http_req_duration{name:history}': ['p(95)<50'],
    'checks': ['rate>0.99'],
  },
};

// Gọi một trang lịch sử 50 tin bằng token của user thứ userIndex, kiểm 200 và đủ 50 tin của đúng kênh
function getPage(url, userIndex) {
  const res = http.get(url, { headers: authHeaders(userIndex), tags: { name: 'history' } });
  check(res, {
    'status 200': (r) => r.status === 200,
    'đủ 50 tin của kênh': (r) => {
      if (r.status !== 200) return false;
      const page = r.json();
      return Array.isArray(page) && page.length === 50 && page[0].channelId === state.channelId;
    },
  });
}

// Mỗi vòng lấy trang mới nhất, nghỉ 1 giây, lấy trang sâu (trước tin thứ 500 000), nghỉ 1 giây: mỗi user tối đa 60 request mỗi phút
export default function () {
  const base = `${__ENV.BASE_URL}/api/v1/channels/${state.channelId}/messages?limit=50`;
  getPage(base, __VU - 1);
  sleep(1);
  getPage(`${base}&before=${state.midMessageId}`, __VU - 1);
  sleep(1);
}
