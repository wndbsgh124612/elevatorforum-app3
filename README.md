# ElevatorForum Rebuilder Improved

리빌더 앱패키징 구조를 기준으로 다시 구성한 개선판 프로젝트입니다.

포함된 개선:
- IntroActivity + MainActivity + CustomFirebaseMessagingService 구조
- 세션 쿠키 기반 토큰 저장
- data-only 푸시 수신 대응
- 커스텀 알림음 (res/raw/elevator_alert.mp3)
- safe area / 상태바 / 하단 탭바 대응
- GitHub Actions로 APK + AAB 자동 빌드

## GitHub 업로드 후
1. 압축 해제
2. 내부 파일을 저장소 루트에 업로드
3. Actions 실행
4. 아티팩트에서 APK / AAB 다운로드

## 플레이스토어 업로드용 서명
release AAB를 실제 업로드하려면 GitHub Secrets에 아래 추가:
- KEYSTORE_BASE64
- KEYSTORE_PASSWORD
- KEY_ALIAS
- KEY_PASSWORD

Secrets가 없으면 release 빌드는 debug signing fallback으로 생성됩니다.
(테스트용 빌드는 가능하지만 실제 Play 업로드용으로는 정식 keystore 사용 권장)

## 서버 패치
server_patches 폴더에 아래 파일 포함:
- rb/rb.lib/ajax.token_update.php
- rb/rb.lib/curl.send_push.php

memo -> push 연동은 서버측 리빌더/쪽지 발송 흐름과 연결되는 로직 추가가 더 필요할 수 있습니다.
현재는 토큰 저장 및 data-only 푸시 전송 기준 패치를 포함했습니다.


## 참고
이 프로젝트는 GitHub Actions 빌드 기준으로 바로 동작하도록 구성했습니다. 로컬 Android Studio에서 열면 필요한 Gradle 파일은 자동으로 재동기화될 수 있습니다.
