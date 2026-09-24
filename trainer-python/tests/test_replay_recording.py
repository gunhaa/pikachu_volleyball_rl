"""평가의 리플레이 기록 — 센 게임만, 짝이 맞게. (Phase 4 P3, FR-6 / plan.md §7.2)

─────────────────────────────────────────────────────────────────────────────
짝이 맞는다는 것을 어떻게 보는가
─────────────────────────────────────────────────────────────────────────────
리플레이 헤더에는 최종 점수 · 랠리별 결과 · 프레임 수가 있다. 그것만으로 진영별 집계
(승 · 득실 · 랠리 · 랠리 승 · 프레임 · 잘린 랠리 · 미결)를 **다시 계산**해 평가 리포트와
정확히 같아야 한다. 행 ↔ envIndex 를 한 칸이라도 잘못 짝지으면 다른 게임의 숫자가 섞여
어긋난다 — M4-d 의 축소판이다.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from pika_trainer.env_client import EnvOptions, PikaVectorEnv
from pika_trainer.evaluate import evaluate_policy, evaluate_target, uniform_random_policy
from pika_trainer.replay import ReplaySink, parse_replay, read_manifest


def _stats_from_replays(out_dir: Path) -> dict[int, dict[str, int]]:
    """리플레이만으로 `SideStats` 를 다시 계산한다 (정책 = External 슬롯)."""
    acc = {
        side: dict(games=0, wins=0, unresolved=0, points_for=0, points_against=0,
                   rallies=0, rally_wins=0, frames=0, truncated_rallies=0)
        for side in (0, 1)
    }
    for line in read_manifest(out_dir):
        h = parse_replay((out_dir / line["file"]).read_bytes())
        for side in h.external_sides:
            a = acc[side]
            if line["unresolved"]:
                a["unresolved"] += 1
                continue
            a["games"] += 1
            a["wins"] += int(h.winner == side)
            a["points_for"] += h.final_score[side]
            a["points_against"] += h.final_score[1 - side]
            a["rallies"] += len(h.rally_frames)
            a["rally_wins"] += sum(1 for o in h.rally_outcomes if o == side)
            a["frames"] += h.frame_count
            a["truncated_rallies"] += sum(1 for o in h.rally_outcomes if o == -1)
    return acc


def _run(env_target: str, out: Path | None, **kw):
    return evaluate_target(
        env_target, lambda _env: uniform_random_policy(seed=3),
        games_per_side=12, num_envs=10, base_seed=5, mode="random",
        record_dir=out, set_name="test-set", **kw,
    )


def test_recording_does_not_change_the_report(env_target: str, tmp_path: Path):
    """기록을 켠 평가의 리포트 = 끈 평가의 리포트 (`to_dict()` ==). NFR-1 의 평가판."""
    off = _run(env_target, None)
    on = _run(env_target, tmp_path / "rec")
    assert on.to_dict() == off.to_dict()


def test_replays_reproduce_the_report_exactly(env_target: str, tmp_path: Path):
    """리플레이에서 다시 계산한 진영별 집계 = 리포트. 파일 수 = 센 게임 수."""
    out = tmp_path / "rec"
    report = _run(env_target, out, participant={"kind": "external", "checkpoint": "sha256:ab", "label": "t"})
    manifest = read_manifest(out)
    counted = report.as_left.games + report.as_right.games + report.unresolved
    assert len(manifest) == counted
    assert len(list(out.glob("*.pkr"))) == counted

    stats = _stats_from_replays(out)
    for side, got in ((0, report.as_left), (1, report.as_right)):
        want = {k: getattr(got, k) for k in stats[side]}
        assert stats[side] == want, f"진영 {side}"

    # manifest 의 모양 (plan.md §3.2)
    line = manifest[0]
    assert line["set"] == "test-set" and line["counted"] is True
    assert {line["p1"]["kind"], line["p2"]["kind"]} == {"external", "fsm"}
    ext = line["p1"] if line["p1"]["kind"] == "external" else line["p2"]
    assert ext["checkpoint"] == "sha256:ab"


def test_games_beyond_quota_are_dropped(env_target: str, tmp_path: Path):
    """할당량을 채운 행이 계속 돌며 끝낸 게임은 쓰지 않는다. 행 번호 = envIndex (Track A)."""
    options = EnvOptions.for_policy(num_envs=10, base_seed=5, record_replays=True)
    env = PikaVectorEnv(env_target, options)
    sink = ReplaySink(tmp_path, "t", {"kind": "external"})
    try:
        report = evaluate_policy(env, uniform_random_policy(seed=3), games_per_side=12, recorder=sink)
    finally:
        env.close()
    assert sink.dropped > 0, "할당량을 넘긴 게임이 한 판도 없었다 — 이 테스트가 아무것도 재지 않는다"
    manifest = read_manifest(tmp_path)
    assert len(manifest) == report.as_left.games + report.as_right.games
    # 뒤쪽 절반(swapped_envs)의 환경은 정책이 오른쪽이다 — 슬롯 플래그가 그것을 말해야 한다.
    for line in manifest:
        h = parse_replay((tmp_path / line["file"]).read_bytes())
        expected_side = 1 if line["envIndex"] >= options.num_envs - options.swapped_envs else 0
        assert h.external_sides == [expected_side], line
    # 행마다 센 게임 번호는 0 부터 빈틈없이 이어진다.
    by_env: dict[int, list[int]] = {}
    for line in manifest:
        by_env.setdefault(line["envIndex"], []).append(line["gameInEnv"])
    for env_index, games in by_env.items():
        assert sorted(games) == list(range(len(games))), env_index


def test_unresolved_games_are_written_as_cut_replays(env_target: str, tmp_path: Path):
    """미결 게임은 `unresolved = true`, 리플레이는 상한에서 잘린 `ended = false`."""
    out = tmp_path / "rec"
    report = evaluate_target(
        env_target, lambda _env: uniform_random_policy(seed=0),
        games_per_side=4, num_envs=8, base_seed=0, mode="random",
        max_game_frames=500, record_dir=out,
    )
    assert report.unresolved == 8
    manifest = read_manifest(out)
    assert len(manifest) == 8 and all(line["unresolved"] for line in manifest)
    for line in manifest:
        h = parse_replay((out / line["file"]).read_bytes())
        assert not h.ended and h.frame_count == 500 and h.rally_outcomes[-1] == -2


def test_cap_must_match_max_game_frames(env_target: str, tmp_path: Path):
    env = PikaVectorEnv(env_target, EnvOptions.for_policy(num_envs=4, record_replays=True, replay_frame_cap=900))
    try:
        with pytest.raises(ValueError, match="replay_frame_cap"):
            evaluate_policy(env, uniform_random_policy(), games_per_side=2,
                            recorder=ReplaySink(tmp_path, "t", {"kind": "external"}))
    finally:
        env.close()


def test_track_b_rows_share_one_replay(env_target: str, tmp_path: Path):
    """External vs External: 행 두 개가 한 서버 환경이다 (env_index = row // 2). 게임당 파일 하나."""
    env = PikaVectorEnv(env_target, EnvOptions.for_policy(num_envs=4, p2="external", record_replays=True))
    sink = ReplaySink(tmp_path, "t", {"kind": "external"})
    try:
        evaluate_policy(env, uniform_random_policy(seed=1), games_per_side=6, recorder=sink)
    finally:
        env.close()
    manifest = read_manifest(tmp_path)
    keys = [(line["envIndex"], line["gameInEnv"]) for line in manifest]
    assert len(keys) == len(set(keys)) > 0
    assert all(line["envIndex"] < 4 for line in manifest)
    assert all(line["p1"]["kind"] == line["p2"]["kind"] == "external" for line in manifest)


def test_fetch_is_empty_without_recording(env_target: str):
    env = PikaVectorEnv(env_target, EnvOptions.for_policy(num_envs=2))
    try:
        env.reset()
        for _ in range(3000):
            env.step(uniform_random_policy()(env._obs))
        assert env.fetch_replays() == []
    finally:
        env.close()
