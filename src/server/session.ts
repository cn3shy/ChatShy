import crypto from 'node:crypto';

export const SESSION_COOKIE = 'chatshy_session';

const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const LOGIN_FAIL_LIMIT = 5;
const LOGIN_LOCK_MS = 60 * 1000;

interface SessionEntry {
  expiresAt: number;
}

interface FailureEntry {
  count: number;
  lockedUntil: number;
}

/** 管理端登录会话（内存，重启需重新登录）+ 登录失败节流 */
export class SessionStore {
  private readonly sessions = new Map<string, SessionEntry>();
  private readonly failures = new Map<string, FailureEntry>();

  create(): string {
    const token = crypto.randomBytes(32).toString('base64url');
    this.sessions.set(token, { expiresAt: Date.now() + SESSION_TTL_MS });
    return token;
  }

  verify(token: string): boolean {
    const entry = this.sessions.get(token);
    if (entry === undefined) return false;
    if (entry.expiresAt <= Date.now()) {
      this.sessions.delete(token);
      return false;
    }
    return true;
  }

  destroy(token: string): void {
    this.sessions.delete(token);
  }

  isLocked(ip: string): boolean {
    const f = this.failures.get(ip);
    return f !== undefined && f.lockedUntil > Date.now();
  }

  registerFailure(ip: string): void {
    const f = this.failures.get(ip) ?? { count: 0, lockedUntil: 0 };
    f.count += 1;
    if (f.count >= LOGIN_FAIL_LIMIT) {
      f.lockedUntil = Date.now() + LOGIN_LOCK_MS;
      f.count = 0;
    }
    this.failures.set(ip, f);
  }

  resetFailures(ip: string): void {
    this.failures.delete(ip);
  }
}
