import type { JobEvent } from './jobs.js';

export interface HubClient {
  write(chunk: string): void;
}

/**
 * 广播到操作台的事件。JobEvent 是主体；thread.deleted 不属于 Job 的生命周期，
 * 但同样需要让其它开着的操作台把那条会话从列表里去掉。
 */
export type HubEvent = JobEvent | { type: 'thread.deleted'; threadId: string };

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
