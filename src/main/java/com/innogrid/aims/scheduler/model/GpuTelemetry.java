package com.innogrid.aims.scheduler.model;

/**
 * 노드 한 대의 GPU 축 — 카드들을 접은 값. 사용률은 카드 평균, 온도는 가장 뜨거운 카드, 전력은 카드 합.
 *
 * <p>노드의 섀시 온도·전력({@code temperatureCelsius}·{@code powerWatts})과는 <b>별개의 축</b>이다. 섀시 온도는 센서
 * 평균이고 GPU 온도는 카드 값이라 섞으면 둘 다 뜻을 잃고, IPMI 전력은 섀시 전체(GPU 포함)라 GPU 전력을 더하면 이중
 * 계상이다. 그래서 어느 쪽에도 합치지 않고 나란히 둔다.</p>
 *
 * <p>{@code 0} 은 미계측이다 — 켜진 카드의 온도 0°C·전력 0W 는 물리적으로 없다. GPU 가 없는 노드는 GPU 개수가 0 이라
 * 이 값들을 볼 일이 없고, GPU 가 있는데 셋이 0 이면 DCGM 시리즈가 비었다는 뜻이다.</p>
 */
public record GpuTelemetry(double usagePercent, double temperatureCelsius, double powerWatts) {

    public static final GpuTelemetry EMPTY = new GpuTelemetry(0, 0, 0);

    /** 셋 다 0 — 시리즈가 없었다. */
    public boolean isEmpty() {
        return usagePercent <= 0 && temperatureCelsius <= 0 && powerWatts <= 0;
    }
}
