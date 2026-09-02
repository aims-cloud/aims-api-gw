plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.innogrid"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.h2database:h2")
	// H2 웹 콘솔 (Boot 4 에서 별도 모듈로 분리) — 로컬 프로파일에서만 spring.h2.console.enabled 로 활성화
	implementation("org.springframework.boot:spring-boot-h2console")
	implementation("com.fasterxml.jackson.core:jackson-databind")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	annotationProcessor("org.projectlombok:lombok")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testRuntimeOnly("com.h2database:h2")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
	testLogging {
		events("passed", "skipped", "failed")
	}
}

// 한글 주석/리소스가 Windows 기본 인코딩(MS949)으로 읽히면 깨지므로 명시적으로 고정한다.
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

// 배포 스크립트가 항상 같은 경로를 참조할 수 있도록 산출물 이름을 고정한다.
// (버전이 올라가도 deploy.ps1 을 고칠 필요가 없다)
tasks.bootJar {
	archiveFileName.set("app.jar")
}

// VM 에는 fat jar 만 올리므로 -plain.jar 는 만들지 않는다.
tasks.jar {
	enabled = false
}
