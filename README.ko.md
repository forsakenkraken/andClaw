# andClaw

andClaw는 안드로이드 폰을 온디바이스 AI 게이트웨이 호스트로 바꿔주는 앱입니다.
`proroot` 기반 Ubuntu arm64 환경에서 OpenClaw를 실행하고, Jetpack Compose UI로 설치, 온보딩, 페어링, 실행 제어를 제공합니다.

Google Play: https://play.google.com/store/apps/details?id=com.coderred.andclaw

## 주요 기능

- rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 원클릭 설치
- OpenRouter OAuth 온보딩 또는 수동 API 키 설정
- 앱 내 게이트웨이 시작, 중지, 재시작 제어
- OpenRouter, OpenAI, Anthropic, Google, OpenAI Codex 모드용 provider/모델 설정
- WhatsApp, Telegram, Discord 채널 연동
- 앱 내부 WhatsApp QR 페어링 플로우
- 포그라운드 서비스, 부팅 자동 시작, 앱 업데이트 후 재시작, watchdog 복구 기반 런타임 복구
- 대용량 install-time assets를 위한 Play Asset Delivery 지원

## 요구사항

- Android Studio / Gradle 환경
- Java 11
- `scripts/setup-assets.sh` 실행용 Docker
- arm64 안드로이드 기기 (최소 SDK 26)

## 프로젝트 구조

- `app/` - Android 앱 모듈 (Kotlin + Jetpack Compose)
- `app/src/main/java/com/coderred/andclaw/` - 기능 레이어별 앱 코드 (`ui/`, `data/`, `proroot/`, `service/`, `receiver/`, `auth/`)
- `app/src/test/` - JVM 단위 테스트
- `app/src/androidTest/` - 계측 테스트
- `install_time_assets/` - Play Asset Delivery install-time asset pack
- `scripts/setup-assets.sh` - `jniLibs`와 번들 런타임 에셋 생성 스크립트

## 빌드

```bash
# 1) 에셋 준비 (최초 1회 또는 번들 갱신 시)
# Docker와 네트워크 접근이 필요하므로 샌드박스 밖에서 실행해야 합니다.
./scripts/setup-assets.sh

# 2) 디버그 APK (prod debug 기준 호환 alias)
./gradlew assembleDebug

# 3) 권장 프로덕션 릴리스 AAB
./gradlew bundleProdRelease
```

산출물:

- 권장 릴리스 AAB: `app/build/outputs/bundle/prodRelease/app-prod-release.aab`
- 레거시 릴리스 경로도 헬퍼 스크립트에서 계속 지원: `app/build/outputs/bundle/release/app-release.aab`

## 디버그 설치

```bash
./gradlew installProdDebug
```

## 테스트

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
```

## 16KB 페이지 크기 호환성

Google Play는 Android 15+ 타깃 앱에 16KB 페이지 크기 호환을 요구합니다. `scripts/setup-assets.sh`는 패키징 전에 번들 네이티브 바이너리를 검증합니다.

- `scripts/setup-assets.sh`가 `app/src/main/jniLibs/arm64-v8a/*.so`의 LOAD 세그먼트 정렬을 확인합니다.

## 오픈소스 고지

핵심 서드파티 런타임 컴포넌트와 배포 시 유의사항은 `THIRD_PARTY_LICENSES.md`를 참고하세요.
