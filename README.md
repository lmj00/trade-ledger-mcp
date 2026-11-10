# trade-ledger-mcp

완결된 거래의 실현 손익을 계산하는 읽기 전용 MCP 서버입니다. 로컬 CSV 저널을 요약하는 `summarize_journal`과 구조화된 거래를 분석하는 `analyze_trades`를 제공합니다. 주문 실행·취소, 입출금·이체 등 자금 이동 기능은 없습니다.

현재 입력은 프로젝트가 정의한 **사용자 정의 완결 거래 형식**입니다. 업비트·바이낸스·토스증권의 공식 API 응답이나 공식 내보내기 CSV 형식을 구현한 것이 아닙니다. 아래 사용법은 제공된 코드와 기존 테스트를 읽어 작성했습니다. 이번 문서 작성에서는 외부 조회, 인증정보 접근, 빌드·테스트 실행이나 실제 서비스 연동을 하지 않았으며 서버 검증과 독립 검토는 대기 상태입니다.

## 설치·빌드·실행

Java 21 JDK를 설치하고 `JAVA_HOME`과 `PATH`가 해당 JDK를 가리키도록 설정합니다. 프로젝트 루트에서 포함된 Gradle Wrapper를 사용하므로 별도 Gradle 설치는 필요하지 않습니다. 최초 실행 시 Gradle 배포본과 Maven Central 의존성 다운로드가 필요할 수 있습니다. 다음은 사용자가 실행할 명령이며 이번 작업의 실행 결과가 아닙니다.

```bash
java -version
./gradlew test
./gradlew build
./gradlew bootRun
```

`build.gradle`은 Java 21 도구 체인과 JUnit Platform을 지정합니다. `bootRun`은 HTTP 서버가 아닌 동기식 stdio MCP 서버를 시작합니다. MCP 클라이언트가 프로세스의 표준 입력·출력으로 연결해야 하며, 일반 셸에 JSON만 입력하는 것으로 MCP 초기화 절차를 대신할 수는 없습니다.

기본 저널 루트는 프로세스 작업 디렉터리 기준 `./journals`입니다. 저장소 루트에서 실행하면 포함된 `journals/sample-trades.csv`를 사용할 수 있습니다. 다른 디렉터리를 허용하려면 시작 전에 `TRADE_LEDGER_JOURNAL_ROOT`를 설정합니다. 아래 절대 경로는 실제 존재하는 저널 디렉터리로 바꿉니다.

```bash
TRADE_LEDGER_JOURNAL_ROOT=/absolute/path/to/journals ./gradlew bootRun
```

MCP 클라이언트에서도 작업 디렉터리를 프로젝트 루트로 지정하고 같은 환경변수를 서버 프로세스에 전달합니다. stdio 연결에는 빌드한 실행 JAR을 직접 실행할 수도 있습니다.

```bash
./gradlew bootJar
TRADE_LEDGER_JOURNAL_ROOT=/absolute/path/to/journals java -jar build/libs/trade-ledger-mcp-0.0.1-SNAPSHOT.jar
```

클라이언트에는 위 `java` 명령과 인자, 저널 루트 환경변수를 설정합니다. 설정 키는 클라이언트마다 다릅니다. `application.properties`는 배너와 애플리케이션 콘솔 로깅을 끄고 stdout을 MCP JSON-RPC 메시지용으로 예약합니다. Gradle 실행 로그가 stdio 연결에 섞이지 않도록 클라이언트에서 직접 실행 JAR을 사용하는 방식을 권장합니다.

## CSV 저널 계약

[CsvTradeSchema](src/main/java/io/github/lmj/tradeledger/adapter/csv/CsvTradeSchema.java)와 [CsvTradeJournalReader](src/main/java/io/github/lmj/tradeledger/adapter/csv/CsvTradeJournalReader.java)가 계약을 정의합니다. UTF-8 CSV의 첫 행에는 다음 **12개 필수 열을 정확히 한 번씩** 넣습니다. 열 재정렬은 허용되며 각 행의 값도 해당 헤더 순서에 맞춰야 합니다. 누락·추가·중복 헤더와 행의 열 개수 불일치는 거부합니다. 빈 줄은 무시하고 값의 앞뒤 공백은 제거합니다. 모든 필드 값은 필수이며 공백만 있는 값도 거부합니다.

