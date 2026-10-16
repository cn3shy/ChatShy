import type { FastifyRequest } from 'fastify';
import { describe, expect, it } from 'vitest';
import { detectClient, fingerprintOf, normalizeIp, parseCaller, requestSource } from '../../src/core/source.js';

function fakeReq(ip: string, ua?: string, extra: Record<string, string> = {}): FastifyRequest {
  const headers: Record<string, string> = { ...extra };
  if (ua !== undefined) headers['user-agent'] = ua;
  return { ip, headers } as unknown as FastifyRequest;
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

describe('parseCaller（调用方自报的名字）', () => {
  it('清洗控制字符与多余空白（换行会撑坏会话列表的展示位）', () => {
    expect(parseCaller('  张 \n 三 \r\n')).toBe('张 三');
  });

  it('超长按码点截断，不劈开 emoji', () => {
    expect(parseCaller('x'.repeat(100))).toHaveLength(40);
    expect(parseCaller('🙂'.repeat(60))).toBe('🙂'.repeat(40));
  });

  it('缺失与空白都不算身份', () => {
    expect(parseCaller(null)).toBe('');
    expect(parseCaller('   ')).toBe('');
  });

  it('中文名：百分号编码与原始 UTF-8 字节都还原成同一个名字', () => {
    // Node/浏览器系客户端只能发 ASCII，中文得自己编码
    expect(parseCaller('%E5%BC%A0%E4%B8%89')).toBe('张三');
    // curl / Python / Go 直接发 UTF-8 字节，Node 按 latin-1 解出来是乱码，得再解一次
    expect(parseCaller(Buffer.from('张三', 'utf8').toString('latin1'))).toBe('张三');
  });

  it('名字里真带 % 时不误当编码', () => {
    expect(parseCaller('50%')).toBe('50%');
  });

  it('两种编码方式落到同一个身份指纹上', () => {
    const percent = requestSource(fakeReq('127.0.0.1', 'curl/8.4.0', { 'x-chatshy-caller': '%E5%BC%A0%E4%B8%89' }));
    const rawBytes = requestSource(
      fakeReq('203.0.113.7', 'curl/8.4.0', { 'x-chatshy-caller': Buffer.from('张三', 'utf8').toString('latin1') }),
    );
    expect(percent.client).toBe('张三');
    expect(percent.fingerprint).toBe(rawBytes.fingerprint);
  });
});

describe('requestSource', () => {
  it('组装出完整的来源信息', () => {
    const src = requestSource(fakeReq('::ffff:192.168.1.9', 'CherryStudio/1.6.0'));
    expect(src.ip).toBe('192.168.1.9');
    expect(src.ua).toBe('CherryStudio/1.6.0');
    expect(src.caller).toBe('');
    expect(src.client).toBe('Cherry Studio');
    expect(src.fingerprint).toBe(fingerprintOf('192.168.1.9', 'CherryStudio/1.6.0', 'Cherry Studio'));
  });

  it('没有 user-agent 头时不崩，落到未知来源', () => {
    const src = requestSource(fakeReq('127.0.0.1'));
    expect(src.ua).toBe('');
    expect(src.client).toBe('未知来源');
    expect(src.fingerprint).toHaveLength(32);
  });

  it('带了身份头就用这个名字当展示名', () => {
    const src = requestSource(fakeReq('127.0.0.1', 'CherryStudio/1.6.0', { 'x-chatshy-caller': '张三' }));
    expect(src.client).toBe('张三');
    expect(src.caller).toBe('张三');
    expect(src.ua).toBe('CherryStudio/1.6.0'); // 原始 UA 仍留着，详情页可查
  });

  it('身份头与 IP 指纹结构互斥，不会撞到同一条会话上', () => {
    const byName = requestSource(fakeReq('203.0.113.7', 'curl/8.4.0', { 'x-chatshy-caller': '张三' }));
    const byIp = requestSource(fakeReq('203.0.113.7', 'curl/8.4.0'));
    expect(byName.fingerprint).not.toBe(byIp.fingerprint);
  });
});

describe('身份指纹：换网络不切段', () => {
  it('同名的人换 IP、换客户端版本，仍是同一条会话', () => {
    const a = requestSource(fakeReq('127.0.0.1', 'CherryStudio/1.6.0', { 'x-chatshy-caller': '张三' }));
    const b = requestSource(fakeReq('203.0.113.7', 'CherryStudio/1.9.3', { 'x-chatshy-caller': '张三' }));
    expect(a.fingerprint).toBe(b.fingerprint);
  });

  it('名字不同就是两个人', () => {
    const a = requestSource(fakeReq('127.0.0.1', 'curl/8.4.0', { 'x-chatshy-caller': '张三' }));
    const b = requestSource(fakeReq('127.0.0.1', 'curl/8.4.0', { 'x-chatshy-caller': '李四' }));
    expect(a.fingerprint).not.toBe(b.fingerprint);
  });
});

describe('clientIpOf（反代下的真实 IP）', () => {
  it('对端是本机、且只配了 X-Real-IP 时采信它', () => {
    const src = requestSource(fakeReq('127.0.0.1', 'curl/8.4.0', { 'x-real-ip': '203.0.113.9' }));
    expect(src.ip).toBe('203.0.113.9');
  });

  it('对端是同内网的反代（如 Docker 网关）时同样采信', () => {
    const src = requestSource(fakeReq('172.18.0.3', 'curl/8.4.0', { 'x-real-ip': '203.0.113.9' }));
    expect(src.ip).toBe('203.0.113.9');
  });

  it('对端是公网时忽略 X-Real-IP，挡住直连伪造', () => {
    const src = requestSource(fakeReq('198.51.100.4', 'curl/8.4.0', { 'x-real-ip': '10.0.0.1' }));
    expect(src.ip).toBe('198.51.100.4');
  });

  it('req.ip 已是真实公网 IP（trustProxy 读到了 XFF）时不再动它', () => {
    const src = requestSource(fakeReq('203.0.113.9', 'curl/8.4.0', { 'x-real-ip': '127.0.0.1' }));
    expect(src.ip).toBe('203.0.113.9');
  });
});
