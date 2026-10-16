import type { FastifyRequest } from 'fastify';
import { describe, expect, it } from 'vitest';
import { detectClient, fingerprintOf, normalizeIp, requestSource } from '../../src/core/source.js';

function fakeReq(ip: string, ua?: string): FastifyRequest {
  return { ip, headers: ua === undefined ? {} : { 'user-agent': ua } } as unknown as FastifyRequest;
}

describe('detectClient', () => {
  it('识别常见客户端', () => {
    expect(detectClient('claude-cli/2.0.24 (external, cli)')).toBe('Claude Code');
    expect(detectClient('CherryStudio/1.6.0')).toBe('Cherry Studio');
    expect(detectClient('Chatbox/1.9.0')).toBe('ChatBox');
    expect(detectClient('OpenAI/Python 1.55.0')).toBe('OpenAI Python SDK');
    expect(detectClient('OpenAI/NodeJS/4.70.0')).toBe('OpenAI Node SDK');
    expect(detectClient('Anthropic/Python 0.40.0')).toBe('Anthropic Python SDK');
  });

  it('识别命令行与脚本', () => {
    expect(detectClient('curl/8.4.0')).toBe('curl');
    expect(detectClient('python-requests/2.31.0')).toBe('Python 脚本');
    expect(detectClient('axios/1.6.0')).toBe('脚本客户端');
  });

  it('识别浏览器', () => {
    expect(
      detectClient('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36'),
    ).toBe('浏览器');
  });

  it('缺 UA 与未知 UA 都不丢信息', () => {
    expect(detectClient('')).toBe('未知来源');
    expect(detectClient('   ')).toBe('未知来源');
    expect(detectClient('SomeVendorTool/3.1')).toBe('其他（SomeVendorTool/3.1）');
    // 过长的未知 UA 截断，避免把整个 UA 塞进展示位
    const long = 'X'.repeat(100);
    expect(detectClient(long)).toBe(`其他（${'X'.repeat(39)}…）`);
  });
});

describe('normalizeIp', () => {
  it('还原 IPv6 映射的 IPv4', () => {
    expect(normalizeIp('::ffff:127.0.0.1')).toBe('127.0.0.1');
    expect(normalizeIp('::FFFF:10.0.0.7')).toBe('10.0.0.7');
  });

  it('其它形式原样返回', () => {
    expect(normalizeIp('127.0.0.1')).toBe('127.0.0.1');
    expect(normalizeIp('2001:db8::1')).toBe('2001:db8::1');
    expect(normalizeIp(' 1.2.3.4 ')).toBe('1.2.3.4');
  });
});

describe('fingerprintOf（会话归并的身份标识）', () => {
  it('同 IP 同客户端 → 同一指纹', () => {
    const a = fingerprintOf('1.2.3.4', 'claude-cli/2.0.24 (external, cli)', 'Claude Code');
    const b = fingerprintOf('1.2.3.4', 'claude-cli/2.0.30 (external, cli)', 'Claude Code');
    expect(a).toBe(b);
  });

  it('客户端升级换版本号不会改变指纹（否则会话每次升级都被切段）', () => {
    const before = fingerprintOf('1.2.3.4', 'CherryStudio/1.5.0', 'Cherry Studio');
    const after = fingerprintOf('1.2.3.4', 'CherryStudio/1.9.3', 'Cherry Studio');
    expect(before).toBe(after);
  });

  it('IP 不同 → 指纹不同', () => {
    const a = fingerprintOf('1.2.3.4', 'curl/8.4.0', 'curl');
    const b = fingerprintOf('5.6.7.8', 'curl/8.4.0', 'curl');
    expect(a).not.toBe(b);
  });

  it('未识别的客户端用原始 UA 区分（此时 UA 是唯一依据）', () => {
    const a = fingerprintOf('1.2.3.4', 'Mozilla/5.0 Chrome/120', '浏览器');
    const b = fingerprintOf('1.2.3.4', 'Mozilla/5.0 Safari/605', '浏览器');
    expect(a).not.toBe(b);
  });
});

describe('requestSource', () => {
  it('组装出完整的来源信息', () => {
    const src = requestSource(fakeReq('::ffff:192.168.1.9', 'CherryStudio/1.6.0'));
    expect(src.ip).toBe('192.168.1.9');
    expect(src.ua).toBe('CherryStudio/1.6.0');
    expect(src.client).toBe('Cherry Studio');
    expect(src.fingerprint).toBe(fingerprintOf('192.168.1.9', 'CherryStudio/1.6.0', 'Cherry Studio'));
  });

  it('没有 user-agent 头时不崩，落到未知来源', () => {
    const src = requestSource(fakeReq('127.0.0.1'));
    expect(src.ua).toBe('');
    expect(src.client).toBe('未知来源');
    expect(src.fingerprint).toHaveLength(32);
  });
});
