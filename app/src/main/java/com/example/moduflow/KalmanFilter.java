package com.example.moduflow;

/**
 * BLE 비콘 거리 평활화를 위한 1차원 칼만 필터.
 *
 * 칼만 필터는 노이즈가 포함된 측정값으로부터 실제 상태(거리)를 추정하는
 * 재귀 알고리즘으로, 매 측정마다 '예측 → 업데이트' 두 단계를 반복한다.
 *
 * Q (프로세스 노이즈): 사람이 실제로 이동하는 속도에 대한 불확실성.
 *   낮을수록 이전 추정값을 더 신뢰하여 출력이 부드러워지지만 반응이 느려진다.
 *
 * R (측정 노이즈): BLE RSSI 기반 거리 측정의 불확실성.
 *   높을수록 측정값보다 모델을 신뢰하여 스파이크에 덜 민감해진다.
 */
public class KalmanFilter {

    private final double q; // 프로세스 노이즈 공분산 (Q)
    private final double r; // 측정 노이즈 공분산 (R)

    private double  x;           // 현재 거리 추정값 (단위: 미터)
    private double  p;           // 추정 오차 공분산 — 추정값의 불확실성 크기
    private boolean initialized = false;

    public KalmanFilter(double q, double r) {
        this.q = q;
        this.r = r;
    }

    /**
     * 새 측정값을 입력받아 필터링된 거리 추정값을 반환한다.
     *
     * 최초 호출 시에는 측정값을 그대로 초기 상태로 설정한다.
     * 이후 호출부터는 예측 → 업데이트 순서로 동작한다.
     */
    public double filter(double measurement) {
        if (!initialized) {
            // 최초 측정값을 초기 상태로 설정, 오차 공분산은 1.0으로 초기화
            x           = measurement;
            p           = 1.0;
            initialized = true;
            return x;
        }

        // ── 예측 단계 (Predict) ────────────────────────────────────────
        // 이전 추정값을 그대로 예측값으로 사용 (등속 이동 없음 가정).
        // 오차 공분산에 Q를 더해 예측 불확실성을 증가시킨다.
        p = p + q;

        // ── 업데이트 단계 (Update) ─────────────────────────────────────
        // 칼만 게인 K: 측정값과 예측값 중 어느 쪽을 더 신뢰할지 결정하는 가중치.
        // K가 1에 가까울수록 측정값을 더 신뢰, 0에 가까울수록 예측값을 더 신뢰한다.
        // 정상 상태에서 K ≈ √(Q/R) 로 수렴한다. (현재 파라미터: √(0.1/2.0) ≈ 0.22)
        double k = p / (p + r);

        // 측정 잔차(measurement - x)에 K를 곱해 추정값을 보정한다.
        x = x + k * (measurement - x);

        // 칼만 게인만큼 오차 공분산을 줄인다 (불확실성 감소).
        p = (1.0 - k) * p;

        return x;
    }

    /**
     * 필터 상태를 초기화한다.
     * 비콘이 일정 시간 미감지된 후 재등장할 때 호출하여
     * 과거 추정값이 새 측정에 영향을 주지 않도록 한다.
     */
    public void reset() {
        initialized = false;
    }
}