from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SlotKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SLOT_KIND_UNSPECIFIED: _ClassVar[SlotKind]
    SLOT_KIND_EXTERNAL: _ClassVar[SlotKind]
    SLOT_KIND_FSM: _ClassVar[SlotKind]
SLOT_KIND_UNSPECIFIED: SlotKind
SLOT_KIND_EXTERNAL: SlotKind
SLOT_KIND_FSM: SlotKind

class RewardWeights(_message.Message):
    __slots__ = ("rally_win", "ball_touch", "crossed_net", "opponent_miss", "time_penalty")
    RALLY_WIN_FIELD_NUMBER: _ClassVar[int]
    BALL_TOUCH_FIELD_NUMBER: _ClassVar[int]
    CROSSED_NET_FIELD_NUMBER: _ClassVar[int]
    OPPONENT_MISS_FIELD_NUMBER: _ClassVar[int]
    TIME_PENALTY_FIELD_NUMBER: _ClassVar[int]
    rally_win: float
    ball_touch: float
    crossed_net: float
    opponent_miss: float
    time_penalty: float
    def __init__(self, rally_win: _Optional[float] = ..., ball_touch: _Optional[float] = ..., crossed_net: _Optional[float] = ..., opponent_miss: _Optional[float] = ..., time_penalty: _Optional[float] = ...) -> None: ...

class ConfigureRequest(_message.Message):
    __slots__ = ("num_envs", "base_seed", "p1", "p2", "winning_score", "max_rally_frames", "obs_include_expected_landing", "obs_include_side_flag", "mirror_observations", "edge_trigger_power_hit", "reward_weights", "swapped_envs", "fixed_boldness", "record_replays", "replay_frame_cap")
    NUM_ENVS_FIELD_NUMBER: _ClassVar[int]
    BASE_SEED_FIELD_NUMBER: _ClassVar[int]
    P1_FIELD_NUMBER: _ClassVar[int]
    P2_FIELD_NUMBER: _ClassVar[int]
    WINNING_SCORE_FIELD_NUMBER: _ClassVar[int]
    MAX_RALLY_FRAMES_FIELD_NUMBER: _ClassVar[int]
    OBS_INCLUDE_EXPECTED_LANDING_FIELD_NUMBER: _ClassVar[int]
    OBS_INCLUDE_SIDE_FLAG_FIELD_NUMBER: _ClassVar[int]
    MIRROR_OBSERVATIONS_FIELD_NUMBER: _ClassVar[int]
    EDGE_TRIGGER_POWER_HIT_FIELD_NUMBER: _ClassVar[int]
    REWARD_WEIGHTS_FIELD_NUMBER: _ClassVar[int]
    SWAPPED_ENVS_FIELD_NUMBER: _ClassVar[int]
    FIXED_BOLDNESS_FIELD_NUMBER: _ClassVar[int]
    RECORD_REPLAYS_FIELD_NUMBER: _ClassVar[int]
    REPLAY_FRAME_CAP_FIELD_NUMBER: _ClassVar[int]
    num_envs: int
    base_seed: int
    p1: SlotKind
    p2: SlotKind
    winning_score: int
    max_rally_frames: int
    obs_include_expected_landing: bool
    obs_include_side_flag: bool
    mirror_observations: bool
    edge_trigger_power_hit: bool
    reward_weights: RewardWeights
    swapped_envs: int
    fixed_boldness: int
    record_replays: bool
    replay_frame_cap: int
    def __init__(self, num_envs: _Optional[int] = ..., base_seed: _Optional[int] = ..., p1: _Optional[_Union[SlotKind, str]] = ..., p2: _Optional[_Union[SlotKind, str]] = ..., winning_score: _Optional[int] = ..., max_rally_frames: _Optional[int] = ..., obs_include_expected_landing: _Optional[bool] = ..., obs_include_side_flag: _Optional[bool] = ..., mirror_observations: _Optional[bool] = ..., edge_trigger_power_hit: _Optional[bool] = ..., reward_weights: _Optional[_Union[RewardWeights, _Mapping]] = ..., swapped_envs: _Optional[int] = ..., fixed_boldness: _Optional[int] = ..., record_replays: _Optional[bool] = ..., replay_frame_cap: _Optional[int] = ...) -> None: ...

class ConfigureReply(_message.Message):
    __slots__ = ("num_envs", "slot_count", "obs_dim", "obs_layout_hash", "obs_field_names", "reward_term_names", "action_count", "session_id")
    NUM_ENVS_FIELD_NUMBER: _ClassVar[int]
    SLOT_COUNT_FIELD_NUMBER: _ClassVar[int]
    OBS_DIM_FIELD_NUMBER: _ClassVar[int]
    OBS_LAYOUT_HASH_FIELD_NUMBER: _ClassVar[int]
    OBS_FIELD_NAMES_FIELD_NUMBER: _ClassVar[int]
    REWARD_TERM_NAMES_FIELD_NUMBER: _ClassVar[int]
    ACTION_COUNT_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    num_envs: int
    slot_count: int
    obs_dim: int
    obs_layout_hash: str
    obs_field_names: _containers.RepeatedScalarFieldContainer[str]
    reward_term_names: _containers.RepeatedScalarFieldContainer[str]
    action_count: int
    session_id: int
    def __init__(self, num_envs: _Optional[int] = ..., slot_count: _Optional[int] = ..., obs_dim: _Optional[int] = ..., obs_layout_hash: _Optional[str] = ..., obs_field_names: _Optional[_Iterable[str]] = ..., reward_term_names: _Optional[_Iterable[str]] = ..., action_count: _Optional[int] = ..., session_id: _Optional[int] = ...) -> None: ...

