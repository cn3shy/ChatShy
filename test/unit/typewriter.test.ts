import { describe, expect, it } from 'vitest';
import { typewriterChunks } from '../../src/server/serve.js';

/** 用码点显式构造，免得源码里的 emoji 字面量在编辑器/版本控制环节被拆散 */
const ZWJ = String.fromCodePoint(0x200d);
const FAMILY = String.fromCodePoint(0x1f468, 0x200d, 0x1f469, 0x200d, 0x1f467); // 一家三口，一个 ZWJ 字素
const FLAG = String.fromCodePoint(0x1f1e8, 0x1f1f3); // 区域指示符对，同样是一个字素
const COMBINING = String.fromCodePoint(0x65, 0x301); // e + 组合尖音符

describe('typewriterChunks（把整段回复切成逐帧下发的小片）', () => {
  it('拼回去与原文逐字相同', () => {
    const text = '这是一段中文回复，mixed with English 123 与符号 🎉！';
    expect(typewriterChunks(text).join('')).toBe(text);
  });

  it('空串不产生任何帧', () => {
    expect(typewriterChunks('')).toEqual([]);
  });

  it('短回复切成多片（否则打字机效果无从谈起）', () => {
    const chunks = typewriterChunks('六个中文字');
    expect(chunks.length).toBeGreaterThan(1);
    expect(chunks.join('')).toBe('六个中文字');
  });

  it('按字素切：不会把 ZWJ 序列或组合字符从中间切开', () => {
    const text = FAMILY + FLAG + COMBINING;
    const chunks = typewriterChunks(text);
    expect(chunks.join('')).toBe(text);

    // 被切开的标志：某一帧以 ZWJ 结尾，或以下一半（区域指示符/组合记号）开头
    for (const chunk of chunks) {
      expect(chunk.endsWith(ZWJ)).toBe(false);
      expect(chunk.startsWith(String.fromCodePoint(0x1f1e8)) && chunk.length === 1).toBe(false);
    }
    // 一家三口必须整块落在同一帧里
    expect(chunks.some((c) => c.includes(FAMILY))).toBe(true);
  });

  it('长回复靠加大每帧粒度压住帧数（帧数失控会拖垮总时长）', () => {
    const long = '字'.repeat(5000);
    const chunks = typewriterChunks(long);
    expect(chunks.length).toBeLessThanOrEqual(400);
    expect(chunks.join('')).toBe(long);
  });
});
