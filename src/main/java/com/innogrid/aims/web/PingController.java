package com.innogrid.aims.web;

import java.time.Instant;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포가 실제로 반영됐는지 눈으로 확인하기 위한 엔드포인트.
 *
 * <p>VM 에 올린 jar 가 방금 빌드한 그 jar 인지 헷갈리는 상황이 자주 생기는데,
 * 여기서 돌려주는 profile/timestamp 를 보면 바로 판별할 수 있다.
 */
@Tag(name = "시스템", description = "헬스체크")
@RestController
@RequestMapping("/api")
class PingController {

	private final String activeProfile;

	PingController(@Value("${spring.profiles.active:local}") String activeProfile) {
		this.activeProfile = activeProfile;
	}

	@GetMapping("/ping")
	PingResponse ping() {
		return new PingResponse("pong", activeProfile, Instant.now().toString());
	}

	record PingResponse(String message, String profile, String startedAt) {
	}
}
