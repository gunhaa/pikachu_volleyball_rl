"""js_trunc_div 가 JS `(a / b) | 0` 과 일치하는지 확인한다.

기대값은 Node 로 실제 뽑은 것이다 (plan.md §3).
"""

import pytest

from pika_trainer import js_trunc_div


@pytest.mark.parametrize(
    ("a", "b", "expected"),
    [
        (5, 2, 2),
        (-5, 2, -2),  # ← Python `//` 였다면 -3. 갈라지는 지점.
        (5, -2, -2),
        (-5, -2, 2),
        (0, 3, 0),
        (432, 2, 216),  # GROUND_HALF_WIDTH
        (-1, 10, 0),
        (-9, 10, 0),
    ],
)
def test_matches_js_truncating_division(a: int, b: int, expected: int) -> None:
    assert js_trunc_div(a, b) == expected


def test_python_floor_division_really_differs() -> None:
    """이 테스트가 깨지면 위 함수의 존재 이유가 사라진 것이다."""
    assert -5 // 2 == -3
    assert js_trunc_div(-5, 2) == -2
