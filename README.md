# trade-ledger-mcp

완결된 거래의 실현 손익을 계산하고 업비트 단건 주문을 조회하는 읽기 전용 MCP 서버입니다. 로컬 CSV 저널을 요약하는 `summarize_journal`, 구조화된 완결 거래를 분석하는 `analyze_trades`, 주문 UUID로 주문·실제 체결을 조회하는 `get_upbit_order`를 제공합니다. 주문 실행·취소, 입출금·이체 등 자금 이동 기능은 없습니다.

두 계산 도구의 입력은 프로젝트가 정의한 **사용자 정의 완결 거래 형식**입니다. 업비트 조회 응답은 별도 주문·체결 스냅샷으로 노출하며 완결 거래나 손익으로 변환하지 않습니다. 바이낸스·토스증권의 공식 API·내보내기 형식은 여전히 미확인입니다. 이번 작업은 제공 규격과 저장소 코드에 근거하며 외부 문서 열람·실계정 접근은 하지 않았습니다. 서버 테스트와 독립 검토는 대기 상태입니다.

## 설치·빌드·실행

Java 21 JDK를 설치하고 `JAVA_HOME`과 `PATH`가 해당 JDK를 가리키도록 설정합니다. 프로젝트 루트에서 포함된 Gradle Wrapper를 사용하므로 별도 Gradle 설치는 필요하지 않습니다. 최초 실행 시 Gradle 배포본과 Maven Central 의존성 다운로드가 필요할 수 있습니다. 다음은 사용자가 실행할 명령이며 이번 작업의 실행 결과가 아닙니다.

```bash
java -version
JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' ./gradlew test
JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' ./gradlew build
JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' ./gradlew bootRun
```

`build.gradle`은 Java 21 도구 체인과 JUnit Platform을 지정합니다. `bootRun`은 HTTP 서버가 아닌 동기식 stdio MCP 서버를 시작합니다. MCP 클라이언트가 프로세스의 표준 입력·출력으로 연결해야 하며, 일반 셸에 JSON만 입력하는 것으로 MCP 초기화 절차를 대신할 수는 없습니다.

기본 저널 루트는 프로세스 작업 디렉터리 기준 `./journals`입니다. 저장소 루트에서 실행하면 포함된 `journals/sample-trades.csv`를 사용할 수 있습니다. 다른 디렉터리를 허용하려면 시작 전에 `TRADE_LEDGER_JOURNAL_ROOT`를 설정합니다. 아래 절대 경로는 실제 존재하는 저널 디렉터리로 바꿉니다.

```bash
TRADE_LEDGER_JOURNAL_ROOT=/absolute/path/to/journals ./gradlew bootRun
```

MCP 클라이언트에서도 작업 디렉터리를 프로젝트 루트로 지정하고 같은 환경변수를 서버 프로세스에 전달합니다. stdio 연결에는 빌드한 실행 JAR을 직접 실행할 수도 있습니다.

```bash
./gradlew bootJar
TRADE_LEDGER_JOURNAL_ROOT=/absolute/path/to/journals java \
  -Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1 \
  -jar build/libs/trade-ledger-mcp-0.0.1-SNAPSHOT.jar
```

클라이언트에는 위 `java` 명령과 인자, 저널 루트 환경변수를 설정합니다. 설정 키는 클라이언트마다 다릅니다. `application.properties`는 배너와 애플리케이션 콘솔 로깅을 끄고 stdout을 MCP JSON-RPC 메시지용으로 예약합니다. Gradle 실행 로그가 stdio 연결에 섞이지 않도록 클라이언트에서 직접 실행 JAR을 사용하는 방식을 권장합니다.

업비트 클라이언트는 첫 조회 시 생성합니다. **키가 없어도 서버 시작과 두 계산 도구의 등록·호출은 가능합니다.** 업비트 조회에는 서버 프로세스 환경변수 `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY`와 **주문조회 권한만** 필요합니다. 주문·출금 권한은 필요하지 않습니다. 비밀값은 프로세스 환경으로 전달하며 MCP 인자·예시 파일·명령행에 넣지 않습니다. 호스트와 URL은 환경변수나 도구 인자로 받지 않으며 목적지는 `https://api.upbit.com/v1/order`로 고정됩니다.

