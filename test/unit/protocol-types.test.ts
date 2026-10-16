import { describe, expect, it } from 'vitest';
import { estimateTokens } from '../../src/protocol/types.js';

describe('estimateTokens', () => {
  it('CJK 1 字 ≈ 1 token', () => {
    expect(estimateTokens('你好世界')).toBe(4);
  });

  it('其余 4 字符 ≈ 1 token，向上取整', () => {
    expect(estimateTokens('hello world')).toBe(3); // 11 字符 → ceil(11/4)
    expect(estimateTokens('abcd')).toBe(1);
    expect(estimateTokens('abcde')).toBe(2);
  });

  it('中英混合与空串', () => {
    expect(estimateTokens('你好abcd')).toBe(3);
    expect(estimateTokens('')).toBe(0);
  });
});
