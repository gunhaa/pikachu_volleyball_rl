/** 아주 작은 DOM 도우미. */
'use strict';

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (v === true) node.setAttribute(k, '');
    else if (v !== false && v != null) node.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null) node.append(c instanceof Node ? c : String(c));
  return node;
}

export function participantTag(p) {
  const name = p.kind === 'fsm' ? 'FSM' : p.kind === 'human' ? '사람' : (p.label || '정책');
  return el('span', { class: `tag ${p.kind}` }, name);
}