조회 프로세스는 시작부터 `-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1`을 지정해야 합니다. JDK HTTP 클라이언트의 암묵적 재전송까지 차단하기 위한 기존 보호 장치이며 시작 후 속성을 바꾸는 것으로 대체할 수 없습니다. 옵션이 없으면 조회는 `UNSAFE_TRANSPORT_CONFIGURATION`으로 실패하고 계산 도구는 계속 사용할 수 있습니다. 옵션이 올바르고 키가 없으면 `MISSING_CREDENTIALS`로 실패합니다. **자동 재시도와 리다이렉트 추적은 없습니다.** 테스트의 로컬 HTTP 클라이언트에도 같은 시작 옵션이 필요하므로 위 테스트 명령은 이를 테스트 JVM에도 전달합니다. `build`도 테스트를 실행하므로 같은 `JAVA_TOOL_OPTIONS`를 적용합니다.

직전 후보에 대해 제공된 최신 서버 기록은 Gradle `test` 종료 코드 1, **248건 중 1건 실패**입니다. `UpbitOrderToolsTest.executionError`에서 Spring 테스트 종료 처리 중 `LifecycleProcessor not initialized`가 발생했습니다. 해당 테스트의 `@AfterAll`이 관리 컨텍스트를 직접 닫은 뒤 Spring의 컨텍스트 캐시 관리가 이를 다시 사용한 경로와 부합합니다. 이번 후보는 직접 종료를 제거하고 `@DirtiesContext`가 컨텍스트와 로컬 대역의 HTTP 자원을 정리하도록 맡깁니다. 테스트 리스너는 정리 전 보관한 대역 참조로 Spring 종료 처리 후 HTTP 클라이언트의 종료 상태를 검사하며, 종료된 컨텍스트를 다시 조회하지 않습니다. 기존 종료 상태 단언과 성공 `content`·`structuredContent` 일치, 오류·정밀도 검증은 유지합니다. 이번 개발 단계에서 테스트를 실행하지 않았으며, 수정 후 `./gradlew test` 결과와 독립 검토 결과는 서버 검증 대기입니다.

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

세 도구 모두 읽기 전용입니다. 아래 요청은 MCP 초기화 이후 `tools/call`에 전달할 `params` 예시이며, 출력은 응답 DTO의 JSON 데이터 예시입니다. 전체 JSON-RPC 요청·응답 봉투와 MCP `content` 등 전송 계층 필드는 생략했습니다. 금액 출력은 정밀도 손실을 피하도록 일반 십진수 **문자열**이며 건수는 정수입니다.

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

### `get_upbit_order`

필수 인자는 정규 UUID 문자열 `uuid` 하나입니다. 키·시크릿·호스트·임의 URL을 받지 않습니다. 등록 힌트는 `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`입니다. 외부 조회가 필요한 도구이며 주문이나 자금 이동 도구는 등록하지 않습니다.

```json
{
  "name": "get_upbit_order",
  "arguments": {"uuid": "123e4567-e89b-12d3-a456-426614174000"}
}
```

아래는 가상의 단일 체결을 가진 응답 데이터 예시이며 실계정 조회 결과가 아닙니다. MCP 성공 결과의 `structuredContent`에는 주문 객체를, `content`에는 같은 객체를 JSON으로 직렬화한 단일 `TextContent`를 제공합니다. 수량·가격·체결 금액·수수료는 소수 자릿수를 보존하는 문자열이고 시각은 UTC ISO-8601 문자열입니다.

```json
{
  "serviceId": "upbit",
  "orderId": "123e4567-e89b-12d3-a456-426614174000",
  "market": "KRW-BTC",
  "side": "bid",
  "orderType": "limit",
  "state": "done",
  "createdAt": "2026-07-01T01:00:00Z",
  "executedVolume": "0.10000000",
  "paidFee": "50.00000000",
  "trades": [
    {
      "tradeId": "example-fill-1",
      "market": "KRW-BTC",
      "side": "bid",
      "price": "1000000.00",
      "volume": "0.10000000",
      "funds": "100000.0000000000",
      "createdAt": "2026-07-01T01:00:01Z"
    }
  ]
}
```

`paidFee`는 **주문 전체 수수료**입니다. 체결별로 배분하거나 체결 수만큼 더하지 않습니다. 제공된 데이터만으로 수수료 통화를 확정하지 않아 수수료 통화 필드는 제공하지 않으며 시장 코드로 추정하지 않습니다. 체결이 없으면 `trades`는 빈 배열입니다. 매수·매도 매칭, 원가 배분, 완결 거래 생성과 실현손익 계산은 이 조회 도구에 구현되어 있지 않습니다.

