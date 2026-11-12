import MarkdownIt from 'markdown-it';

/**
 * 极简 Markdown 渲染：围栏代码块、行内代码、粗体/斜体、链接、标题、
 * 有序/无序列表、引用。html:false，原始 HTML 一律转义，天然防 XSS。
 *
 * 不引入 highlight.js：旧版豆奶的代码块就是纯文本样式，保持 1:1 观感。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
});

const defaultLinkOpen = md.renderer.rules.link_open
  ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx]!.attrSet('target', '_blank');
  tokens[idx]!.attrSet('rel', 'noopener noreferrer');
  return defaultLinkOpen(tokens, idx, options, env, self);
};

md.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx]!;
  const info = (token.info || '').trim();
  const lang = info.split(/\s+/)[0] || '';
  const label = lang === '' ? '' : `<span class="code-lang">${md.utils.escapeHtml(lang)}</span>`;
  return `<pre>${label}<button class="code-copy" type="button">复制</button>`
    + `<code>${md.utils.escapeHtml(token.content)}</code></pre>`;
};

export function renderMarkdown(text: string): string {
  return md.render(text || '');
}
