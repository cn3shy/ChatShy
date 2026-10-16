import { afterEach, describe, expect, it, vi } from 'vitest';
import { OperatorHub } from '../../src/core/hub.js';

afterEach(() => {
  vi.useRealTimers();
});

function frameOf(mock: ReturnType<typeof vi.fn>, call: number): unknown {
  return JSON.parse((mock.mock.calls[call]![0] as string).replace(/^data: /, '').trim());
}

describe('OperatorHub', () => {
  it('广播给全部客户端，移除后不再收到', () => {
    const hub = new OperatorHub();
    const w1 = vi.fn();
    const w2 = vi.fn();
    const off1 = hub.addClient({ write: w1 });
    hub.addClient({ write: w2 });

    hub.broadcast({ type: 'job.created', jobId: 'j1', threadId: 't1', deadlineAt: 123 });
    expect(w1).toHaveBeenCalledTimes(1);
    expect(frameOf(w1, 0)).toEqual({ type: 'job.created', jobId: 'j1', threadId: 't1', deadlineAt: 123 });
    expect(w2).toHaveBeenCalledTimes(1);

    off1();
    hub.broadcast({ type: 'job.replied', jobId: 'j1', threadId: 't1' });
    expect(w1).toHaveBeenCalledTimes(1);
    expect(w2).toHaveBeenCalledTimes(2);
  });

  it('ping 定时器：按间隔发送，stopPing 后停止', () => {
    vi.useFakeTimers();
    const hub = new OperatorHub();
    const w = vi.fn();
    hub.addClient({ write: w });

    hub.startPing(1000);
    vi.advanceTimersByTime(3000);
    expect(w).toHaveBeenCalledTimes(3);
    expect(frameOf(w, 0)).toEqual({ type: 'ping' });

    hub.stopPing();
    vi.advanceTimersByTime(3000);
    expect(w).toHaveBeenCalledTimes(3);
  });

  it('write 抛异常的客户端被剔除，不再尝试', () => {
    const hub = new OperatorHub();
    const bad = {
      write: () => {
        throw new Error('dead connection');
      },
    };
    const good = vi.fn();
    hub.addClient(bad);
    hub.addClient({ write: good });

    hub.broadcast({ type: 'job.timeout', jobId: 'j1', threadId: 't1' });
    expect(good).toHaveBeenCalledTimes(1);
    expect(hub.clientCount).toBe(1);

    hub.broadcast({ type: 'job.timeout', jobId: 'j1', threadId: 't1' });
    expect(good).toHaveBeenCalledTimes(2);
    expect(hub.clientCount).toBe(1);
  });
});
