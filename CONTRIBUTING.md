# 기여 가이드

이 프로젝트는 [MIT 라이선스](./LICENSE)를 따릅니다. 기여하는 코드도 같은 라이선스로
배포되는 것에 동의하는 것으로 간주합니다. 참여자는 [행동 강령](./CODE_OF_CONDUCT.md)을
지켜 주세요.

작은 이슈 하나와 검증 가능한 PR 하나를 기본 단위로 삼습니다. 기능, 버그, 벤치마크,
문서, 리팩터링, 보안 중 목적에 맞는 양식을 선택하고 서로 다른 목적은 PR을 나눠 주세요.

전체 절차와 분류 기준은 [docs/contributing/WORKFLOW.md](docs/contributing/WORKFLOW.md),
벤치마크 무결성 규칙은 [docs/contributing/BENCHMARK_POLICY.md](docs/contributing/BENCHMARK_POLICY.md)를 따릅니다.

## 평가기준 근거

모든 이슈와 PR은 프로젝트 구조 및 코드 완성도, 오픈소스 발전 가능성, 개발 문서 구체성,
프로젝트 혁신성, 프로젝트 팀워크 중 주 평가항목을 하나 선택하고 보조 평가항목을 함께
지정합니다. 아울러 기준 브랜치, 현재 문제의 증거, 전후 검증 방법, 위험과 되돌리기를
템플릿의 필수 항목으로 받습니다. 최소 0.1점 이상 기여할 수 있다는 가설, 예상 상승 폭,
구현 전후 확인 방법을 함께 적습니다.
예상 점수는 심사 결과를 보장하는 수치가 아니며, 근거가 없거나 중복되는 변경은 올리지 않습니다.

예약된 일일 검토에서도 같은 규칙을 사용합니다. 후보마다 독립 브랜치를 사용하고 자동으로
병합하지 않습니다. 구현·검증 중이면 Draft, 검증과 자체 리뷰가 끝나면 Ready for review로
전환합니다. 개선이 없거나 회귀하면 실패 결과를 보존한 뒤 이슈와 PR을 닫습니다.

## 1. 이슈 등록

1. [기존 이슈](https://github.com/qixiangme/riwonace-nexus/issues)를 검색해 중복을 확인합니다.
2. [새 이슈 만들기](https://github.com/qixiangme/riwonace-nexus/issues/new/choose)에서 `Feature`,
   `Bug`, `Benchmark`, `Docs`, `Refactor`, `Security` 중 목적에 맞는 양식을 선택합니다.
3. 문제와 범위, 완료 조건, 안전성 영향, 재현 방법을 작성합니다.
4. 주 평가항목과 보조 평가항목, 기준 브랜치, 현재 문제의 증거, 예상 상승 폭 가설,
   전후 검증 방법, 위험과 되돌리기를 기록합니다.

악용 가능한 취약점, 개인정보, 토큰, 내부 URL은 공개 이슈에 쓰지 않습니다. 이러한 내용은
[GitHub Security Advisory](https://github.com/qixiangme/riwonace-nexus/security/advisories/new)로
비공개 제보해 주세요.

## 2. 브랜치와 구현

```bash
git switch main
git pull --ff-only
git switch -c feature/짧은-설명
```

작업 성격에 따라 `feature/`, `fix/`, `benchmark/`, `docs/`, `refactor/`, `security/`,
`chore/` 접두사를 사용합니다. 한 브랜치와 PR에는 한 가지 목적만 포함하고, 공유 브랜치의
이력을 강제 푸시로 덮어쓰지 않습니다.

## 3. 검증과 기록

변경 유형에 맞는 검증을 실행하고 명령과 결과를 PR에 기록합니다.

```bash
# Kotlin 전체 테스트
bash ./gradlew test

# 웹 클라이언트 빌드
cd client
npm ci
npm audit --audit-level=high
npm run build
```

- 코드 변경: 정상·실패·경계 경로 테스트를 추가합니다.
- 웹 변경: TypeScript 빌드와 관련 UI 확인을 수행합니다.
- 벤치마크: 같은 데이터셋·모델·설정·반복 횟수로 기준선과 후보를 측정하고, 정확도,
  지연 시간, 오류와 문항별 원시 결과를 보존합니다.
- 외부 동작 변경: README 또는 관련 문서를 함께 갱신합니다.
- 보안 변경: 입력 검증, 읽기 전용 SQL, 최소 권한, 비밀정보 노출 여부를 확인합니다.

벤치마크 변경은 [벤치마크 무결성 정책](docs/contributing/BENCHMARK_POLICY.md)의 데이터 누수,
보류셋, 재현성 규칙을 반드시 따릅니다.

## 4. PR 등록

1. 브랜치를 원격에 푸시하고 GitHub에서 새 PR을 만듭니다.
2. 변경 유형에 맞는 [PR 템플릿](.github/PULL_REQUEST_TEMPLATE)을 선택합니다.
3. 제목에는 `[Feature]`, `[Docs]`, `[Benchmark]`, `[Bug]`, `[Refactor]`, `[Security]` 중
   알맞은 영문 접두사를 사용하고 나머지 제목과 본문은 한국어로 작성합니다.
4. 본문에 `Closes #이슈번호`를 작성해 이슈를 연결합니다.
5. 변경 이유와 범위, 실행한 테스트, 전후 수치, 안전성, 위험과 되돌리기를 기록합니다.
6. 정확성, 안전성, 체계성, 재현성, 회귀 여부를 자체 검토한 뒤 리뷰를 요청합니다.

PR 화면에서 템플릿이 자동으로 열리지 않으면 주소 끝에 `?template=feature.md` 또는
`?template=benchmark.md`처럼 템플릿 파일명을 지정할 수 있습니다.

## 5. PR과 이슈 상태 판정

| 상태 | 적용 기준 |
|---|---|
| Draft | 구현·테스트·벤치마크가 진행 중이거나 차단 문제가 남아 있음 |
| Ready for review | 구현과 검증이 끝났고 적대적 자체 리뷰에서 차단 문제가 없음 |
| Close / 계획하지 않음 | 목표 지표가 개선되지 않거나 회귀했고 현재 접근을 계속할 가치가 없음 |
| Issue / 완료됨 | 완료 조건을 모두 충족하고 근거 댓글을 남김 |

개선되지 않은 실험도 삭제하지 않습니다. 브랜치, 원시 결과, 실패 원인과 판정 근거를 남긴
뒤 PR과 이슈를 닫습니다. Ready 전환은 병합 승인이 아니며 PR을 자동으로 병합하지 않습니다.

## 보안

SQL은 읽기 전용을 유지하고 입력 검증과 최소 권한을 확인합니다. 로그, 스크린샷, 벤치마크
결과를 게시하기 전에 토큰, 접속 정보, 개인정보와 내부 URL을 제거합니다.
