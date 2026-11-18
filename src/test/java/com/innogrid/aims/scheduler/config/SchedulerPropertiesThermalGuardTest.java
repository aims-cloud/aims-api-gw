package com.innogrid.aims.scheduler.config;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 열관성 계수 설정의 음수 보정 — setter와 실제 바인딩 경로 둘 다.
 *
 * <p>바인딩 테스트는 {@code @ConfigurationProperties}와 같은 JavaBean 바인딩({@link Binder} · 기존 인스턴스에 바인딩)으로
 * 설정 키가 setter를 지나 잘리는 것을 확인한다 — 스프링 컨텍스트 없이 돈다.</p>
 */
class SchedulerPropertiesThermalGuardTest {

    @DisplayName("열관성 계수 설정은 음수를 0 으로 자르고 0 이상은 그대로 둔다")
    @Test
    void inertia_k_setter_clamps_negative_to_zero() {
        SchedulerProperties.Thermal thermal = new SchedulerProperties().getThermal();

        thermal.setInertiaK(-0.15);
        assertEquals(0.0, thermal.getInertiaK(), 0.0, "음수는 0");
        thermal.setInertiaK(0);
        assertEquals(0.0, thermal.getInertiaK(), 0.0, "0 은 0");
        thermal.setInertiaK(0.15);
        assertEquals(0.15, thermal.getInertiaK(), 0.0, "양수는 그대로");
    }

    @DisplayName("설정 바인딩 경로도 음수를 0 으로 자른다")
    @Test
    void binding_path_applies_the_clamp() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("aims.scheduler.thermal.inertia-k", "-0.15")));

        SchedulerProperties target = new SchedulerProperties();

        SchedulerProperties bound = binder.bind("aims.scheduler", Bindable.ofInstance(target)).get();

        assertSame(target, bound, "기존 인스턴스에 바인딩한다");
        assertEquals(0.0, target.getThermal().getInertiaK(), 0.0, "바인딩이 setter 를 지나 잘려야 한다");
    }
}
