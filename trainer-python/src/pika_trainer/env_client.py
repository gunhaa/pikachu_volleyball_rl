"""Kotlin 엔진에 붙는 Gymnasium 벡터 환경. (FR-12, M2-c)

─────────────────────────────────────────────────────────────────────────────
슬롯을 어떻게 Gymnasium 에 맞추는가
─────────────────────────────────────────────────────────────────────────────
서버의 환경 하나에는 외부 정책 슬롯이 1개(Track A) 또는 2개(Track B) 있다.
Gymnasium 의 `VectorEnv` 는 "환경 하나 = 에이전트 하나" 를 가정하므로 **슬롯을 펼친다**:

    num_envs(Gymnasium) = 서버 환경 수 × 슬롯 수
    관측 인덱스 i*slots + k  ↔  서버 환경 i 의 슬롯 k

⚠️ 그래서 Track B 에서는 **이웃한 두 줄이 서로의 상대**다. 독립 표본이 아니다.
   PPO 는 그대로 돌아가지만, 배치 안의 상관을 모르고 분산을 해석하면 틀린 결론이 나온다.
   (같은 랠리를 양쪽에서 본 것이므로 terminated/truncated 도 짝을 이뤄 켜진다.)

─────────────────────────────────────────────────────────────────────────────
오토리셋은 next-step 이다 (Gymnasium 1.x)
─────────────────────────────────────────────────────────────────────────────
`terminated=True` 인 스텝은 **랠리의 마지막 관측**을 준다. 리셋은 **다음** step 호출
시작 시점에 일어나고, 그 호출의 행동은 버려지며 보상은 0 이다. 서버가 그렇게 구현되어
있고, 이 클래스는 그것을 **그대로 전달**한다 (여기서 다시 리셋하지 않는다).

─────────────────────────────────────────────────────────────────────────────
예산 (plan.md §7.4)
─────────────────────────────────────────────────────────────────────────────
- 채널은 **한 번만** 만든다.
- 응답은 `np.frombuffer` 로 읽고 **미리 잡아 둔 버퍼에 한 번만 복사**한다.
  리스트로 풀면(`list(reply.observations)`) 그 자리에서 예산이 날아간다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import grpc
import numpy as np
from gymnasium import spaces
from gymnasium.vector import AutoresetMode, VectorEnv

from .obs_spec import ObsOptions, ObsSpec
from .pb import env_pb2, env_pb2_grpc

#: 행동 공간의 크기. 서버가 `Configure` 응답으로 확인해 준다.
ACTION_COUNT = 18

#: **학습기와 평가기가 함께 쓰는** 진영 플래그 설정 (FR-14, plan.md §4.5).
#:
#: 미러링은 오른쪽 슬롯의 관측을 왼쪽 시점으로 뒤집지만, 이 게임은 좌우 대칭이 아니다
#: (PRD §2.4 — 뒷벽이 20px 다르다). 플래그를 켜면 정책이 두 진영의 중간값으로 헤지하는
#: 대신 진영별 위치 선정을 낼 수 있다. 관측은 40 → 41차원이 된다.
#:
#: ⚠️ Track A(Phase 3)와 Track B(Phase 7)가 **같은 값**을 써야 Phase 8 의 동일 예산 비교가
#:    성립한다. 어긋나면 레이아웃 해시 대조가 붙는 즉시 실패시키지만, 애초에 갈라지지
#:    않도록 러너들은 :meth:`EnvOptions.for_policy` 를 통해서만 구성을 만든다.
OBS_INCLUDE_SIDE_FLAG = True


@dataclass
class EnvOptions:
    """`Configure` 로 보낼 구성. 기본값은 서버의 기본값과 같다."""

    num_envs: int = 8
    base_seed: int = 0
    #: "external" 또는 "fsm"
    p1: str = "external"
    p2: str = "fsm"
    winning_score: int = 15
    max_rally_frames: int = 3000
    obs_include_expected_landing: bool = True
    obs_include_side_flag: bool = False
    mirror_observations: bool = True
    edge_trigger_power_hit: bool = True
    #: 뒤쪽 이만큼의 환경은 p1/p2 를 뒤바꿔 쓴다 (FR-3, plan.md §4). 0 이면 전부 (p1, p2).
    swapped_envs: int = 0
    #: FSM 의 boldness 고정값. -1 이면 매 랠리 추첨(원작). 진단용이다 (FR-13).
    fixed_boldness: int = -1
    #: 항의 부호는 항 안에 있다. 가중치는 크기다 (plan.md §6.1).
    reward_weights: dict[str, float] = field(default_factory=lambda: {"rally_win": 1.0})
    #: 끝난 게임을 리플레이로 기록한다 (Phase 4 FR-6). 꺼져 있으면 동작이 기록 기능 전과 같다.
    record_replays: bool = False
    #: 기록 상한. ⚠️ evaluate 의 ``max_game_frames`` 와 같아야 미결 게임과 잘린 리플레이가 짝이 맞는다.
    replay_frame_cap: int = 60_000

    def obs_options(self) -> ObsOptions:
        return ObsOptions(
            include_expected_landing=self.obs_include_expected_landing,
            include_side_flag=self.obs_include_side_flag,
        )

    @classmethod
    def for_policy(cls, **kwargs: Any) -> EnvOptions:
        """학습기·평가기 공용 구성 (FR-3, FR-14).

        두 가지를 **기본으로** 준다:

        - 진영 플래그 on (:data:`OBS_INCLUDE_SIDE_FLAG`)
        - `swapped_envs` 를 주지 않으면 `num_envs // 2` — 벡터의 절반이 오른쪽 진영이다

        러너가 직접 :class:`EnvOptions` 를 만들면 이 둘을 빠뜨릴 수 있고, 그 사고는
        승률이 이상해지기 전까지 드러나지 않는다. 그래서 구성 생성을 한 곳으로 모은다.
        """
        kwargs.setdefault("obs_include_side_flag", OBS_INCLUDE_SIDE_FLAG)
        options = cls(**kwargs)
        if "swapped_envs" not in kwargs:
            options.swapped_envs = options.num_envs // 2
        return options


_SLOT_KIND = {
    "external": env_pb2.SLOT_KIND_EXTERNAL,
    "fsm": env_pb2.SLOT_KIND_FSM,
}


def _external_sides(p1: str, p2: str) -> list[int]:
    """`(p1, p2)` 구성에서 외부 슬롯이 차지하는 진영 번호. 슬롯 순서 그대로다."""
    return [side for side, kind in enumerate((p1, p2)) if kind == "external"]


class PikaVectorEnv(VectorEnv):
    """Kotlin gRPC 서버에 붙는 `gymnasium.vector.VectorEnv`."""

    metadata = {"autoreset_mode": AutoresetMode.NEXT_STEP}

    def __init__(
        self,
        target: str,
        options: EnvOptions | None = None,
        *,
        spec_path: str | None = None,
        verify_layout: bool = True,
    ) -> None:
        """
        Args:
            target: gRPC 대상. UDS 는 ``unix:///tmp/pika-env.sock``, TCP 는 ``127.0.0.1:50051``.
            options: 서버 구성.
            verify_layout: 서버의 관측 레이아웃 해시를 우리 `obs_spec.proto` 와 대조한다.
                끄지 말 것 — 어긋난 서버에 붙으면 관측이 **조용히** 뒤섞인다.
        """
        super().__init__()
        self.options = options or EnvOptions()

        # ⚠️ 채널은 여기서 한 번만 만든다 (plan.md §7.4).
        self._channel = grpc.insecure_channel(
            target,
            options=[
                ("grpc.max_receive_message_length", 64 * 1024 * 1024),
                ("grpc.max_send_message_length", 64 * 1024 * 1024),
            ],
        )
        self._stub = env_pb2_grpc.PikaEnvStub(self._channel)

        configure = self._configure(self.options)
        self.server_num_envs: int = configure.num_envs
        self.slot_count: int = configure.slot_count
        self.obs_dim: int = configure.obs_dim
        self.obs_field_names: list[str] = list(configure.obs_field_names)
        self.reward_term_names: list[str] = list(configure.reward_term_names)
        self.server_layout_hash: str = configure.obs_layout_hash
        #: 이 서버는 단일 테넌트다. 다른 클라이언트가 Configure 하면 이 번호가 낡고,
        #: 그때 Step 은 **조용히 이상한 응답을 주는 대신** FAILED_PRECONDITION 으로 실패한다.
        self.session_id: int = configure.session_id

        if configure.action_count != ACTION_COUNT:
            raise RuntimeError(
                f"서버의 행동 공간이 {configure.action_count} 입니다. 클라이언트는 {ACTION_COUNT} 를 압니다.",
            )

        if verify_layout:
            self._verify_layout(spec_path)

        self.num_envs = self.server_num_envs * self.slot_count
        self.single_observation_space = spaces.Box(
            # ⚠️ 관측을 [-1, 1] 로 자르지 않는다 (obs_spec.proto 참고). 범위는 척도이지
            #    상한이 아니므로 공간도 무한대로 둔다. 좁게 선언하면 검사기가
            #    "정상적인 빠른 공" 을 규약 위반으로 신고한다.
            low=-np.inf,
            high=np.inf,
            shape=(self.obs_dim,),
            dtype=np.float32,
        )
        self.single_action_space = spaces.Discrete(ACTION_COUNT)
        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf, shape=(self.num_envs, self.obs_dim), dtype=np.float32,
        )
        self.action_space = spaces.MultiDiscrete([ACTION_COUNT] * self.num_envs)

        # 재사용 버퍼. 스텝마다 새로 잡지 않는다.
        self._obs = np.zeros((self.num_envs, self.obs_dim), dtype=np.float32)
        self._rewards = np.zeros(self.num_envs, dtype=np.float32)
        self._terminated = np.zeros(self.num_envs, dtype=bool)
        self._truncated = np.zeros(self.num_envs, dtype=bool)
        self._terms = np.zeros((self.num_envs, len(self.reward_term_names)), dtype=np.float32)
        self._scores = np.zeros((self.server_num_envs, 2), dtype=np.int32)

        # 슬롯이 어느 진영인가 — **환경마다 다르다** (plan.md §4.3).
        # 뒤쪽 swapped_envs 개는 서버가 (p2, p1) 로 구성했으므로 슬롯 0 이 player2 다.
        normal = _external_sides(self.options.p1, self.options.p2)
        swapped = _external_sides(self.options.p2, self.options.p1)
        swap_n = min(self.options.swapped_envs, self.server_num_envs)
        #: `(server_num_envs, slot_count)` — 환경 i 슬롯 k 가 보는 진영 (0 왼쪽, 1 오른쪽).
        self._slot_side = np.array(
            [normal] * (self.server_num_envs - swap_n) + [swapped] * swap_n, dtype=np.intp,
        ).reshape(self.server_num_envs, self.slot_count)

    # ── 구성 ────────────────────────────────────────────────────────────

    def _configure(self, options: EnvOptions) -> Any:
        weights = env_pb2.RewardWeights(
            rally_win=options.reward_weights.get("rally_win", 0.0),
            ball_touch=options.reward_weights.get("ball_touch", 0.0),
            crossed_net=options.reward_weights.get("crossed_net", 0.0),
            opponent_miss=options.reward_weights.get("opponent_miss", 0.0),
            time_penalty=options.reward_weights.get("time_penalty", 0.0),
        )
        request = env_pb2.ConfigureRequest(
            num_envs=options.num_envs,
            base_seed=options.base_seed,
            p1=_SLOT_KIND[options.p1],
            p2=_SLOT_KIND[options.p2],
            winning_score=options.winning_score,
            max_rally_frames=options.max_rally_frames,
            obs_include_expected_landing=options.obs_include_expected_landing,
            obs_include_side_flag=options.obs_include_side_flag,
            mirror_observations=options.mirror_observations,
            edge_trigger_power_hit=options.edge_trigger_power_hit,
            swapped_envs=options.swapped_envs,
            fixed_boldness=options.fixed_boldness,
            reward_weights=weights,
            record_replays=options.record_replays,
            replay_frame_cap=options.replay_frame_cap,
        )
        return self._stub.Configure(request)

    def _verify_layout(self, spec_path: str | None) -> None:
        """서버의 레이아웃 해시를 우리 `obs_spec.proto` 와 대조한다.

        어긋나면 **즉시** 실패한다. 관측이 뒤섞인 채로 학습이 돌면 손실 곡선은
        그럴듯하게 내려가고, 몇 시간 뒤에야 "왜 정책이 이상하지" 를 묻게 된다.
        """
        from pathlib import Path

        spec = ObsSpec.load(Path(spec_path) if spec_path else None)
        opts = self.options.obs_options()
        local_hash = spec.layout_hash(opts)
        if local_hash != self.server_layout_hash:
            raise RuntimeError(
                "관측 레이아웃이 서버와 다릅니다.\n"
                f"  서버      : {self.server_layout_hash}\n"
                f"  클라이언트: {local_hash}\n"
                f"  서버 필드 : {list(self.obs_field_names)}\n"
                f"  로컬 필드 : {spec.field_names(opts)}\n"
                "서버와 클라이언트가 같은 proto/obs_spec.proto 를 보고 있는지 확인하세요.",
            )
        if spec.field_names(opts) != list(self.obs_field_names):
            raise RuntimeError("레이아웃 해시는 같은데 필드 목록이 다릅니다. 해시 계산이 깨졌습니다.")

    # ── Gymnasium 규약 ──────────────────────────────────────────────────

    def reset(
        self,
        *,
        seed: int | None = None,
        options: dict[str, Any] | None = None,
    ) -> tuple[np.ndarray, dict[str, Any]]:
        """전부 처음 상태로.

        `seed` 를 주면 서버의 `base_seed` 로 내려간다 — 즉 **재현 키는 Python 의 RNG 가
        아니라 서버의 시드**다. 같은 seed 와 같은 행동 시퀀스면 관측 바이트가 완전히 같다.
        """
        super().reset(seed=seed)
        request = env_pb2.ResetRequest(session_id=self.session_id)
        if seed is not None:
            request.base_seed = int(np.int32(seed))
            self.options.base_seed = request.base_seed
        reply = self._stub.Reset(request)
        self._unpack(reply)
        return self._obs, self._build_info()

    def step(
        self, actions: np.ndarray,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, dict[str, Any]]:
        actions = np.asarray(actions)
        if actions.shape != (self.num_envs,):
            raise ValueError(f"행동 배열의 모양이 {actions.shape} 입니다. ({self.num_envs},) 이어야 합니다.")
        if actions.dtype != np.uint8:
            if np.any((actions < 0) | (actions >= ACTION_COUNT)):
                raise ValueError(f"행동은 0..{ACTION_COUNT - 1} 이어야 합니다.")
            actions = actions.astype(np.uint8)

        reply = self._stub.Step(
            env_pb2.StepRequest(actions=actions.tobytes(), session_id=self.session_id),
        )
        self._unpack(reply)
        return self._obs, self._rewards, self._terminated, self._truncated, self._build_info()

    def close_extras(self, **kwargs: Any) -> None:
        self._channel.close()

    # ── 응답 풀기 ───────────────────────────────────────────────────────

    def _unpack(self, reply: Any) -> None:
        """packed bytes → 미리 잡아 둔 배열. 복사는 배열당 한 번뿐이다."""
        np.copyto(
            self._obs,
            np.frombuffer(reply.observations, dtype="<f4").reshape(self.num_envs, self.obs_dim),
        )
        np.copyto(self._rewards, np.frombuffer(reply.rewards, dtype="<f4"))
        np.copyto(
            self._terms,
            np.frombuffer(reply.reward_terms, dtype="<f4").reshape(
                self.num_envs, len(self.reward_term_names),
            ),
        )
        np.copyto(self._scores, np.frombuffer(reply.scores, dtype="<i4").reshape(self.server_num_envs, 2))

        # terminated/truncated 는 서버 환경 단위다. 슬롯 수만큼 펼친다 —
        # 같은 랠리를 두 슬롯이 함께 끝내기 때문이다.
        per_env_term = np.frombuffer(reply.terminated, dtype=np.uint8).astype(bool)
        per_env_trunc = np.frombuffer(reply.truncated, dtype=np.uint8).astype(bool)
        np.copyto(self._terminated, np.repeat(per_env_term, self.slot_count))
        np.copyto(self._truncated, np.repeat(per_env_trunc, self.slot_count))

    def _build_info(self) -> dict[str, Any]:
        """항별 보상과 점수를 `info` 로 낸다.

        합계만 주면 Phase 3 의 어닐링이 "지금 보상의 몇 %가 셰이핑에서 왔는가" 에
        답할 수 없다 (FR-6).
        """
        info: dict[str, Any] = {
            name: self._terms[:, i] for i, name in enumerate(self.reward_term_names)
        }
        # 점수를 슬롯 시점으로 바꿔 준다 (내 점수가 먼저).
        # ⚠️ 진영이 환경마다 다르므로 열 인덱싱이 아니라 환경별 take 다 (plan.md §4.3).
        info["score_me"] = np.take_along_axis(self._scores, self._slot_side, axis=1).reshape(-1)
        info["score_opponent"] = np.take_along_axis(self._scores, 1 - self._slot_side, axis=1).reshape(-1)
        info["side"] = self.slot_sides
        return info

    # ── 관측 외의 것들 ──────────────────────────────────────────────────

    @property
    def slot_sides(self) -> np.ndarray:
        """`(num_envs,)` — 펼쳐진 행 하나하나가 어느 진영인가 (0 왼쪽, 1 오른쪽).

        진영별 메트릭을 나누는 기준이다 (FR-9). 앞 절반과 뒤 절반의 분포가 다르므로
        (뒷벽이 20px 다르다 — PRD §2.4) 이것으로 나누지 않으면 한쪽의 실패가 평균에 묻힌다.
        """
        return self._slot_side.reshape(-1)

    @property
    def reward_terms(self) -> np.ndarray:
        """`(num_envs, 5)` — 항별 **원시값**. 가중치는 적용되어 있지 않다 (FR-5).

        ⚠️ **복사본**이다. 내부 버퍼는 스텝마다 덮어쓰이므로 롤아웃 버퍼가 참조를 들고
           있으면 과거 스텝의 값이 조용히 바뀐다. 그 버그는 손실 곡선에 드러나지 않는다.
        """
        return self._terms.copy()

    # ── 리플레이 (Phase 4) ──────────────────────────────────────────────

    def fetch_replays(self) -> list[tuple[int, int, bytes]]:
        """서버에 쌓인 끝난 게임을 꺼낸다 — ``(env_index, game_in_env, 리플레이 v1 바이트)``.

        서버 큐를 비운다. ``record_replays`` 가 꺼져 있으면 항상 빈 목록이다.
        ⚠️ ``env_index`` 는 **서버 환경** 번호다. 행 번호는 ``env_index * slot_count + k`` 다.
        """
        reply = self._stub.FetchReplays(env_pb2.FetchReplaysRequest(session_id=self.session_id))
        return [(g.env_index, g.game_in_env, bytes(g.replay)) for g in reply.games]

    # ── 진단 ────────────────────────────────────────────────────────────

    def health(self) -> Any:
        """서버 상태. 누적 처리량과 레이아웃 해시를 준다."""
        return self._stub.Health(env_pb2.HealthRequest())
