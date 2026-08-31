package com.innogrid.aims.common.util;

import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * 목록 API 의 {@code sort} 파라미터를 해석한다.
 *
 * <p>이 저장소의 목록 API 는 {@code sort="필드,asc|desc"} 형식을 쓰기로 이미 정해 두었지만
 * (기본값이 선언된 엔드포인트가 13개 있다) 실제로 해석하는 코드가 없었다. 같은 파싱을
 * 컨트롤러마다 되풀이하지 않도록 여기에 모은다.</p>
 *
 * <p><b>허용 필드 목록을 반드시 넘긴다.</b> 여기서 만든 {@link Sort} 는 그대로 JPQL 의
 * order by 로 들어가므로, 목록에 없는 이름을 그대로 통과시키면 클라이언트가 정렬 기준으로
 * 임의의 필드 경로를 지정할 수 있게 된다.</p>
 *
 * <p>스프링 빈이 아니다. 애너테이션이 없어 컴포넌트 스캔 대상이 되지 않으며, 호출하지
 * 않으면 클래스 로딩조차 일어나지 않는다.</p>
 */
public final class SortSupport {

    private SortSupport() {}

    /**
     * {@code "필드,방향"} 을 {@link Sort} 로 옮긴다. 방향을 생략하면 오름차순이다.
     *
     * @param sort     요청 값. 비어 있으면 {@code fallback} 을 쓴다
     * @param allowed  정렬을 허용할 필드 이름
     * @param fallback 요청 값이 없을 때 쓸 기본 정렬
     * @throws BusinessException 형식이 틀렸거나 허용하지 않는 필드이면 400
     */
    public static Sort parse(String sort, Set<String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",");
        if (parts.length > 2) {
            throw badRequest("정렬 형식이 올바르지 않습니다: " + sort + " (형식: 필드,asc|desc)");
        }

        String field = parts[0].trim();
        if (!allowed.contains(field)) {
            throw badRequest(String.format("정렬할 수 없는 필드입니다: %s (가능한 값: %s)",
                    field, String.join(", ", allowed.stream().sorted().toList())));
        }

        if (parts.length == 1) {
            return Sort.by(Sort.Direction.ASC, field);
        }

        String direction = parts[1].trim().toLowerCase(Locale.ROOT);
        return switch (direction) {
            case "asc" -> Sort.by(Sort.Direction.ASC, field);
            case "desc" -> Sort.by(Sort.Direction.DESC, field);
            default -> throw badRequest("정렬 방향은 asc 또는 desc 여야 합니다: " + parts[1].trim());
        };
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
