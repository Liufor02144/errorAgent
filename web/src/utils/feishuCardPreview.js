/**
 * 飞书卡片 JSON → HTML 近似预览。
 *
 * 只实现巡检卡片实际用到的元素：markdown、column_set、collapsible_panel、hr、button。
 * 卡片内容来自生产日志（报错原文、代码片段），必须先整体转义，
 * 再只放回 markdown 里用到的那几种格式，否则日志里的 <script> 会被当成页面代码执行。
 */

const HEADER_COLORS = {
  red: '#f54a45', orange: '#ff8800', yellow: '#ffc60a', green: '#34c724',
  blue: '#3370ff', wathet: '#14c0ff', turquoise: '#00d6b9', grey: '#8f959e',
  indigo: '#4e83fd', purple: '#7f3bf5', violet: '#a35bf6', carmine: '#e1336c'
};

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function safeUrl(u) {
  return /^https?:\/\//i.test(u) ? u : '#';
}

/** 卡片 markdown 的子集：代码块、行内代码、加粗、链接、<font color> */
function md(src) {
  const blocks = [];
  // 代码块先摘出来，里面的内容一个字符都不能被后面的规则改动
  let s = String(src == null ? '' : src).replace(/```(\w*)\n?([\s\S]*?)```/g, (m, lang, code) => {
    blocks.push('<pre class="fc-code">' + esc(code.replace(/\n$/, '')) + '</pre>');
    return '\uE000' + (blocks.length - 1) + '\uE000';
  });
  s = esc(s);
  s = s.replace(/&lt;font color=(?:'|&quot;)?([a-zA-Z#0-9-]+)(?:'|&quot;)?&gt;([\s\S]*?)&lt;\/font&gt;/g,
    (m, c, body) => `<span style="color:${HEADER_COLORS[c] || (/^#[0-9a-fA-F]{3,6}$/.test(c) ? c : '#8f959e')}">${body}</span>`);
  s = s.replace(/`([^`\n]+)`/g, '<code class="fc-inline">$1</code>');
  s = s.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g,
    (m, t, u) => `<a href="${esc(safeUrl(u.replace(/&amp;/g, '&')))}" target="_blank" rel="noopener">${t}</a>`);
  s = s.replace(/\n/g, '<br>');
  return s.replace(/\uE000(\d+)\uE000/g, (m, i) => blocks[Number(i)]);
}

function text(t) {
  if (!t) return '';
  if (typeof t === 'string') return esc(t);
  return t.tag === 'markdown' || t.tag === 'lark_md' ? md(t.content) : esc(t.content);
}

function elements(list) {
  return (list || []).map(element).join('');
}

function element(el) {
  if (!el || typeof el !== 'object') return '';
  switch (el.tag) {
    case 'markdown':
    case 'lark_md':
      return `<div class="fc-md" style="text-align:${esc(el.text_align || 'left')}">${md(el.content)}</div>`;
    case 'div':
      return `<div class="fc-md">${text(el.text)}</div>`;
    case 'hr':
      return '<hr class="fc-hr">';
    case 'column_set':
      return '<div class="fc-cols">' + (el.columns || []).map((c) =>
        `<div class="fc-col">${elements(c.elements)}</div>`).join('') + '</div>';
    case 'collapsible_panel': {
      const title = el.header && el.header.title ? text(el.header.title) : '展开';
      return `<details class="fc-panel"${el.expanded ? ' open' : ''}><summary>${title}</summary>`
        + `<div class="fc-panel-body">${elements(el.elements)}</div></details>`;
    }
    case 'button': {
      const b = (el.behaviors || []).find((x) => x.default_url) || {};
      const url = b.default_url || el.url || '#';
      return `<a class="fc-btn" href="${esc(safeUrl(url))}" target="_blank" rel="noopener">${text(el.text)}</a>`;
    }
    case 'action':
      return `<div class="fc-actions">${elements(el.actions)}</div>`;
    default:
      return el.elements ? elements(el.elements) : '';
  }
}

export function renderCard(json) {
  let card;
  try {
    card = typeof json === 'string' ? JSON.parse(json) : json;
  } catch (e) {
    return '<div class="fc-err">卡片 JSON 解析失败</div>';
  }
  const h = card.header || {};
  const color = HEADER_COLORS[h.template] || HEADER_COLORS.blue;
  const body = card.body ? card.body.elements : card.elements;
  return `
<style>
.fc{border:1px solid #dee0e3;border-radius:8px;overflow:hidden;font-size:14px;line-height:1.6;color:#1f2329;background:#fff}
.fc-head{padding:12px 16px;color:#fff}
.fc-title{font-weight:600;font-size:16px}
.fc-subtitle{font-size:12px;opacity:.9;margin-top:2px}
.fc-body{padding:12px 16px;display:flex;flex-direction:column;gap:8px}
.fc-cols{display:flex;gap:12px;flex-wrap:wrap}
.fc-col{flex:1 1 0;min-width:90px}
.fc-hr{border:none;border-top:1px solid #dee0e3;margin:4px 0}
.fc-code{background:#f5f6f7;border-radius:4px;padding:8px 10px;font-family:Menlo,Consolas,monospace;font-size:12px;white-space:pre-wrap;word-break:break-all;margin:4px 0}
.fc-inline{background:#f5f6f7;border-radius:3px;padding:0 4px;font-family:Menlo,Consolas,monospace;font-size:12px}
.fc-panel{border:1px solid #dee0e3;border-radius:6px;padding:6px 10px}
.fc-panel summary{cursor:pointer;font-weight:600}
.fc-panel-body{margin-top:6px}
.fc-btn{display:inline-block;border:1px solid #dee0e3;border-radius:6px;padding:4px 12px;color:#1f2329;text-decoration:none;margin-right:8px}
.fc-md{word-break:break-word}
</style>
<div class="fc">
  <div class="fc-head" style="background:${color}">
    <div class="fc-title">${text(h.title)}</div>
    ${h.subtitle ? `<div class="fc-subtitle">${text(h.subtitle)}</div>` : ''}
  </div>
  <div class="fc-body">${elements(body)}</div>
</div>`;
}