오류는 `isError=true`와 `Upbit request failed: <안전한 범주>` 메시지로 반환합니다. `INVALID_UUID`, `MISSING_CREDENTIALS`, `AUTHENTICATION_FAILED`, `PERMISSION_DENIED`, `ORDER_NOT_FOUND`, `RATE_LIMITED`, `NETWORK_ERROR`, `TIMEOUT`, `INVALID_RESPONSE` 등이 있으며 알려지지 않은 내부 실패는 `INTERNAL_ERROR`로 처리합니다. 클라이언트 초기화 예외도 원인을 제거한 뒤 전달해 키·JWT·원문 응답과 파서 예외가 MCP 오류 원인으로 따라가지 않게 합니다. 오류의 `structuredContent`에 별도 오류 객체가 존재한다고 가정하지 않습니다.

프로젝트 상한은 연결 **3초**, 본문 수신까지 요청 전체 **10초**, 응답 본문 **1 MiB(1,048,576바이트)**, 주문당 체결 **0~1,000건**, 십진 문자열 **128자**, 기타 원문 문자열 **256자**입니다. UUID는 공백 없이 36자이며 초과 데이터는 부분 반환하지 않습니다. 이 상한은 업비트의 공식 서비스 상한과 구별됩니다. **단건 조회만 지원**하며 주문 목록·페이지 처리·기간별 수집·자동 재시도·호출 속도 조절기는 구현하지 않았습니다.

제공 규격에 따른 공식 호출 제한은 **`default` 그룹 초당 30회 공유 한도**입니다. 이 단건 주문 조회만의 전용 30회가 아니며 같은 계정의 해당 그룹 API 사용량과 공유합니다. 호출자는 다른 클라이언트의 사용량도 함께 조정해야 하며 429는 `RATE_LIMITED`로 반환합니다.

