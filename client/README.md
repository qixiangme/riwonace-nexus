# Riwonace MCP Client

React + Vite로 만든 과제 검증용 클라이언트입니다. 목적은 AI 답변 데모보다 성능 테스트와 결과 확인입니다.

## 기능

- `/api/tools` 상태 확인 및 MCP 도구 목록 표시
- `/api/chat` 단일 질의 실행
- 루트 테스트 데이터셋 기반 실행
- 여러 질문 배치 실행, 동시성 및 반복 횟수 조절
- 성공률, 평균 지연, P95 지연, 라우트 적중률, 키워드 적중률 요약
- 최근 응답의 `answer`, `routes`, `toolCalls`, `contextSources` 확인
- 실행 이력 CSV 다운로드
- `/mcp-admin/ingest` 프록시를 통한 샘플 데이터 적재 요청

## 실행

```bash
cd client
npm install
npm run dev
```

브라우저에서 `http://localhost:5173`으로 접속합니다.

## 서버 연결

Vite 개발 서버는 다음 프록시를 사용합니다.

- `/api/*` -> `http://localhost:8080`
- `/mcp-admin/*` -> `http://localhost:8081/admin/*`

따라서 `agent-app`은 8080, `mcp-server`는 8081에서 실행되어야 합니다.

## 테스트 데이터셋

루트 폴더의 데이터셋을 클라이언트에서 바로 사용할 수 있게 복사해 두었습니다.

- `companyx-dataset-v1.0/questions.json` -> `client/public/datasets/companyx-questions.json`
- `eval/eval-set.json` -> `client/public/datasets/eval-set.json`

클라이언트의 데이터셋 성능 테스트 패널에서 데이터셋과 도구/라우트를 필터링한 뒤 `데이터셋 실행`을 누르면 예상 라우트와 실제 응답 라우트를 비교합니다. `eval-set.json` 문항은 키워드 적중률도 함께 계산합니다.
