import type { JobEvent } from './jobs.js';

export interface HubClient {
  write(chunk: string): void;
}

/**
 * 广播到操作台的事件。JobEvent 是主体；其余事件不属于 Job 的生命周期，
 * 但同样需要其它开着的操作台刷新状态：
 * - thread.deleted    把那条会话从列表里去掉
 * - job.forward_failed 自动转发失败，请求已留在 pending 等人工（提示 + 展示原因）
 * - settings.updated / providers.updated  多开操作台时同步开关与厂商列表
 */
export type HubEvent =
  | JobEvent
  | { type: 'thread.deleted'; threadId: string }
  | { type: 'job.forward_failed'; jobId: string; threadId: string; message: string }
  | { type: 'settings.updated' }
  | { type: 'providers.updated' };

/**
 * 操作台实时通道（设计 §5.4）：向所有在线管理端 SSE 连接广播事件。
 * 事件负载刻意保持最小（jobId/threadId/deadlineAt），前端收到后重新拉取数据。
 */
export class OperatorHub {
  private readonly clients = new Set<HubClient>();
  private pingTimer: NodeJS.Timeout | null = null;

  addClient(client: HubClient): () => void {
    this.clients.add(client);
    return () => {
      this.clients.delete(client);
    };
  }

  get clientCount(): number {
    return this.clients.size;
  }

  broadcast(evt: HubEvent): void {
    this.writeAll(`data: ${JSON.stringify(evt)}\n\n`);
  }

  startPing(intervalMs = 20_000): void {
    if (this.pingTimer !== null) return;
    this.pingTimer = setInterval(() => {
      this.writeAll(`data: ${JSON.stringify({ type: 'ping' })}\n\n`);
    }, intervalMs);
    this.pingTimer.unref?.();
  }

  stopPing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private writeAll(frame: string): void {
    for (const client of [...this.clients]) {
      try {
        client.write(frame);
      } catch {
        this.clients.delete(client); // 连接已死，剔除
      }
    }
  }
}
