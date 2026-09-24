/** 뷰어 API (`analysis serve`, Vite 가 /api 를 127.0.0.1:8081 로 프록시한다). */
'use strict';

async function json(res) {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `${res.status} ${res.statusText}`);
  return body;
}

export const api = {
  sets: () => fetch('/api/sets').then(json),
  games: (params) => fetch(`/api/games?${new URLSearchParams(params)}`).then(json),
  game: (id) => fetch(`/api/games/${id}`).then(json),
  replay: async (id) => {
    const res = await fetch(`/api/games/${id}/replay`);
    if (!res.ok) throw new Error(`리플레이 ${id}: ${res.status}`);
    return new Uint8Array(await res.arrayBuffer());
  },
  stats: (set, bin = 50) => fetch(`/api/stats/${encodeURIComponent(set)}?bin=${bin}`).then(json),
  submitLive: (bytes) =>
    fetch('/api/live-games', { method: 'POST', body: bytes, headers: { 'Content-Type': 'application/octet-stream' } }).then(json),
};
