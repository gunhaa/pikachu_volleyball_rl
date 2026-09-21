"""JS/Kotlin 과 동일한 정수 나눗셈 의미론.

plan.md §3 의 경고를 코드로 고정해 둔 것이다.

`physics.js` 의 나눗셈은 전부 `(a / b) | 0` 이고, 이는 **0 방향 절삭**이다.
JS 와 Kotlin 은 둘 다 0 방향 절삭이지만 **Python `//` 는 floor division** 이라
음수에서 갈라진다.

    JS/Kotlin:  -5 / 2  ->  -2
    Python:     -5 // 2 ->  -3

엔진 자체는 Kotlin 이 담당하므로 학습 코드가 물리 값을 재계산할 일은 없어야 하지만,
분석·검증 스크립트가 엔진 값을 다시 만져야 할 때는 반드시 이 함수를 쓴다.
"""


def js_trunc_div(a: int, b: int) -> int:
    """`(a / b) | 0` 과 동일한 결과를 반환한다 (0 방향 절삭)."""
    if b == 0:
        raise ZeroDivisionError("physics.js 에는 0 나눗셈 경로가 없다. 호출부가 잘못되었다.")
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q
