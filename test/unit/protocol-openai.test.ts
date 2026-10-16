import { describe, expect, it } from 'vitest';
import {
  buildCompletionFrames,
  newCompletionId,
  openaiErrors,
  parseOpenAIRequest,
  renderCompletion,
  renderModel,
  renderModels,
} from '../../src/protocol/openai.js';

describe('parseOpenAIRequest', () => {
  it('标准请求：system 抽为顶层、user/assistant 保留、忽略额外字段', () => {
    const r = parseOpenAIRequest({
      model: 'gpt-x',
      temperature: 0.7,
      n: 3,
      messages: [
        { role: 'system', content: '你是助手' },
        { role: 'user', content: '你好' },
        { role: 'assistant', content: '你好呀' },
        { role: 'user', content: '继续' },
      ],
    });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.value.prompt.system).toBe('你是助手');
    expect(r.value.prompt.model).toBe('gpt-x');
    expect(r.value.prompt.messages).toEqual([
      { role: 'user', content: '你好' },
      { role: 'assistant', content: '你好呀' },
      { role: 'user', content: '继续' },
    ]);
    expect(r.value.stream).toBe(false);
    expect(r.value.includeUsage).toBe(false);
  });

  it('content 部件数组：text 拼接、非 text 占位', () => {
    const r = parseOpenAIRequest({
      messages: [
        {
          role: 'user',
          content: [
            { type: 'text', text: '看这张图' },
            { type: 'image_url', image_url: { url: 'http://x/y.png' } },
            { type: 'text', text: '谢谢' },
          ],
        },
      ],
    });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.value.prompt.messages[0]!.content).toBe('看这张图[不支持的部件: image_url]谢谢');
  });

  it('role tool/function 折算为 user；仅有 tool_calls 时占位', () => {
    const r = parseOpenAIRequest({
      messages: [
        { role: 'user', content: '查天气' },
        { role: 'assistant', content: null, tool_calls: [{ id: 'c1', function: { name: 'weather' } }] },
        { role: 'tool', content: '上海 26 度' },
      ],
    });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.value.prompt.messages).toEqual([
      { role: 'user', content: '查天气' },
      { role: 'assistant', content: '[不支持的部件: tool_calls]' },
      { role: 'user', content: '上海 26 度' },
    ]);
  });

  it('多段 system 合并；stream 与 include_usage 透传', () => {
    const r = parseOpenAIRequest({
      stream: true,
      stream_options: { include_usage: true },
      messages: [
        { role: 'system', content: 'A' },
        { role: 'developer', content: 'B' },
        { role: 'user', content: 'hi' },
      ],
    });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.value.prompt.system).toBe('A\n\nB');
    expect(r.value.stream).toBe(true);
    expect(r.value.includeUsage).toBe(true);
  });

  it('非法请求返回 ok:false', () => {
    expect(parseOpenAIRequest(null).ok).toBe(false);
    expect(parseOpenAIRequest('x').ok).toBe(false);
    expect(parseOpenAIRequest({}).ok).toBe(false);
    expect(parseOpenAIRequest({ messages: [] }).ok).toBe(false);
    expect(parseOpenAIRequest({ messages: 'x' }).ok).toBe(false);
    expect(parseOpenAIRequest({ messages: [{ role: 'hacker', content: 'x' }] }).ok).toBe(false);
  });
});

describe('renderCompletion（非流式）', () => {
  it('响应结构与设计 §4.2 逐字段一致', () => {
    const body = renderCompletion({
      id: 'chatcmpl-test',
      created: 1730000000,
      model: 'gpt-x',
      content: '你好，我是人工回复',
      promptTokens: 12,
      completionTokens: 34,
      includeUsage: false,
    });
    expect(body).toEqual({
      id: 'chatcmpl-test',
      object: 'chat.completion',
      created: 1730000000,
      model: 'gpt-x',
      choices: [
        {
          index: 0,
          message: { role: 'assistant', content: '你好，我是人工回复' },
          finish_reason: 'stop',
        },
      ],
      usage: { prompt_tokens: 12, completion_tokens: 34, total_tokens: 46 },
    });
  });
});

describe('buildCompletionFrames（流式）', () => {
  const params = {
    id: 'chatcmpl-test',
    created: 1730000000,
    model: 'gpt-x',
    content: '回复全文',
    promptTokens: 12,
    completionTokens: 34,
    includeUsage: true,
  };

  function parseFrames(frames: string[]): unknown[] {
    return frames.map((f) => JSON.parse(f.replace(/^data: /, '').trim()));
  }

  it('事件序列与设计 §4.2 一致（含 usage 块与 [DONE]）', () => {
    const frames = buildCompletionFrames(params);
    expect(frames.at(-1)).toBe('data: [DONE]\n\n');

    const parsed = parseFrames(frames.slice(0, -1));
    expect(parsed).toEqual([
      {
        id: 'chatcmpl-test',
        object: 'chat.completion.chunk',
        created: 1730000000,
        model: 'gpt-x',
        choices: [{ index: 0, delta: { role: 'assistant', content: '' }, finish_reason: null }],
      },
      {
        id: 'chatcmpl-test',
        object: 'chat.completion.chunk',
        created: 1730000000,
        model: 'gpt-x',
        choices: [{ index: 0, delta: { content: '回复全文' }, finish_reason: null }],
      },
      {
        id: 'chatcmpl-test',
        object: 'chat.completion.chunk',
        created: 1730000000,
        model: 'gpt-x',
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      },
      {
        id: 'chatcmpl-test',
        object: 'chat.completion.chunk',
        created: 1730000000,
        model: 'gpt-x',
        choices: [],
        usage: { prompt_tokens: 12, completion_tokens: 34, total_tokens: 46 },
      },
    ]);
  });

  it('不含 usage 时 finish 后直接 [DONE]', () => {
    const frames = buildCompletionFrames({ ...params, includeUsage: false });
    expect(frames).toHaveLength(4);
    expect(frames.at(-1)).toBe('data: [DONE]\n\n');
    const last = JSON.parse(frames[2]!.replace(/^data: /, '').trim());
    expect(last.choices[0].finish_reason).toBe('stop');
  });
});

describe('模型列表与错误体', () => {
  it('renderModels / renderModel', () => {
    expect(renderModels('chatshy', 1730000000)).toEqual({
      object: 'list',
      data: [{ id: 'chatshy', object: 'model', created: 1730000000, owned_by: 'chatshy' }],
    });
    expect(renderModel('chatshy', 1)).toEqual({ id: 'chatshy', object: 'model', created: 1, owned_by: 'chatshy' });
  });

  it('错误体形状', () => {
    expect(openaiErrors.invalidKey()).toEqual({
      error: { message: 'Incorrect API key provided', type: 'invalid_request_error', param: null, code: 'invalid_api_key' },
    });
    expect(openaiErrors.timeout()).toEqual({
      error: { message: 'The model timed out while generating a response.', type: 'server_error', param: null, code: 'timeout' },
    });
    expect(openaiErrors.badRequest('boom')).toEqual({
      error: { message: 'boom', type: 'invalid_request_error', param: null, code: null },
    });
  });

  it('id 生成带 chatcmpl- 前缀', () => {
    expect(newCompletionId()).toMatch(/^chatcmpl-[0-9a-f]{24}$/);
  });
});