| CSV 열 | 의미와 제약 |
| --- | --- |
| `trade_id` | 저널 안에서 유일한 완결 거래 ID. 중복 ID는 거부 |
| `symbol` | 종목 식별자. 대문자로 정규화하며 `[A-Z0-9][A-Z0-9._:/-]{0,31}` 허용 |
| `market` | `STOCK` 또는 `CRYPTO` |
| `direction` | `LONG` 또는 `SHORT` |
| `quantity` | 0보다 큰 수량 |
| `entry_price` | 0보다 큰 진입 단가 |
| `exit_price` | 0보다 큰 청산 단가 |
| `fees` | 거래 전체 수수료 금액. 0 이상이며 수량을 다시 곱하지 않음 |
| `currency` | 진입·청산 단가와 수수료의 공통 통화. 대문자 정규화 후 영문 대문자·숫자 2~10자(예: `USDT`, `KRW`) |
| `opened_at` | 오프셋을 포함한 ISO-8601 진입 시각 |
| `closed_at` | 오프셋을 포함한 ISO-8601 청산 시각. 진입 시각보다 엄격히 뒤여야 함 |
| `strategy` | 비어 있지 않은 전략명 |

`market`과 `direction`은 대소문자를 구분하지 않습니다. 수량·단가·수수료는 일반 십진수 표기(`[+-]?\d+(?:\.\d+)?`)를 사용합니다. 지수 표기, 천 단위 쉼표, `.5`는 허용하지 않습니다. 시각은 `2026-07-01T01:00:00Z` 또는 `2026-07-01T10:00:00+09:00`처럼 작성하며 내부에서 `Instant`로 변환합니다. 오프셋 없는 현지 시각은 허용하지 않습니다.

CSV의 `quantity`, `entry_price`, `exit_price`, `fees`는 각각 **부호와 소수점을 포함해 최대 128자**입니다. Commons CSV의 기존 `setTrim(true)` 설정으로 앞뒤 공백을 제거한 필드값을 기준으로 매퍼에서 정규식 검사와 `BigDecimal` 생성 전에 길이를 검사합니다. 원문 바이트 길이 제한이 아니며, 기존 파일·스트림 바이트 상한 및 구조화 입력 제한은 별도로 유지됩니다. 128자를 초과하면 입력값을 오류 메시지에 넣지 않고 해당 행 번호·필드명과 함께 `INVALID_FIELD`로 거부합니다.

사용자 정의 형식의 한 행 예시입니다. 공식 서비스 내보내기 파일 예시가 아닙니다.

```csv
trade_id,symbol,market,direction,quantity,entry_price,exit_price,fees,currency,opened_at,closed_at,strategy
trade-001,BTC-USDT,CRYPTO,LONG,0.1234,60000.25,62000.75,5.37,USDT,2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout
```

저널 전체는 단일 통화여야 합니다. 서로 다른 통화의 행을 섞으면 거부하며 자동 환산하지 않습니다. 기본 파일 크기 상한은 **5 MiB(5,242,880바이트)**, 거래 수는 **1~10,000건**입니다. 상한과 같은 크기는 허용하고 초과하면 거부합니다. 현재 설정은 기본 생성자를 사용하며 이 제한을 조절하는 환경변수는 제공하지 않습니다.

도구에 전달하는 저널명은 확장자·경로 없는 이름입니다. `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`(1~64자)만 허용하고 `<저널 루트>/<이름>.csv`를 읽습니다. 예를 들어 `sample-trades`가 `sample-trades.csv`에 대응합니다. 루트와 파일은 존재해야 하며 일반 파일만 읽습니다. 루트 밖을 가리키는 심볼릭 링크도 거부합니다.

잘못된 저널은 부분 집계 대신 실패합니다. CSV 리더의 오류 범주에는 `INVALID_HEADER`, `INVALID_FIELD`, `EMPTY_JOURNAL`, `DUPLICATE_TRADE_ID`, `MIXED_CURRENCY`, `FILE_TOO_LARGE`, `TOO_MANY_TRADES` 등이 있으며 가능한 경우 물리적 행 번호와 필드명이 예외에 포함됩니다. 이는 내부 예외 계약이며 MCP 오류 응답의 별도 JSON 구조를 보장하는 설명은 아닙니다.

## MCP 도구 입력·출력 예시

