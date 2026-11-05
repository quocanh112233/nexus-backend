# Cấu trúc frontend

File này mô tả cấu trúc `nexus-frontend`: App Router, tổ chức theo feature, Zustand store theo domain, API client, WebSocket client, xử lý auth và Dockerfile.

> **Lựa chọn có chủ đích (P-30):** frontend ở mức **tối giản đủ demo**. Dùng component shadcn/ui có sẵn, không tự thiết kế giao diện, không tối ưu SEO/SSR. Mọi trang là client component sau khi đăng nhập. Thời gian dồn cho backend.

## 1. Cây thư mục

```text
nexus-frontend/src/
├── app/                                   # Next.js App Router
│   ├── layout.tsx                         # Providers, Toaster
│   ├── (auth)/login/page.tsx
│   ├── (auth)/register/page.tsx
│   ├── invite/[code]/page.tsx             # Preview + tham gia
│   ├── api/health/route.ts                # Healthcheck cho Docker
│   └── (app)/                             # Cần đăng nhập (AuthGuard ở layout)
│       ├── layout.tsx                     # Khung 3 cột: server list | channel list | nội dung; mở WebSocket
│       ├── channels/@me/page.tsx          # Bạn bè + lời mời
│       ├── channels/@me/[dmId]/page.tsx   # DM
│       ├── channels/[serverId]/[channelId]/page.tsx   # Chat kênh
│       ├── servers/[serverId]/settings/page.tsx       # Tên, role, invite, member
│       └── settings/profile/page.tsx      # Profile + avatar
├── features/                              # Theo domain, mỗi feature: api.ts, store.ts, components/
│   ├── auth/        (api.ts, store.ts, AuthGuard.tsx, LoginForm.tsx, RegisterForm.tsx)
│   ├── users/       (api.ts, store.ts: cache profile theo id, ProfileForm.tsx, AvatarUpload.tsx)
│   ├── servers/     (api.ts, store.ts: servers, categories, channels, roles, myPermissions; ServerSidebar.tsx, ChannelList.tsx, RoleEditor.tsx, OverwriteEditor.tsx, InviteDialog.tsx)
│   ├── messages/    (api.ts, store.ts: messages theo channelId, typing; MessageList.tsx, MessageItem.tsx, Composer.tsx, ReactionBar.tsx)
│   ├── social/      (api.ts, store.ts: friends, requests, dms; FriendList.tsx, AddFriendForm.tsx)
│   ├── presence/    (api.ts, store.ts: status theo userId; StatusDot.tsx)
│   └── notifications/ (api.ts, store.ts: readStates; UnreadBadge.tsx)
├── lib/
│   ├── http.ts                            # fetch wrapper: base URL, Bearer, refresh khi 401, parse Problem Details
│   ├── ws.ts                              # STOMP client singleton, subscribe/unsubscribe, dispatch event → store
│   ├── cloudinary.ts                      # Upload với chữ ký
│   └── ids.ts                             # sinh nonce
├── components/ui/                         # shadcn/ui sinh ra
└── types/api.ts                           # Kiểu response/ event (viết tay theo api-conventions + event-catalog)
```

## 2. Zustand store theo domain

| Store | State chính | Cập nhật từ |
|-------|------------|-------------|
| `authStore` | `accessToken`, `me`, `status` | `auth/api.ts` (login, refresh) — chỉ trong bộ nhớ |
| `usersStore` | `profiles: Record<userId, Profile>` | `GET /users?ids=` (gom id thiếu, debounce 50 ms), cache TTL 5 phút |
| `serversStore` | servers, channels, categories, roles, bans, `myPermissions` theo kênh | REST + event `/topic/servers/{id}`, `/user/queue/events` |
| `messagesStore` | `byChannel: Record<channelId, Message[]>`, `pendingByNonce`, `typing` | REST lịch sử + event `/topic/channels/{id}` |
| `socialStore` | friends, incoming/outgoing requests, dms | REST + `/user/queue/events` |
| `presenceStore` | `status: Record<userId, 'ONLINE'|'OFFLINE'>` (chỉ user được phép thấy) | `GET /presence`, `PresenceChanged` từ `/topic/servers/{id}` và `/user/queue/events` |
| `notificationsStore` | `readStates: Record<channelId, ReadState>` | `GET /read-states`, `/user/queue/events` |

Quy tắc: component chỉ gọi action của store; store gọi `features/*/api.ts`; `lib/ws.ts` chuyển event tới action của store theo `type`. Store không gọi nhau trực tiếp ngoại trừ đọc `authStore`.

## 3. API client (`lib/http.ts`)

- Gắn `Authorization: Bearer` từ `authStore`.
- 401 → gọi `POST /auth/refresh` **một lần** (promise dùng chung) → thử lại request; refresh lỗi → logout.
- Lỗi trả về dạng `ApiError { status, code, detail, errors }` từ Problem Details để form hiển thị.
- `credentials: 'include'` chỉ cho `/auth/**`.

## 4. WebSocket client (`lib/ws.ts`)

- `@stomp/stompjs`, URL `NEXT_PUBLIC_WS_URL`, header `Authorization` ở CONNECT, reconnect tự động.
- Subscribe `/user/queue/events` và `/topic/servers/{id}` của **mọi** server của tôi khi kết nối (để nhận cập nhật server và presence của người chung server); `/topic/channels/{id}` khi mở kênh (hủy khi rời).
- Sau reconnect: subscribe lại + gọi REST `?after=` để bù tin (xem [realtime-design.md](../02-architecture/realtime-design.md#7-kết-nối-lại-và-bù-dữ-liệu)).

## 5. Auth

- Access token chỉ trong bộ nhớ; tải lại trang → `AuthGuard` gọi `/auth/refresh` (cookie) để lấy token mới.
- Route `(app)` bọc `AuthGuard` (client component); không dùng middleware server-side để giữ đơn giản.

## 6. Dockerfile

Vị trí: `nexus-frontend/Dockerfile` ([ADR-007](../00-overview/adr/ADR-007-docker-packaging.md)).

```dockerfile
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_API_URL
ARG NEXT_PUBLIC_WS_URL
RUN corepack enable && pnpm build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S nextjs && adduser -S -u 10001 -G nextjs nextjs
COPY --from=build --chown=nextjs:nextjs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nextjs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nextjs /app/public ./public
USER 10001
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://localhost:3000/api/health || exit 1
CMD ["node", "server.js"]
```

Lưu ý: biến `NEXT_PUBLIC_*` được nhúng lúc build → image cho local và demo build với giá trị khác nhau (build arg).