근거를 식별하는 공식 문서 링크는 [개별 주문 조회](https://docs.upbit.com/kr/reference/get-order), [인증](https://docs.upbit.com/kr/reference/auth), [호출 수 제한](https://docs.upbit.com/kr/reference/rate-limits)입니다. 제공된 규격의 `GET /v1/order?uuid=...`, 주문조회 권한, HS512 JWT의 `access_key`·새 `nonce`·미인코딩 쿼리 SHA-512 해시를 구현 기준으로 삼았습니다. 시크릿은 Base64 디코딩하지 않습니다. 이번 작업에서는 외부 링크의 최신 내용과 실계정 동작을 확인하지 않았습니다.

추가한 [UpbitOrderToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/UpbitOrderToolsTest.java)는 가짜 키와 로컬 HTTP 대역을 실제 등록 MCP `callHandler`에 연결하도록 작성했습니다. 체결·주문 수수료·정밀도, 키 미설정·잘못된 UUID·인증·권한·조회·호출 제한·네트워크·파싱 오류를 검사하고, `content`·`structuredContent`와 수집한 애플리케이션/MCP 로그에 키·JWT·원문 표식이 없는지 검사합니다. 이번 회귀 검증은 기존의 비어 있지 않은 `content` 검증을 유지하며, 체결이 있는 주문과 없는 주문 모두 텍스트 JSON 전체가 `structuredContent`와 같은지도 확인합니다. 테스트 작성은 통과 증거가 아니며 수정 후 실행 결과는 서버 검증 대기입니다.

## 현재 Trade로 표현할 수 있는 범위

[Trade](src/main/java/io/github/lmj/tradeledger/domain/model/Trade.java)는 수량 하나, 진입·청산 단가 하나씩, 동일 통화의 총수수료와 진입·청산 시각을 가진 완결 거래입니다. 개별 체결을 완결 거래로 간주하거나 미청산 거래에 임의 청산값을 넣을 수 없습니다. 분할 체결·부분 청산을 묶는 규칙, 원가 배분 및 포지션 매칭은 구현되어 있지 않습니다. 다른 통화로 낸 수수료도 통화 이름만 바꾸어 입력할 수 없고, 환율·기준 시각·출처를 정한 환산 기능은 없습니다. 따라서 원본 체결이나 서비스 파일을 이 형식으로 임의 변환해서는 안 됩니다. `SHORT` 값 지원이 특정 서비스의 공매도·파생상품 연동을 의미하지도 않습니다.

## 서비스별 미확인 상태와 다음 확인 자료

업비트는 제공된 단건 주문 조회 규격에 한해 어댑터와 MCP 연결 후보를 구현했습니다. 공식 규격 제공, 모의 테스트 작성·실행, 실계정 대조는 서로 다른 상태입니다. 바이낸스·토스증권은 외부 접근 없이 공식 방식의 존재 여부·접근 대상·사용 조건을 판단할 수 없습니다. 아래 “미확인”은 지원 불가 판정이 아닙니다.

| 서비스 | 공식 방식 확인 | 어댑터 구현 | 서비스 모의 검증 | 실제 연동 검증 | 다음 확인에 필요한 공식 문서·비식별 파일 예시 |
| --- | --- | --- | --- | --- | --- |
| 업비트 | 단건 주문 조회·인증·호출 제한 규격 제공. 최신 외부 문서 재확인 미수행 | 단건 주문·체결 조회 및 MCP 연결 구현 후보. 매수·매도 매칭·실현손익 미구현 | 어댑터 및 등록 MCP 핸들러 모의 테스트 작성. 직전 서버 실행 248건 중 종료 처리 1건 실패, 수정 후 재검증 대기 | 미검증 | 최신 공식 주문조회 권한·default 공유 한도 확인, 조회 전용 실계정 원본과 UUID·체결·주문 수수료 대조. 확장 시 목록·페이지 규격과 수수료 통화·시간대가 보존된 비식별 분할 체결 예시 |
| 바이낸스 | 공식 API 또는 내보내기 지원 방식 미확인 | 미구현 | 미수행 | 미검증 | 대상 상품(현물·파생 등)을 구분한 공식 거래 내역/API·파일 내보내기 안내와 조회 전용 권한·페이지 처리·호출 제한 문서. 제공 가능한 경우 상품 유형·수수료 자산·시간대가 보존된 비식별 파일 및 부분 청산 예시 |
| 토스증권 | 공식 API 또는 내보내기 지원 방식 미확인 | 미구현 | 미수행 | 미검증 | 공식 거래 내역 조회·내보내기 및 API 제공 여부·이용 대상 안내, 조회 전용 권한·페이지 처리·호출 제한 규정(해당 방식이 있는 경우). 제공 가능한 경우 국내·해외 구분, 거래·수수료 통화, 파일 시간대가 보존된 비식별 거래 내역 파일 |

서비스별로 다음 단계를 구분해 진행해야 합니다.

1. **공식 방식 확인:** 공식 제공 채널과 문서의 버전·지원 대상·이용 조건을 확인합니다. API가 있다면 주문·자금 이동 권한 없이 조회 전용 권한으로 필요한 내역을 얻을 수 있는지, 조회 기간·페이지 처리·호출 제한, ID 안정성·중복 가능성, 수수료 통화와 시각 단위를 확인합니다. 파일 방식은 실제 헤더·인코딩·구분자·파일 시간대와 오프셋 유무를 확인합니다. API나 파일 제공 자체를 미리 가정하지 않습니다.
2. **모의 검증:** 공식 규격과 비식별 표본을 확보한 뒤 어댑터와 변환 규칙을 구현하고, 페이지 경계 중복·누락, 호출 제한 처리, 부분 체결·미청산 거래, 다른 통화 수수료, 파일 시간대 해석을 검증합니다. 현재 CSV·계산 테스트는 프로젝트 자체 형식에 대한 것이며 서비스 어댑터의 모의 검증 근거가 아닙니다.
3. **실제 연동 검증:** 공식 방식과 모의 검증을 마친 후 별도로 승인된 환경에서 조회 전용 접근으로 기간별 원본 내역과 거래 수·수수료·손익을 대조해야 합니다. 모의 검증 통과만으로 실제 연동 완료를 선언할 수 없습니다. 현재 세 서비스 모두 이 단계는 미검증입니다.

다음 확인용 파일은 원본의 열 이름·형식·통화·시간대 의미를 유지하면서 개인·계좌 식별정보를 제거하고 거래 식별자를 일관된 가명으로 바꾼 예시가 필요합니다. 인증정보, API 키·시크릿, 토큰 등 비밀값은 문서나 표본 파일에 포함하지 않습니다. 이 문서는 공식 자료나 실제 연동 검증을 대체하지 않습니다.
