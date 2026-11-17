-- Dừng ở lỗi đầu tiên để một câu hỏng không bị bỏ qua trong kết quả đo
\set ON_ERROR_STOP on

-- Keyset sâu 0: lấy con trỏ before là id ở vị trí 0 từ mới nhất, chạy câu của MessageDao.before hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L AND m.id < %L ORDER BY m.id DESC LIMIT 50', :'channel', id) AS q
FROM messaging.messages WHERE channel_id = :'channel' ORDER BY id DESC OFFSET 0 LIMIT 1 \gset
\echo '=== keyset, độ sâu 0 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- Keyset sâu 100000: lấy con trỏ before là id ở vị trí 100000 từ mới nhất, chạy câu của MessageDao.before hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L AND m.id < %L ORDER BY m.id DESC LIMIT 50', :'channel', id) AS q
FROM messaging.messages WHERE channel_id = :'channel' ORDER BY id DESC OFFSET 100000 LIMIT 1 \gset
\echo '=== keyset, độ sâu 100000 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- Keyset sâu 500000: lấy con trỏ before là id ở vị trí 500000 từ mới nhất, chạy câu của MessageDao.before hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L AND m.id < %L ORDER BY m.id DESC LIMIT 50', :'channel', id) AS q
FROM messaging.messages WHERE channel_id = :'channel' ORDER BY id DESC OFFSET 500000 LIMIT 1 \gset
\echo '=== keyset, độ sâu 500000 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- Keyset sâu 999950: lấy con trỏ before là id ở vị trí 999950 từ mới nhất, chạy câu của MessageDao.before hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L AND m.id < %L ORDER BY m.id DESC LIMIT 50', :'channel', id) AS q
FROM messaging.messages WHERE channel_id = :'channel' ORDER BY id DESC OFFSET 999950 LIMIT 1 \gset
\echo '=== keyset, độ sâu 999950 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- OFFSET 0: chạy câu OFFSET hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L ORDER BY m.id DESC LIMIT 50 OFFSET 0', :'channel') AS q \gset
\echo '=== offset, độ sâu 0 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- OFFSET 100000: chạy câu OFFSET hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L ORDER BY m.id DESC LIMIT 50 OFFSET 100000', :'channel') AS q \gset
\echo '=== offset, độ sâu 100000 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- OFFSET 500000: chạy câu OFFSET hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L ORDER BY m.id DESC LIMIT 50 OFFSET 500000', :'channel') AS q \gset
\echo '=== offset, độ sâu 500000 ==='
\o /dev/null
:q;
:q;
\o
:q;

-- OFFSET 999950: chạy câu OFFSET hai lần để làm ấm cache rồi in lần ba
SELECT format('EXPLAIN (ANALYZE, BUFFERS) SELECT m.* FROM messaging.messages m WHERE m.channel_id = %L ORDER BY m.id DESC LIMIT 50 OFFSET 999950', :'channel') AS q \gset
\echo '=== offset, độ sâu 999950 ==='
\o /dev/null
:q;
:q;
\o
:q;
