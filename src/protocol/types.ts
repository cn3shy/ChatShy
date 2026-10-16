export type Protocol = 'openai' | 'anthropic';

export interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
}

/** 内部统一模型：协议适配层负责"协议形状 ↔ 此模型"的翻译 */
export interface Prompt {
  model: string;
  system?: string;
  messages: ChatMsg[];
}

export interface Reply {
  content: string;
}

export type ParseResult<T> = { ok: true; value: T } | { ok: false; message: string };

const CJK_RE = /[　-〿぀-ヿ㐀-䶿一-鿿豈-﫿＀-￯]/g;

/** 粗略估算 token 数：CJK 1 字 ≈ 1 token，其余 4 字符 ≈ 1 token */
export function estimateTokens(text: string): number {
  const cjk = (text.match(CJK_RE) ?? []).length;
  return cjk + Math.ceil((text.length - cjk) / 4);
}
