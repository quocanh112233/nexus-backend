import { SharedArray } from 'k6/data';

const shared = new SharedArray('state', () => [JSON.parse(open('/perf/.state/state.json'))]);

// State do setup.sh ghi (channelId, midMessageId, users[]), đọc một lần và dùng chung cho mọi VU
export const state = shared[0];

// Header có Bearer token của user thứ userIndex (quay vòng theo số user) và Content-Type JSON
export function authHeaders(userIndex) {
  const user = state.users[userIndex % state.users.length];
  return { Authorization: `Bearer ${user.accessToken}`, 'Content-Type': 'application/json' };
}