class ResetRequest(_message.Message):
    __slots__ = ("base_seed", "session_id")
    BASE_SEED_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    base_seed: int
    session_id: int
    def __init__(self, base_seed: _Optional[int] = ..., session_id: _Optional[int] = ...) -> None: ...

class StepRequest(_message.Message):
    __slots__ = ("actions", "session_id")
    ACTIONS_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    actions: bytes
    session_id: int
    def __init__(self, actions: _Optional[bytes] = ..., session_id: _Optional[int] = ...) -> None: ...

class StepReply(_message.Message):
    __slots__ = ("observations", "rewards", "terminated", "truncated", "reward_terms", "scores")
    OBSERVATIONS_FIELD_NUMBER: _ClassVar[int]
    REWARDS_FIELD_NUMBER: _ClassVar[int]
    TERMINATED_FIELD_NUMBER: _ClassVar[int]
    TRUNCATED_FIELD_NUMBER: _ClassVar[int]
    REWARD_TERMS_FIELD_NUMBER: _ClassVar[int]
    SCORES_FIELD_NUMBER: _ClassVar[int]
    observations: bytes
    rewards: bytes
    terminated: bytes
    truncated: bytes
    reward_terms: bytes
    scores: bytes
    def __init__(self, observations: _Optional[bytes] = ..., rewards: _Optional[bytes] = ..., terminated: _Optional[bytes] = ..., truncated: _Optional[bytes] = ..., reward_terms: _Optional[bytes] = ..., scores: _Optional[bytes] = ...) -> None: ...

class HealthRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class HealthReply(_message.Message):
    __slots__ = ("version", "configured", "num_envs", "slot_count", "obs_dim", "obs_layout_hash", "total_env_steps", "uptime_seconds", "env_steps_per_sec", "reward_term_names", "session_id")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    CONFIGURED_FIELD_NUMBER: _ClassVar[int]
    NUM_ENVS_FIELD_NUMBER: _ClassVar[int]
    SLOT_COUNT_FIELD_NUMBER: _ClassVar[int]
    OBS_DIM_FIELD_NUMBER: _ClassVar[int]
    OBS_LAYOUT_HASH_FIELD_NUMBER: _ClassVar[int]
    TOTAL_ENV_STEPS_FIELD_NUMBER: _ClassVar[int]
    UPTIME_SECONDS_FIELD_NUMBER: _ClassVar[int]
    ENV_STEPS_PER_SEC_FIELD_NUMBER: _ClassVar[int]
    REWARD_TERM_NAMES_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    version: str
    configured: bool
    num_envs: int
    slot_count: int
    obs_dim: int
    obs_layout_hash: str
    total_env_steps: int
    uptime_seconds: float
    env_steps_per_sec: float
    reward_term_names: _containers.RepeatedScalarFieldContainer[str]
    session_id: int
    def __init__(self, version: _Optional[str] = ..., configured: _Optional[bool] = ..., num_envs: _Optional[int] = ..., slot_count: _Optional[int] = ..., obs_dim: _Optional[int] = ..., obs_layout_hash: _Optional[str] = ..., total_env_steps: _Optional[int] = ..., uptime_seconds: _Optional[float] = ..., env_steps_per_sec: _Optional[float] = ..., reward_term_names: _Optional[_Iterable[str]] = ..., session_id: _Optional[int] = ...) -> None: ...

class FetchReplaysRequest(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: int
    def __init__(self, session_id: _Optional[int] = ...) -> None: ...

class FetchReplaysReply(_message.Message):
    __slots__ = ("games",)
    GAMES_FIELD_NUMBER: _ClassVar[int]
    games: _containers.RepeatedCompositeFieldContainer[RecordedGame]
    def __init__(self, games: _Optional[_Iterable[_Union[RecordedGame, _Mapping]]] = ...) -> None: ...

class RecordedGame(_message.Message):
    __slots__ = ("env_index", "game_in_env", "replay")
    ENV_INDEX_FIELD_NUMBER: _ClassVar[int]
    GAME_IN_ENV_FIELD_NUMBER: _ClassVar[int]
    REPLAY_FIELD_NUMBER: _ClassVar[int]
    env_index: int
    game_in_env: int
    replay: bytes
    def __init__(self, env_index: _Optional[int] = ..., game_in_env: _Optional[int] = ..., replay: _Optional[bytes] = ...) -> None: ...
