package com.innogrid.aims.scheduler.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.UserRepository;

/**
 * 스케줄을 만들거나 실행한 주체를 알아낸다.
 *
 * <p>인증 principal 에는 email 만 들어 있어(스프링 기본 {@code UserDetails} 를 쓴다)
 * 화면에 표시할 이름을 얻으려면 사용자 원장을 읽어야 한다. <b>이 클래스가 인증 도메인을
 * 참조하는 유일한 지점</b>이다. 스케줄러 곳곳에서 직접 리포지토리를 부르면 결합도가
 * 올라가고 나중에 사용자 조회 방식이 바뀔 때 고칠 곳이 흩어진다.</p>
 *
 * <p>읽기만 하며 인증 도메인의 동작을 바꾸지 않는다. 이름은 조회한 시점의 값을 스케줄
 * 행에 복사해 두므로, 나중에 사용자 이름이 바뀌어도 기록 당시의 이름이 남는다.</p>
 */
@Component
public class ScheduleActorResolver {

    /** 사용자 없이 러너가 일으킨 실행의 주체. */
    public static final String SYSTEM = "SYSTEM";

    private static final String ANONYMOUS = "anonymousUser";

    private final UserRepository userRepository;

    public ScheduleActorResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 현재 요청의 주체. 인증 정보가 없으면 시스템으로 본다.
     * 자동 실행은 요청 스레드가 아니라 러너 스레드에서 돌아 인증 정보가 비어 있다.
     */
    public Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || ANONYMOUS.equals(auth.getName())) {
            return Actor.system();
        }
        String email = auth.getName();
        String name = userRepository.findByEmail(email).map(User::getName).orElse(null);
        return new Actor(email, name);
    }

    /** 러너가 쓰는 고정 주체. */
    public Actor system() {
        return Actor.system();
    }

    /**
     * @param email 로그인 계정. 화면에는 이름과 조합해 "이름(email)" 로 표시한다
     * @param name  조회 시점의 사용자 이름. 원장에 없으면 null
     */
    public record Actor(String email, String name) {
        static Actor system() {
            return new Actor(SYSTEM, SYSTEM);
        }
    }
}
