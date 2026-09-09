package com.innogrid.aims.monitoring.service.alarm.notification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.entity.AlarmDelivery;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import com.innogrid.aims.monitoring.entity.AlarmPolicy.RecipientRef;
import com.innogrid.aims.monitoring.repository.AlarmDeliveryRepository;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmPolicyRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전송 계획 (docs/09 A.5.4 수신자 전개) — 발생 알람 1건을 정책의 전송 대상 × 채널로 전개해 전송 원장 행을 만든다.
 *
 * <ul>
 *   <li>개인 대상 → users.id 그대로, 조직 대상 → 발송 시점의 구성원({@code users.organization}, 무소속은 "미지정")을 전개한다.</li>
 *   <li>수신자 행은 SENDING 으로 먼저 만들고 발송기가 결과로 갱신한다 — 화면의 "전송중"이 자연스럽게 나온다.</li>
 *   <li>시도조차 못 하는 수신자는 사유와 함께 바로 FAILED: 삭제된 사용자, 비활성 사용자, 연락처 없음(SMS 는 {@code users.phone} 도입 전까지 전부).</li>
 *   <li>같은 사용자가 여러 대상(개인 + 소속 조직)에 걸리면 채널당 한 번만 받는다 — 먼저 나온 대상에 붙고 뒤 대상에서는 뺀다.</li>
 *   <li>이력의 {@code delivery_count} 는 만든 전송 행 수만큼 올린다.</li>
 * </ul>
 * 평가 트랜잭션 커밋 뒤 별도 트랜잭션(REQUIRES_NEW)으로 돌아 계획 실패가 평가를 되돌리지 않는다.
 */
@Component
public class AlarmDeliveryPlanner {

    private static final Logger log = LoggerFactory.getLogger(AlarmDeliveryPlanner.class);

    static final String UNASSIGNED_ORG = "미지정";
    static final String DELETED_USER = "(삭제된 사용자)";
    static final String REASON_DELETED = "삭제된 사용자";
    static final String REASON_INACTIVE = "비활성 사용자";
    static final String REASON_NO_ADDRESS = "연락처 없음";

    private final AlarmPolicyRepository policyRepository;
    private final AlarmHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final AlarmDeliveryRepository deliveryRepository;
    private final AlarmMessageTemplate template;

    public AlarmDeliveryPlanner(AlarmPolicyRepository policyRepository, AlarmHistoryRepository historyRepository,
                                UserRepository userRepository, AlarmDeliveryRepository deliveryRepository,
                                AlarmMessageTemplate template) {
        this.policyRepository = policyRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.deliveryRepository = deliveryRepository;
        this.template = template;
    }

    /**
     * 전송 원장 행을 만들고, 발송기가 처리할(SENDING 수신자가 있는) 전송 id 를 돌려준다.
     * 발생 전이가 아니거나 정책이 없으면(정책 삭제와 경합) 빈 목록.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> plan(AlarmStateChangedEvent e) {
        if (!e.isFiring() || e.policyId() == null) return List.of();
        Optional<AlarmPolicy> found = policyRepository.findById(e.policyId());
        if (found.isEmpty()) {
            log.warn("알람 전송 계획 생략: 정책 없음 policyId={}, alarmHistoryId={}", e.policyId(), e.alarmHistoryId());
            return List.of();
        }
        AlarmPolicy policy = found.get();
        List<AlarmChannel> channels = policy.getChannels();
        if (channels.isEmpty() || policy.getRecipients().isEmpty()) return List.of();

        AlarmMessageTemplate.Message message = template.render(e);
        OffsetDateTime now = OffsetDateTime.now();
        Map<AlarmChannel, Set<Long>> seen = new EnumMap<>(AlarmChannel.class);
        List<AlarmDelivery> deliveries = new ArrayList<>();

        for (RecipientRef ref : policy.getRecipients()) {
            Target target = resolve(ref);
            for (AlarmChannel channel : channels) {
                AlarmDelivery d = new AlarmDelivery(e.alarmHistoryId(), policy.getId(), ref.targetType(), ref.targetId(),
                        target.name(), channel, message.subject(), message.bodyFor(channel), now);
                if (target.deleted()) {
                    d.addFailed(null, DELETED_USER, null, REASON_DELETED, now);
                } else {
                    Set<Long> seenIds = seen.computeIfAbsent(channel, k -> new HashSet<>());
                    for (User u : target.users()) {
                        if (!seenIds.add(u.getId())) continue;            // 다른 대상에서 이미 받는 사용자
                        String address = addressOf(u, channel);
                        if (u.getStatus() != User.UserStatus.ACTIVE) {
                            d.addFailed(u.getId(), u.getName(), address, REASON_INACTIVE, now);
                        } else if (address == null || address.isBlank()) {
                            d.addFailed(u.getId(), u.getName(), null, REASON_NO_ADDRESS, now);
                        } else {
                            d.addPending(u.getId(), u.getName(), address);
                        }
                    }
                }
                deliveries.add(d);
            }
        }

        deliveryRepository.saveAll(deliveries);
        historyRepository.findById(e.alarmHistoryId()).ifPresent(h -> h.incrementDeliveryCount(deliveries.size()));
        List<Long> pending = deliveries.stream().filter(AlarmDelivery::hasPending).map(AlarmDelivery::getId).toList();
        log.debug("알람 전송 계획: alarmHistoryId={}, deliveries={}, pending={}", e.alarmHistoryId(), deliveries.size(), pending.size());
        return pending;
    }

    /** 대상 1건 전개 — 개인은 사용자 1명(없으면 deleted), 조직은 구성원 목록(없으면 빈 목록) */
    private Target resolve(RecipientRef ref) {
        if (ref.targetType() == AlarmTargetType.ORGANIZATION) {
            List<User> members = UNASSIGNED_ORG.equals(ref.targetId())
                    ? userRepository.findUnassigned()
                    : userRepository.findByOrganization(ref.targetId());
            return new Target(ref.targetId(), members, false);
        }
        Optional<User> user = parseLong(ref.targetId()).flatMap(userRepository::findById);
        return user.map(u -> new Target(u.getName(), List.of(u), false))
                .orElseGet(() -> new Target(DELETED_USER, List.of(), true));
    }

    /** 채널별 수신 주소 — EMAIL 은 users.email, SMS 는 users.phone 도입 전까지 null */
    private static String addressOf(User u, AlarmChannel channel) {
        return channel == AlarmChannel.EMAIL ? u.getEmail() : null;
    }

    private static Optional<Long> parseLong(String s) {
        try {
            return Optional.of(Long.parseLong(s.trim()));
        } catch (NumberFormatException | NullPointerException ex) {
            return Optional.empty();
        }
    }

    private record Target(String name, List<User> users, boolean deleted) {}
}