두 도구 모두 읽기 전용입니다. 아래 요청은 MCP 초기화 이후 `tools/call`에 전달할 `params` 예시이며, 출력은 응답 DTO의 JSON 데이터 예시입니다. 전체 JSON-RPC 요청·응답 봉투와 MCP `content` 등 전송 계층 필드는 생략했습니다. 금액 출력은 정밀도 손실을 피하도록 일반 십진수 **문자열**이며 건수는 정수입니다.

### `summarize_journal`

허용된 루트의 CSV를 읽어 완결 거래를 집계합니다. `journalName`은 필수입니다.

```json
{
  "name": "summarize_journal",
  "arguments": {
    "journalName": "sample-trades"
  }
}
```

다음은 기존 [CsvJournalSummaryIntegrationTest](src/test/java/io/github/lmj/tradeledger/application/service/CsvJournalSummaryIntegrationTest.java)의 표본 기대값을 [JournalSummaryResponse](src/main/java/io/github/lmj/tradeledger/mcp/JournalSummaryResponse.java) 필드에 맞춰 표현한 것입니다. 기대 순손익 **355.78645 USDT는 기존 테스트의 기대값이며 이번 실행 결과가 아닙니다.** 테스트 리소스와 `journals/sample-trades.csv`에는 같은 세 거래가 들어 있습니다.

```json
{
  "journalName": "sample-trades",
  "tradeCount": 3,
  "winningTrades": 2,
  "losingTrades": 1,
  "breakEvenTrades": 0,
  "currency": "USDT",
  "totalGrossPnl": "367.29645",
  "totalFees": "11.51",
  "totalNetPnl": "355.78645"
}
```

### `analyze_trades`

CSV 파일을 읽지 않고 요청의 `trades`를 분석합니다. 요청당 **1~100건**의 완결 거래를 받습니다. CSV의 snake_case 열 이름과 달리 [TradeInput](src/main/java/io/github/lmj/tradeledger/mcp/TradeInput.java)은 camelCase 필드를 사용합니다. 아래 12개 필드는 모두 필수이며 수량·단가·수수료를 포함해 각 필드 값은 문자열입니다. CSV와 같은 도메인 제약을 적용하고, 요청 내 중복 ID 및 혼합 통화는 거부합니다.

```json
{
  "name": "analyze_trades",
  "arguments": {
    "trades": [
      {
        "tradeId": "trade-1",
        "symbol": "BTC-USDT",
        "market": "CRYPTO",
        "direction": "LONG",
        "quantity": "0.5",
        "entryPrice": "60000.25",
        "exitPrice": "61000.75",
        "fees": "2.125",
        "currency": "USDT",
        "openedAt": "2026-08-01T00:00:00Z",
        "closedAt": "2026-08-01T01:00:00Z",
        "strategy": "breakout"
      }
    ]
  }
}
```

기존 [TradeLedgerToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/TradeLedgerToolsTest.java)의 입력과 계산 기대값을 바탕으로 한 출력 예시입니다. 이번 실행 결과가 아닙니다.

```json
{
  "tradeCount": 1,
  "winningTrades": 1,
  "losingTrades": 0,
  "breakEvenTrades": 0,
  "currency": "USDT",
  "totalGrossPnl": "500.25",
  "totalFees": "2.125",
  "totalNetPnl": "498.125"
}
```

두 도구의 총손익은 LONG이면 `(청산 단가 - 진입 단가) × 수량`, SHORT이면 `(진입 단가 - 청산 단가) × 수량`입니다. 순손익은 총손익에서 수수료를 뺀 값이며 승리·손실·본전 건수는 각 거래의 **순손익** 부호로 분류합니다. `analyze_trades` 출력에는 `journalName`이 없습니다.

## 현재 Trade로 표현할 수 있는 범위

[Trade](src/main/java/io/github/lmj/tradeledger/domain/model/Trade.java)는 수량 하나, 진입·청산 단가 하나씩, 동일 통화의 총수수료와 진입·청산 시각을 가진 완결 거래입니다. 개별 체결을 완결 거래로 간주하거나 미청산 거래에 임의 청산값을 넣을 수 없습니다. 분할 체결·부분 청산을 묶는 규칙, 원가 배분 및 포지션 매칭은 구현되어 있지 않습니다. 다른 통화로 낸 수수료도 통화 이름만 바꾸어 입력할 수 없고, 환율·기준 시각·출처를 정한 환산 기능은 없습니다. 따라서 원본 체결이나 서비스 파일을 이 형식으로 임의 변환해서는 안 됩니다. `SHORT` 값 지원이 특정 서비스의 공매도·파생상품 연동을 의미하지도 않습니다.

