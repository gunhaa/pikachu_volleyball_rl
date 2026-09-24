"""리플레이 형식 v1 읽기 · 기록 싱크. (Phase 4 FR-6, FR-7 / plan.md §3, §7.2)

─────────────────────────────────────────────────────────────────────────────
여기서 하지 않는 것
─────────────────────────────────────────────────────────────────────────────
재생(물리)은 하지 않는다. 재생의 정답은 Kotlin `ReplayPlayer` 와 JS `GameRunner` 이고,
적재 전에 `analysis ingest` 가 재생으로 검증한다. Python 은 **헤더와 결과**만 읽는다 —
평가 리포트와 리플레이가 같은 게임을 가리키는지 대조하는 데 그것으로 충분하다.

─────────────────────────────────────────────────────────────────────────────
행 ↔ envIndex (plan.md §7.2 ⚠️)
─────────────────────────────────────────────────────────────────────────────
`PikaVectorEnv` 는 슬롯을 펼친다: 행 ``i * slot_count + k`` ↔ 서버 환경 ``i`` 의 슬롯 ``k``.
리플레이는 서버 환경 단위이므로 ``env_index = row // slot_count`` 다. Track A 는 슬롯 1개라
행 = 환경이다. 진영은 리플레이의 슬롯 플래그가 말해 주므로 manifest 에 따로 적지 않는다.
"""

from __future__ import annotations

import hashlib
import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Any

MAGIC = b"PKRP"
VERSION = 1

#: 랠리 결과 코드 (0/1 은 득점자).
TRUNCATED = -1
UNFINISHED = -2


@dataclass(frozen=True)
class ReplayHeader:
    """리플레이의 설정 · 결과. 입력 바이트는 담지 않는다."""

    p1_external: bool
    p2_external: bool
    first_serve_is_player2: bool
    ended: bool
    edge_trigger: bool
    seed_mode: int
    winning_score: int
    fixed_boldness: tuple[int, int]
    max_rally_frames: int
    seeds: tuple[int, ...]
    rally_frames: tuple[int, ...]
    rally_outcomes: tuple[int, ...]
    final_score: tuple[int, int]
    frame_count: int

    @property
    def winner(self) -> int | None:
        if not self.ended:
            return None
        return 0 if self.final_score[0] >= self.winning_score else 1

    @property
    def external_sides(self) -> list[int]:
        return [s for s, ext in enumerate((self.p1_external, self.p2_external)) if ext]


def parse_replay(data: bytes) -> ReplayHeader:
    """v1 바이트 → 헤더. 형식이 틀리면 ValueError — 알 수 없는 버전을 대충 읽지 않는다."""
    if data[:4] != MAGIC:
        raise ValueError("리플레이가 아닙니다 (magic 불일치)")
    if data[4] != VERSION:
        raise ValueError(f"알 수 없는 리플레이 버전: {data[4]}")
    flags, seed_mode, winning = data[5], data[6], data[7]
    b1, b2 = struct.unpack_from("<bb", data, 8)
    max_rally_frames, rally_count = struct.unpack_from("<iI", data, 10)
    p = 18
    seed_count = 1 if seed_mode == 0 else rally_count
    seeds = struct.unpack_from(f"<{seed_count}i", data, p)
    p += 4 * seed_count
    frames, outcomes = [], []
    for _ in range(rally_count):
        f, o = struct.unpack_from("<ib", data, p)
        frames.append(f)
        outcomes.append(o)
        p += 5
    s1, s2 = data[p], data[p + 1]
    (frame_count,) = struct.unpack_from("<I", data, p + 2)
    p += 6
    external = bin(flags & 3).count("1")
    if len(data) - p != frame_count * external:
        raise ValueError(f"입력 바이트 수가 맞지 않습니다: 남은 {len(data) - p}, 기대 {frame_count * external}")
    return ReplayHeader(
        p1_external=bool(flags & 1),
        p2_external=bool(flags & 2),
        first_serve_is_player2=bool(flags & 4),
        ended=bool(flags & 8),
        edge_trigger=bool(flags & 16),
        seed_mode=seed_mode,
        winning_score=winning,
        fixed_boldness=(b1, b2),
        max_rally_frames=max_rally_frames,
        seeds=tuple(seeds),
        rally_frames=tuple(frames),
        rally_outcomes=tuple(outcomes),
        final_score=(s1, s2),
        frame_count=frame_count,
    )


