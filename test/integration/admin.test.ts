import { afterEach, describe, expect, it } from 'vitest';
import { listThreadMessages } from '../../src/core/threads.js';
import { ADMIN_PASSWORD, waitFor, startTestServer, type TestServer } from './helpers.js';

let servers: TestServer[] = [];
afterEach(async () => {
  for (const s of servers) await s.close();
  servers = [];
});

async function boot(): Promise<TestServer> {
  const s = await startTestServer();
  servers.push(s);
  return s;
}

async function login(s: TestServer): Promise<string> {
  const res = await fetch(`${s.base}/admin/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: ADMIN_PASSWORD }),
  });
  expect(res.status).toBe(200);
  const setCookie = res.headers.get('set-cookie')!;
  return setCookie.split(';')[0]!;
}

const jsonHeaders = (cookie?: string): Record<string, string> => ({
  'content-type': 'application/json',
  ...(cookie === undefined ? {} : { cookie }),
});

async function fetchJson<T>(url: string, init?: RequestInit): Promise<{ status: number; body: T }> {
  const res = await fetch(url, init);
  return { status: res.status, body: (await res.json()) as T };
}

describe('管理端 API（集成）', () => {
  it('未登录 → 401；登录后 me 可见', async () => {
    const s = await boot();
    expect((await fetch(`${s.base}/admin/api/threads`)).status).toBe(401);
    expect((await fetch(`${s.base}/admin/api/settings`)).status).toBe(401);

    const cookie = await login(s);
    const me = await fetch(`${s.base}/admin/api/me`, { headers: { cookie } });
    expect(me.status).toBe(200);
  });

  it('登录节流：连续 5 次错误密码后锁定 429', async () => {
    const s = await boot();
    for (let i = 0; i < 5; i++) {
      const res = await fetch(`${s.base}/admin/api/login`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ password: 'wrong' }),
      });
      expect(res.status).toBe(401);
    }
    const locked = await fetch(`${s.base}/admin/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ password: ADMIN_PASSWORD }),
    });
    expect(locked.status).toBe(429);
  });

  it('回复工作流：API 请求挂起 → 列表/详情可见 → 回复 → 客户端收到', async () => {
    const s = await boot();
    const cookie = await login(s);

    const pendingReq = fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${s.key}`,
        'user-agent': 'CherryStudio/1.0.0',
      },
      body: JSON.stringify({ model: 'gpt-test', messages: [{ role: 'user', content: '管理端测试' }] }),
    });
    await waitFor(() => s.queue.listPending().length === 1);

    const list = await fetchJson<{ threads: Array<{ id: string; sourceClient: string; sourceIp: string; sourceUa: string; pendingCount: number; pendingJobId: string; pendingDeadlineAt: number; title: string; lastMessage: string }> }>(
      `${s.base}/admin/api/threads`,
      { headers: { cookie } },
    );
    const thread = list.body.threads.find((t) => t.pendingCount === 1)!;
    // 会话上打的是"来源"标签，取代了原来的 keyName
    expect(thread.sourceClient).toBe('Cherry Studio');
    expect(thread.sourceIp).toBe('127.0.0.1');
    expect(thread.sourceUa).toBe('CherryStudio/1.0.0');
    expect(thread.title).toBe('管理端测试');
    expect(thread.pendingDeadlineAt).toBeGreaterThan(Date.now());

    const detail = await fetchJson<{ messages: Array<{ role: string; content: string; delivered: boolean }>; pendingJobs: unknown[] }>(
      `${s.base}/admin/api/threads/${thread.id}`,
      { headers: { cookie } },
    );
    expect(detail.body.messages.map((m) => m.content)).toEqual(['管理端测试']);
    expect(detail.body.pendingJobs).toHaveLength(1);

    const reply = await fetchJson<{ ok: boolean; delivered: boolean }>(`${s.base}/admin/api/jobs/${thread.pendingJobId}/reply`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ content: '管理端人工回复' }),
    });
    expect(reply.status).toBe(200);
    expect(reply.body.delivered).toBe(true);

    const res = await pendingReq;
    expect(res.status).toBe(200);
    const body = (await res.json()) as { choices: Array<{ message: { content: string } }> };
    expect(body.choices[0]!.message.content).toBe('管理端人工回复');

    // 列表 pending 清零，最近消息已更新
    const list2 = await fetchJson<{ threads: Array<{ id: string; pendingCount: number; lastMessage: string }> }>(
      `${s.base}/admin/api/threads`,
      { headers: { cookie } },
    );
    const thread2 = list2.body.threads.find((t) => t.id === thread.id)!;
    expect(thread2.pendingCount).toBe(0);
    expect(thread2.lastMessage).toBe('管理端人工回复');
  });

  it('忽略挂起请求 → 客户端收到 504', async () => {
    const s = await boot();
    const cookie = await login(s);

    const pendingReq = fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
      body: JSON.stringify({ messages: [{ role: 'user', content: '将被忽略' }] }),
    });
    await waitFor(() => s.queue.listPending().length === 1);
    const jobId = s.queue.listPending()[0]!.id;

    const ignore = await fetchJson<{ ok: boolean }>(`${s.base}/admin/api/jobs/${jobId}/ignore`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: '{}',
    });
    expect(ignore.status).toBe(200);

    const res = await pendingReq;
    expect(res.status).toBe(504);
  });

  it('超时后补回复：delivered=false，以 console 源落库不污染 API 匹配', async () => {
    const s = await boot();
    const cookie = await login(s);

    const res = await s.withDefaultTimeout(300, () =>
      fetch(`${s.base}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
        body: JSON.stringify({ messages: [{ role: 'user', content: '超时了' }] }),
      }),
    );
    expect(res.status).toBe(504);

    const job = s.db.prepare('SELECT id, thread_id FROM jobs ORDER BY created_at DESC LIMIT 1').get() as {
      id: string;
      thread_id: string;
    };
    const reply = await fetchJson<{ ok: boolean; delivered: boolean }>(`${s.base}/admin/api/jobs/${job.id}/reply`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ content: '迟到的回复' }),
    });
    expect(reply.status).toBe(200);
    expect(reply.body.delivered).toBe(false);

    const msgs = listThreadMessages(s.db, job.thread_id);
    const last = msgs.at(-1)!;
    expect(last.content).toBe('迟到的回复');
    expect(last.source).toBe('console'); // 不进 API 转录本

    const detail = await fetchJson<{ messages: Array<{ delivered: boolean; content: string }> }>(
      `${s.base}/admin/api/threads/${job.thread_id}`,
      { headers: { cookie } },
    );
    expect(detail.body.messages.at(-1)!.delivered).toBe(false);

    // 同一 job 再回复 → 409
    const again = await fetch(`${s.base}/admin/api/jobs/${job.id}/reply`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ content: '再来一次' }),
    });
    // 该 job 状态是 timeout（非 replied），第二次仍会入库，返回 delivered:false；
    // 但已 replied 的 job 会 409 —— 这里验证 timeout 后的重复回复仍走存档路径
    expect(again.status).toBe(200);
  });

  it('全局调用方 Key：设置页可见、可重新生成、旧 Key 立即失效', async () => {
    const s = await boot();
    const cookie = await login(s);

    const settings = await fetchJson<{ callerKey: string }>(`${s.base}/admin/api/settings`, {
      headers: { cookie },
    });
    expect(settings.body.callerKey).toBe(s.key);

    const call = (key: string): Promise<Response> =>
      fetch(`${s.base}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
        body: JSON.stringify({ messages: [{ role: 'user', content: 'x' }] }),
      });

    // 当前 key 可用
    const first = call(s.key);
    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, 'ok');
    expect((await first).status).toBe(200);

    // 重新生成
    const regen = await fetchJson<{ callerKey: string }>(`${s.base}/admin/api/caller-key`, {
      method: 'POST',
      headers: jsonHeaders(cookie),
      body: '{}',
    });
    expect(regen.status).toBe(200);
    expect(regen.body.callerKey).toMatch(/^sk-chatshy-/);
    expect(regen.body.callerKey).not.toBe(s.key);

    // 旧 key 立即失效；新 key 可用
    expect((await call(s.key)).status).toBe(401);
    const withNew = call(regen.body.callerKey);
    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, 'ok');
    expect((await withNew).status).toBe(200);
  });

  it('设置：超时与模型名可改；改密码需 current_password', async () => {
    const s = await boot();
    const cookie = await login(s);

    const patched = await fetchJson<{ defaultTimeoutMs: number; modelName: string }>(`${s.base}/admin/api/settings`, {
      method: 'PATCH',
      headers: jsonHeaders(cookie),
      body: JSON.stringify({ defaultTimeoutMs: 5000, modelName: 'shy-model' }),
    });
    expect(patched.body).toMatchObject({ defaultTimeoutMs: 5000, modelName: 'shy-model' });

    // /v1/models 展示新模型名
    const models = await fetchJson<{ data: Array<{ id: string }> }>(`${s.base}/v1/models`, {
      headers: { authorization: `Bearer ${s.key}` },
    });
    expect(models.body.data[0]!.id).toBe('shy-model');

    // 改密码：缺 current_password → 403
    expect(
      (
        await fetch(`${s.base}/admin/api/settings`, {
          method: 'PATCH',
          headers: jsonHeaders(cookie),
          body: JSON.stringify({ adminPassword: 'newpass123' }),
        })
      ).status,
    ).toBe(403);

    // 正确 current_password → 成功，新密码可登录
    expect(
      (
        await fetch(`${s.base}/admin/api/settings`, {
          method: 'PATCH',
          headers: jsonHeaders(cookie),
          body: JSON.stringify({ adminPassword: 'newpass123', currentPassword: ADMIN_PASSWORD }),
        })
      ).status,
    ).toBe(200);
    const relogin = await fetch(`${s.base}/admin/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ password: 'newpass123' }),
    });
    expect(relogin.status).toBe(200);
  });

  it('SSE 事件流：hello → job.created → job.replied', async () => {
    const s = await boot();
    const cookie = await login(s);

    const ac = new AbortController();
    const events = fetch(`${s.base}/admin/api/events`, { headers: { cookie }, signal: ac.signal });

    let seen = '';
    const reading = (async () => {
      const res = await events;
      const reader = res.body!.getReader();
      const decoder = new TextDecoder();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          seen += decoder.decode(value, { stream: true });
        }
      } catch {
        /* aborted */
      }
    })();

    await waitFor(() => seen.includes('"hello"'));

    const pendingReq = fetch(`${s.base}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${s.key}` },
      body: JSON.stringify({ messages: [{ role: 'user', content: '事件测试' }] }),
    });
    await waitFor(() => seen.includes('"job.created"'));
    await waitFor(() => s.queue.listPending().length === 1);
    s.queue.reply(s.queue.listPending()[0]!.id, '回你了');
    expect((await pendingReq).status).toBe(200);

    await waitFor(() => seen.includes('"job.replied"'));
    ac.abort();
    await reading;
  });
});