## 서비스별 미확인 상태와 다음 확인 자료

제공된 코드에는 로컬 CSV 어댑터만 있으며 아래 세 서비스의 어댑터나 공식 API·내보내기 규격 자료는 없습니다. 외부 접근 없이 작성했으므로 공식 지원 방식의 존재 여부·접근 대상·사용 조건을 판단할 수 없습니다. 아래 “미확인”은 지원 불가 판정이 아닙니다.

| 서비스 | 공식 방식 확인 | 어댑터 구현 | 서비스 모의 검증 | 실제 연동 검증 | 다음 확인에 필요한 공식 문서·비식별 파일 예시 |
| --- | --- | --- | --- | --- | --- |
| 업비트 | 공식 API 또는 내보내기 지원 방식 미확인 | 미구현 | 미수행 | 미검증 | 공식 거래·체결 내역 조회/API 권한 및 파일 내보내기 안내, 페이지 처리·호출 제한 규정. 제공 가능한 경우 원본 헤더·수수료 통화·파일 시간대가 보존된 비식별 체결 내역 파일과 분할 체결 예시 |
| 바이낸스 | 공식 API 또는 내보내기 지원 방식 미확인 | 미구현 | 미수행 | 미검증 | 대상 상품(현물·파생 등)을 구분한 공식 거래 내역/API·파일 내보내기 안내와 조회 전용 권한·페이지 처리·호출 제한 문서. 제공 가능한 경우 상품 유형·수수료 자산·시간대가 보존된 비식별 파일 및 부분 청산 예시 |
| 토스증권 | 공식 API 또는 내보내기 지원 방식 미확인 | 미구현 | 미수행 | 미검증 | 공식 거래 내역 조회·내보내기 및 API 제공 여부·이용 대상 안내, 조회 전용 권한·페이지 처리·호출 제한 규정(해당 방식이 있는 경우). 제공 가능한 경우 국내·해외 구분, 거래·수수료 통화, 파일 시간대가 보존된 비식별 거래 내역 파일 |

서비스별로 다음 단계를 구분해 진행해야 합니다.

1. **공식 방식 확인:** 공식 제공 채널과 문서의 버전·지원 대상·이용 조건을 확인합니다. API가 있다면 주문·자금 이동 권한 없이 조회 전용 권한으로 필요한 내역을 얻을 수 있는지, 조회 기간·페이지 처리·호출 제한, ID 안정성·중복 가능성, 수수료 통화와 시각 단위를 확인합니다. 파일 방식은 실제 헤더·인코딩·구분자·파일 시간대와 오프셋 유무를 확인합니다. API나 파일 제공 자체를 미리 가정하지 않습니다.
2. **모의 검증:** 공식 규격과 비식별 표본을 확보한 뒤 어댑터와 변환 규칙을 구현하고, 페이지 경계 중복·누락, 호출 제한 처리, 부분 체결·미청산 거래, 다른 통화 수수료, 파일 시간대 해석을 검증합니다. 현재 CSV·계산 테스트는 프로젝트 자체 형식에 대한 것이며 서비스 어댑터의 모의 검증 근거가 아닙니다.
3. **실제 연동 검증:** 공식 방식과 모의 검증을 마친 후 별도로 승인된 환경에서 조회 전용 접근으로 기간별 원본 내역과 거래 수·수수료·손익을 대조해야 합니다. 모의 검증 통과만으로 실제 연동 완료를 선언할 수 없습니다. 현재 세 서비스 모두 이 단계는 미검증입니다.

다음 확인용 파일은 원본의 열 이름·형식·통화·시간대 의미를 유지하면서 개인·계좌 식별정보를 제거하고 거래 식별자를 일관된 가명으로 바꾼 예시가 필요합니다. 인증정보, API 키·시크릿, 토큰 등 비밀값은 문서나 표본 파일에 포함하지 않습니다. 이 문서는 공식 자료나 실제 연동 검증을 대체하지 않습니다.