def sha256_file(path: str | Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class ReplaySink:
    """평가가 **센** 게임의 리플레이만 파일로 남긴다. (FR-6, plan.md §7.2)

    `evaluate_policy` 가 게임을 셀 때 :meth:`mark` 를 부르고, 서버에서 꺼낸 게임을
    :meth:`offer` 로 넘긴다. 짝은 ``(env_index, game_in_env)`` 로 맞춘다.

    ⚠️ **꺼낸 시점에 판정이 끝나 있다.** 서버는 게임이 끝나거나 잘린 바로 그 스텝에 큐에
       넣고, 평가 루프는 그 스텝의 응답을 처리(=mark)한 **뒤에** 꺼낸다. 그래서 offer 시점에
       mark 되지 않은 게임은 세지 않은 게임(할당량을 채운 행이 계속 돈 게임)이고, 버린다.

    파일: ``<dir>/e<env>-g<game>.pkr`` + ``<dir>/manifest.jsonl`` (한 게임 한 줄).
    """

    def __init__(
        self,
        out_dir: str | Path,
        set_name: str,
        policy: dict[str, Any],
        opponent: dict[str, Any] | None = None,
    ) -> None:
        self.dir = Path(out_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.set_name = set_name
        self.policy = policy
        self.opponent = opponent or {"kind": "fsm"}
        self._marked: dict[tuple[int, int], bool] = {}  # (env, game) → unresolved
        self._written: set[tuple[int, int]] = set()
        self.dropped = 0
        self._manifest = self.dir / "manifest.jsonl"
        self._manifest.write_text("", encoding="utf-8")

    def mark(self, env_index: int, game_in_env: int, *, unresolved: bool) -> None:
        """이 게임을 집계에 넣었다. Track B 는 한 게임을 두 행이 함께 센다 — 같은 키다."""
        key = (env_index, game_in_env)
        prev = self._marked.get(key)
        if prev is not None and prev != unresolved:
            raise RuntimeError(f"게임 {key} 을 미결과 완료로 동시에 셌습니다")
        self._marked[key] = unresolved

    def offer(self, games: list[tuple[int, int, bytes]]) -> None:
        for env_index, game_in_env, data in games:
            key = (env_index, game_in_env)
            if key not in self._marked:
                self.dropped += 1
                continue
            if key in self._written:
                raise RuntimeError(f"게임 {key} 의 리플레이가 두 번 왔습니다")
            unresolved = self._marked[key]
            header = parse_replay(data)
            if header.ended == unresolved:
                raise RuntimeError(
                    f"게임 {key}: 평가는 {'미결' if unresolved else '완료'} 로 셌는데 "
                    f"리플레이는 ended={header.ended} 입니다 — 기록 상한과 max_game_frames 가 다릅니까?",
                )
            name = f"e{env_index:03d}-g{game_in_env:04d}.pkr"
            (self.dir / name).write_bytes(data)
            sides = [self.policy if ext else self.opponent for ext in (header.p1_external, header.p2_external)]
            line = {
                "file": name, "set": self.set_name, "envIndex": env_index, "gameInEnv": game_in_env,
                "p1": sides[0], "p2": sides[1], "counted": True, "unresolved": unresolved,
            }
            with self._manifest.open("a", encoding="utf-8") as f:
                f.write(json.dumps(line, ensure_ascii=False) + "\n")
            self._written.add(key)

    def close(self) -> int:
        """센 게임마다 리플레이가 정확히 하나 있는지 확인한다. 쓴 파일 수를 돌려준다."""
        missing = sorted(set(self._marked) - self._written)
        if missing:
            raise RuntimeError(f"센 게임 {len(missing)} 개의 리플레이가 없습니다: {missing[:5]} …")
        return len(self._written)


def read_manifest(out_dir: str | Path) -> list[dict[str, Any]]:
    lines = (Path(out_dir) / "manifest.jsonl").read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]
