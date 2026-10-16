import { describe, expect, it } from 'vitest';
import {
  anthropicErrors,
  anthropicPingFrame,
  buildMessageFrames,
  newMessageId,
  parseAnthropicRequest,
  renderMessage,
} from '../../src/protocol/anthropic.js';

describe('parseAnthropicRequest', () => {
  it('system 字符串 / 块数组；messages 字符串 / 块数组', () => {
    const r1 = parseAnthropicRequest({
      model: 'claude-x',
      max_tokens: 1024,
      system: '你是助手',
      messages: [{ role: 'user', content: '你好' }],
    });
    expect(r1.ok).toBe(true);
    if (!r1.ok) return;
    expect(r1.value.prompt.system).toBe('你是助手');
    expect(r1.value.prompt.messages).toEqual([{ role: 'user', content: '你好' }]);

    const r2 = parseAnthropicRequest({
      system: [{ type: 'text', text: '第一段' }, { type: 'text', text: '第二段' }],
      messages: [
        {
          role: 'user',
          content: [
            { type: 'text', text: '看看' },
            { type: 'image', source: { type: 'base64' } },
            { type: 'tool_result', tool_use_id: 't1', content: '结果' },
          ],
        },
      ],
    });
    expect(r2.ok).toBe(true);
    if (!r2.ok) return;
    expect(r2.value.prompt.system).toBe('第一段第二段');
    expect(r2.value.prompt.messages[0]!.content).toBe('看看[不支持的块类型: image][不支持的块类型: tool_result]');
  });

  it('stream 透传；忽略 max_tokens 等字段', () => {
    const r = parseAnthropicRequest({
      stream: true,
      max_tokens: 1,
      messages: [{ role: 'user', content: 'x' }],
    });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.value.stream).toBe(true);
  });

  it('非法请求返回 ok:false', () => {
    expect(parseAnthropicRequest(null).ok).toBe(false);
    expect(parseAnthropicRequest({}).ok).toBe(false);
    expect(parseAnthropicRequest({ messages: [] }).ok).toBe(false);
    expect(parseAnthropicRequest({ messages: [{ role: 'system', content: 'x' }] }).ok).toBe(false);
  });
});

describe('renderMessage（非流式）', () => {
  it('响应结构与设计 §4.3 逐字段一致', () => {
    const body = renderMessage({
      id: 'msg_test',
      model: 'claude-x',
      content: '人工回复内容',
      inputTokens: 12,
      outputTokens: 34,
    });
    expect(body).toEqual({
      id: 'msg_test',
      type: 'message',
      role: 'assistant',
      model: 'claude-x',
      content: [{ type: 'text', text: '人工回复内容' }],
      stop_reason: 'end_turn',
      stop_sequence: null,
      usage: { input_tokens: 12, output_tokens: 34 },
    });
  });
});

describe('buildMessageFrames（流式）', () => {
  const params = { id: 'msg_test', model: 'claude-x', content: '回复全文', inputTokens: 12, outputTokens: 34 };

  function parseFrame(frame: string): { event: string; data: unknown } {
    const [eventLine, dataLine] = frame.trim().split('\n');
    return {
      event: eventLine!.replace(/^event: /, ''),
      data: JSON.parse(dataLine!.replace(/^data: /, '')),
    };
  }

  it('六段事件序列与设计 §4.3 一致', () => {
    const frames = buildMessageFrames(params);
    const parsed = frames.map(parseFrame);

    expect(parsed.map((p) => p.event)).toEqual([
      'message_start',
      'content_block_start',
      'content_block_delta',
      'content_block_stop',
      'message_delta',
      'message_stop',
    ]);

    expect(parsed[0]!.data).toEqual({
      type: 'message_start',
      message: {
        id: 'msg_test',
        type: 'message',
        role: 'assistant',
        model: 'claude-x',
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 12, output_tokens: 0 },
      },
    });
    expect(parsed[1]!.data).toEqual({
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'text', text: '' },
    });
    expect(parsed[2]!.data).toEqual({
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: '回复全文' },
    });
    expect(parsed[3]!.data).toEqual({ type: 'content_block_stop', index: 0 });
    expect(parsed[4]!.data).toEqual({
      type: 'message_delta',
      delta: { stop_reason: 'end_turn', stop_sequence: null },
      usage: { output_tokens: 34 },
    });
    expect(parsed[5]!.data).toEqual({ type: 'message_stop' });
  });

  it('ping 事件帧', () => {
    expect(parseFrame(anthropicPingFrame())).toEqual({ event: 'ping', data: { type: 'ping' } });
  });

  it('错误体与错误事件形状', () => {
    expect(anthropicErrors.invalidKey()).toEqual({
      type: 'error',
      error: { type: 'authentication_error', message: 'invalid x-api-key' },
    });
    expect(anthropicErrors.timeout()).toEqual({
      type: 'error',
      error: { type: 'overloaded_error', message: 'Overloaded' },
    });
    expect(anthropicErrors.badRequest('boom')).toEqual({
      type: 'error',
      error: { type: 'invalid_request_error', message: 'boom' },
    });
  });

  it('id 生成带 msg_ 前缀', () => {
    expect(newMessageId()).toMatch(/^msg_[0-9a-f]{24}$/);
  });
});
