# trade-ledger-mcp

완결된 거래의 실현 손익을 계산하고 업비트 주문·바이낸스 현물 체결·토스 주문 요약을 조회하는 읽기 전용 MCP 서버입니다. 로컬 CSV 저널을 요약하는 `summarize_journal`, 구조화된 완결 거래를 분석하는 `analyze_trades`, 주문 UUID로 주문·실제 체결을 조회하는 `get_upbit_order`, 종료 주문 요약을 조회하는 `get_upbit_closed_orders`, 바이낸스 단일 symbol 최근 체결을 조회하는 `get_binance_account_trades`, 업비트 단건 주문을 공통 체결 형식으로 조회하는 `get_upbit_order_executions`, 바이낸스 최근·필터 체결을 공통 형식으로 조회하는 `get_binance_account_executions`, 토스 단건 주문·누적 체결 요약을 조회하는 `get_toss_order`, 토스 종료 주문 한 페이지를 조회하는 `get_toss_closed_orders`를 제공합니다. 등록 도구는 이 아홉 개이며 주문 실행·취소, 입출금·이체 등 자금 이동 기능은 없습니다.

두 계산 도구의 입력은 프로젝트가 정의한 **사용자 정의 완결 거래 형식**입니다. 거래소 조회 응답은 별도 주문·체결 또는 종료 주문 요약 스냅샷으로 노출하며 완결 거래나 손익으로 변환하지 않습니다. `get_binance_account_executions`는 선택적 문자열 `fromId`·`startTime`·`endTime`·`orderId` 필터를 받고 `RECENT_SYMBOL`과 `FILTERED_SYMBOL`을 구분합니다. 업비트 종료 주문의 시작·종료 시간 경계는 각각 독립적으로 지정할 수 있습니다. 토스증권은 기존 요청·누적 체결 요약 모델·매퍼·읽기 전용 HTTP 클라이언트를 지연 Spring 빈과 MCP에 연결합니다. 실계정·조회 전용 권한 분리는 미검증입니다.

## 설치·빌드·실행

Java 21 JDK를 설치하고 `JAVA_HOME`과 `PATH`가 해당 JDK를 가리키도록 설정합니다. 프로젝트 루트에서 포함된 Gradle Wrapper를 사용하므로 별도 Gradle 설치는 필요하지 않습니다. 최초 실행 시 Gradle 배포본과 Maven Central 의존성 다운로드가 필요할 수 있습니다. 다음 명령으로 테스트·빌드·실행합니다.

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

업비트와 바이낸스 클라이언트는 각각 첫 조회 시 생성하며 생성된 클라이언트는 Spring 컨텍스트 종료 시 닫습니다. **키나 전송 시작 옵션이 없어도 두 클라이언트를 생성하지 않고 서버 시작과 두 계산 도구의 등록·호출은 가능합니다.** 업비트 조회에는 서버 프로세스 환경변수 `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY`와 **주문조회 권한만** 필요합니다. 주문·출금 권한은 필요하지 않습니다. 비밀값은 프로세스 환경으로 전달하며 MCP 인자·예시 파일·명령행에 넣지 않습니다. 호스트와 URL은 환경변수나 도구 인자로 받지 않습니다. 업비트 목적지는 단건 조회의 `https://api.upbit.com/v1/order`와 종료 주문 요약의 `https://api.upbit.com/v1/orders/closed` 두 고정 GET 경로입니다.

바이낸스 조회는 서버를 실행하는 환경의 비밀정보 설정 기능으로 아래 두 환경변수를 지정한 뒤 같은 서버 프로세스에 전달합니다. 조회 전용 키를 사용하며 주문·자금 이동 권한을 부여할 필요가 없습니다. 키는 도구 호출마다 서버 환경에서 읽고 별칭으로 선택하지 않습니다.

| 환경변수 | 용도 |
| --- | --- |
| `BINANCE_API_KEY` | 바이낸스 현물 계정 조회용 API 키. `X-MBX-APIKEY` 헤더로 전송 |
| `BINANCE_SECRET_KEY` | 쿼리 HMAC-SHA256 서명용 시크릿. 원문은 전송하지 않음 |

바이낸스 목적지는 고정된 `https://api.binance.com/api/v3/myTrades`입니다. 두 변수를 설정한 서버도 위 실행 JAR 예시의 두 `-D` 시작 옵션이 필요합니다. `accountAlias`에는 키·시크릿·계좌 식별정보를 넣지 않습니다.

조회 프로세스는 시작부터 `-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1`을 지정해야 합니다. JDK HTTP 클라이언트의 암묵적 재전송까지 차단하기 위한 기존 보호 장치이며 시작 후 속성을 바꾸는 것으로 대체할 수 없습니다. 옵션이 없으면 조회는 `UNSAFE_TRANSPORT_CONFIGURATION`으로 실패하고 계산 도구는 계속 사용할 수 있습니다. 옵션이 올바르고 키가 없으면 `MISSING_CREDENTIALS`로 실패합니다. **자동 재시도와 리다이렉트 추적은 없습니다.** 테스트의 로컬 HTTP 클라이언트에도 같은 시작 옵션이 필요하므로 위 테스트 명령은 이를 테스트 JVM에도 전달합니다. `build`도 테스트를 실행하므로 같은 `JAVA_TOOL_OPTIONS`를 적용합니다.

**HTTP 진단 로깅을 활성화하지 마십시오.** `jdk.httpclient.HttpClient.log`의 요청·헤더·본문 로깅은 API 키·JWT·서명·응답 원문을 노출할 수 있습니다. 바이낸스와 업비트 클라이언트는 이 속성의 비어 있지 않은 현재 설정 또는 JVM 시작 인자를 원인 예외 없이 `UNSAFE_TRANSPORT_CONFIGURATION`으로 거부합니다. 업비트는 단건 주문(`get_upbit_order`)과 종료 주문(`get_upbit_closed_orders`) 모두 HTTP 클라이언트 생성 전, 유효 조회의 인증정보 조회·서명 전, 실제 전송 직전에 검사합니다. 시작 후 지우거나 뒤에 빈 설정을 덧붙여도 비어 있지 않았던 시작 설정을 허용하지 않습니다. 미설정 또는 빈 값만 허용하며 **프로세스 실행 중 진단 로깅 설정을 변경하지 않는 운영 조건**이 필요합니다. 검사 사이의 임의 동시 속성 변경까지 방지하지는 않습니다. 애플리케이션 콘솔 로깅을 끄는 것만으로 JDK HTTP 진단 로깅을 차단할 수는 없습니다. [Java 21 공식 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html)는 진단 로깅 대상과 실행 중 `System.setProperty()` 적용이 보장되지 않음을 설명합니다.

## 검증 범위와 연동 한계

`build.gradle`의 `test` 진입점은 JUnit Platform을 사용하며 독립 JVM 검사에 테스트 런타임 클래스 경로를 전달합니다. Java 21과 두 필수 시작 옵션을 적용해 전체 테스트를 실행합니다. Gradle과 의존성 캐시를 미리 준비한 환경에서는 다음과 같이 오프라인으로 실행할 수 있습니다. 환경별 메모리·CPU 제한과 초기화 스크립트는 해당 실행 환경에서 별도로 적용해야 합니다.

```bash
JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' \
  ./gradlew --offline --no-daemon --max-workers=1 --no-watch-fs --no-build-cache --console=plain test
```

순수 모델·로컬 HTTP 대역·등록 MCP 핸들러·독립 JVM 회귀 테스트는 입력 검증, 전송·응답 제한과 안전한 오류를 검사합니다. 테스트 소스의 존재는 특정 실행의 통과를 뜻하지 않으며 실행별 결과와 독립 검토는 별도 보고서에서 관리합니다. 업비트·바이낸스·토스증권의 실계정 대조는 미검증입니다. 공식 기간 기본 동작의 실계정 대조, 전체 내역 수집, 매수·매도 매칭과 조회 응답의 손익 계산 완료를 보장하지 않습니다.

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

아홉 도구 모두 읽기 전용입니다. 아래 요청은 MCP 초기화 이후 `tools/call`에 전달할 `params` 예시이며, 출력은 응답 DTO의 JSON 데이터 예시입니다. 전체 JSON-RPC 요청·응답 봉투와 MCP `content` 등 전송 계층 필드는 생략했습니다. 금액 출력은 정밀도 손실을 피하도록 일반 십진수 **문자열**입니다. 계산 도구의 건수는 정수이고 종료 주문의 `tradesCount`와 바이낸스의 `id`·`orderId`·`orderListId`는 큰 정수의 정밀도를 보존하는 십진 문자열입니다.

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

401·403 오류 본문은 클라이언트 내부에서 트리 생성 없이 토큰을 선형 순회합니다. 루트 객체의 `error` 객체 안에 직접 있는 문자열 `name`만 검사하고, `out_of_scope`, `insufficient_permission`, `insufficient_permissions`, `permission_denied`, `no_authorization_ip`를 `PERMISSION_DENIED`로 분류합니다. 이스케이프된 속성명과 문자열은 디코딩 후 같은 규칙을 적용하며 다른 위치의 `name`은 근거로 사용하지 않습니다. 이름을 먼저 찾더라도 **전체 단일 JSON 문서의 검증이 끝난 뒤** 판정합니다.

오류 본문의 프로젝트 파싱 정책은 **루트 객체 포함 깊이 64**, **디코딩된 문자열·속성명 256 UTF-16 코드 단위**, **정규화 전 숫자 원본 토큰 128 코드 단위**입니다. 무시되는 필드에도 설치된 Jackson의 `StreamReadConstraints`와 정확한 길이 검사를 적용하고, 모든 객체의 중복 키를 거부합니다. 중복 키·두 번째 문서·손상된 꼬리·잘못된 루트/`error`/`name` 유형·제한 초과·잘못된 UTF-8이면 본문 기반 판별을 포기합니다. 이때 401은 `AUTHENTICATION_FAILED`, 403은 `PERMISSION_DENIED`이며 400·404·418·429·3xx·5xx도 기존 HTTP 상태 범주를 유지합니다. 파서 진단·cause·suppressed 예외는 전달하거나 로그로 남기지 않습니다. 이 제한은 거래소 공식 규격이 아닌 프로젝트 정책이며 기존 1 MiB 전송 상한을 함께 적용합니다.

같은 처리는 두 HTTP 경로와 세 업비트 MCP 도구에 적용됩니다. 유효 조회 호출당 GET은 한 번입니다. 실패 응답은 `isError=true`이고 부분 `structuredContent`를 반환하지 않습니다. 클라이언트와 등록 핸들러 회귀 테스트는 정상·이스케이프 이름, 중복 키·후행 데이터, 무시되는 필드의 64/65·256/257·128/129 경계와 비밀값·파서 진단 비노출 검사를 포함합니다. 실계정 동작은 미검증이며 성능 개선 수치를 주장하지 않습니다.

프로젝트 상한은 연결 **3초**, 본문 수신까지 요청 전체 **10초**, 응답 본문 **1 MiB(1,048,576바이트)**, 단건 주문당 체결 **0~1,000건**, 십진 문자열 **128자**, 기타 원문 문자열 **256자**입니다. UUID는 공백 없이 36자이며 초과 데이터는 부분 반환하지 않습니다. 이 상한은 업비트의 공식 서비스 상한과 구별됩니다. 단건 조회와 아래의 한 기간 종료 주문 요약 조회를 지원하며 페이지 순회·기간 분할 수집·자동 재시도·호출 속도 조절기는 구현하지 않았습니다.

제공 규격에 따른 공식 호출 제한은 **`default` 그룹 초당 30회 공유 한도**입니다. 이 단건 주문 조회만의 전용 30회가 아니며 같은 계정의 해당 그룹 API 사용량과 공유합니다. 호출자는 다른 클라이언트의 사용량도 함께 조정해야 하며 429는 `RATE_LIMITED`로 반환합니다.

근거를 식별하는 공식 문서 링크는 [개별 주문 조회](https://docs.upbit.com/kr/reference/get-order), [인증](https://docs.upbit.com/kr/reference/auth), [호출 수 제한](https://docs.upbit.com/kr/reference/rate-limits)입니다. 제공된 규격의 `GET /v1/order?uuid=...`, 주문조회 권한, HS512 JWT의 `access_key`·새 `nonce`·미인코딩 쿼리 SHA-512 해시를 구현 기준으로 삼았습니다. 시크릿은 Base64 디코딩하지 않습니다. 이번 작업에서는 외부 링크의 최신 내용과 실계정 동작을 확인하지 않았습니다.

[UpbitOrderToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/UpbitOrderToolsTest.java)는 가짜 키와 로컬 HTTP 대역을 실제 등록 MCP `callHandler`에 연결합니다. 기존 단건 체결·주문 수수료·정밀도·오류 검사와 종료 주문의 기본값·선택 인자·시간 정규화·입력 경계·빈 배열·큰 정수 문자열·잘림 가능성·오류별 단일 전송 검사를 포함합니다. 성공 텍스트 JSON 전체와 `structuredContent`가 같은지, 응답과 수집한 애플리케이션/MCP 로그·예외 스택에 키·JWT·비공개 원문 표식이 없는지도 검사하도록 작성되어 있습니다.

### `get_upbit_order_executions`

여섯 번째 도구는 필수 `accountAlias`와 `uuid`만 받습니다. 키·시크릿·URL 인자는 없습니다. `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`로 등록합니다. UUID는 기존 단건 주문의 8-4-4-4-12 16진수 정책으로 검증하고 오류는 `INVALID_UUID`입니다. 별칭은 기존 바이낸스 도구·공통 모델과 같은 **최대 256 UTF-16 코드 단위** 정책을 사용합니다. 공백만 있는 값·ISO 제어문자·짝이 맞지 않는 surrogate·257 코드 단위 이상은 `BAD_REQUEST`로 거부하며 유효 비ASCII 문자·대소문자·앞뒤 공백은 그대로 보존합니다. 두 검사는 지연 클라이언트 생성·인증정보 조회·HTTP 전송 전에 수행합니다.

별칭은 비밀정보가 아닌 로컬 표시 이름입니다. 인증 계정을 선택하지 않으며 업비트로 전송하지 않습니다. 실제 계정은 서버 환경의 키로만 정해집니다. 같은 계정에는 일관된 별칭을 사용해야 하며 다른 별칭이 다른 인증 계정이라는 보장은 없습니다.

```json
{
  "name": "get_upbit_order_executions",
  "arguments": {"accountAlias": "spot-main", "uuid": "123e4567-e89b-12d3-a456-426614174000"}
}
```

다음은 가상 공통 출력이며 실계정 조회 결과가 아닙니다. 성공 `content`는 단일 JSON `TextContent`이고 파싱한 전체 값이 `structuredContent`와 같습니다. 금액은 후행 0과 정밀도를 보존하는 문자열이며 시각은 UTC ISO8601입니다.

```json
{
  "serviceId": "upbit",
  "accountAlias": "spot-main",
  "symbol": "KRW-BTC",
  "queryScope": "SINGLE_ORDER",
  "orderId": "123e4567-e89b-12d3-a456-426614174000",
  "possiblyTruncated": null,
  "executions": [{
    "serviceId": "upbit",
    "accountAlias": "spot-main",
    "symbol": "KRW-BTC",
    "executionId": "fill-1",
    "orderId": "123e4567-e89b-12d3-a456-426614174000",
    "side": "BUY",
    "price": "50000000.0000",
    "quantity": "0.10000000",
    "amount": "5000000.00000000",
    "executedAt": "2026-07-01T01:00:01Z"
  }],
  "fees": [{
    "scope": "ORDER",
    "targetId": "123e4567-e89b-12d3-a456-426614174000",
    "amount": "50.00000000",
    "currency": null
  }]
}
```

유효 요청은 기존 지연 `UpbitOrderClient.getOrder(uuid)`를 정확히 한 번 호출하고 `CommonExecutionSnapshot.fromUpbit`와 순수 DTO 변환을 적용합니다. `bid`/`ask`는 `BUY`/`SELL`로 옮기며 체결이 있는 `cancel`도 보존합니다. 무체결 주문은 `executions=[]`이고 `paidFee`는 체결 수와 관계없이 `ORDER` 범위·주문 ID를 가진 수수료 한 항목입니다. 통화는 추정하거나 배분하지 않습니다. 단건 조회에서 적용되지 않는 `possiblyTruncated`와 미확인 수수료 `currency`는 **명시적인 JSON null**이며 출력 스키마도 null을 허용합니다. 공통 DTO의 다른 조회 범위에서 비어 있는 `orderId` 역시 null, 확인된 잘림 힌트는 boolean, 확인된 수수료 통화는 문자열로 변환합니다.

체결·수수료 목록은 방어 복사한 불변 목록입니다. 기존 최대 1000건·본문 1 MiB·전체 10초와 단일 GET·리다이렉트 거부·재시도 금지 정책을 유지합니다. 추가 조회·전체 내역 수집·매수/매도 매칭·손익 계산은 포함하지 않습니다. 단건 조회와 null은 전체 계정 내역 수집 완료를 뜻하지 않습니다. 기존 안전한 오류 범주와 JSON content 보정 경로를 재사용하며 공통 변환에서 예상 밖으로 거부된 응답은 원인 없는 `INTERNAL_ERROR`로 처리합니다.

[CommonExecutionResponseTest](src/test/java/io/github/lmj/tradeledger/mcp/CommonExecutionResponseTest.java), 등록 스키마 테스트와 기존 로컬 HTTP 주입점의 [UpbitOrderToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/UpbitOrderToolsTest.java)는 null·필드 대응·불변성·별칭 경계·단일 전송·안전한 오류·로그 비노출 회귀 검사를 포함합니다. 실계정은 미검증입니다.

### `get_upbit_closed_orders`

종료 주문(`done`, `cancel`)의 **요약만** 한 번 조회합니다. 지정한 일곱 인자는 모두 선택 사항이며 키·시크릿·호스트·URL 인자는 없습니다. 등록 힌트는 `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`입니다.

| 인자 | 계약 |
| --- | --- |
| `market` | 대문자 시장 코드, 예: `KRW-BTC`. 생략 시 전송하지 않음 |
| `state` | `done` 또는 `cancel`. `states`와 동시 지정 금지 |
| `states` | 중복 없는 비어 있지 않은 `done`/`cancel` 배열. 상태 인자 모두 생략 시 `["done", "cancel"]` |
| `startTime`, `endTime` | 각각 독립적으로 지정하거나 모두 생략. 각 입력은 오프셋 ISO8601 또는 정수 epoch 밀리초 문자열을 UTC ISO8601로 정규화. 두 경계가 있을 때만 시작 ≤ 종료와 최대 정확히 7일 간격을 검사 |
| `limit` | 1~1000, 기본 100 |
| `orderBy` | `asc` 또는 `desc`, 기본 `desc` |

[업비트 공식 규격](https://docs.upbit.com/kr/reference/list-closed-orders)에 따르면 시작만 지정하면 해당 시각 이후 7일, 종료만 지정하면 해당 시각 이전 7일, 모두 생략하면 요청 시각 이전 7일을 조회합니다. 서버는 생략된 경계를 null로 유지하고 쿼리에서 제외하며 현재 시각이나 반대 경계를 로컬에서 계산하지 않습니다. 지정한 경계만 기존 순서로 한 번씩 전송하고 같은 미인코딩 쿼리로 JWT 해시를 생성합니다. 잘못된 날짜·오프셋 없는 시각·빈 값·길이 초과·정수 범위 초과, 역전 또는 7일 초과 기간, 상태 충돌·중복·오류, 잘못된 시장·정렬, 범위 밖 `limit`은 provider 획득·인증정보 조회·HTTP 전송 전에 입력값과 원인을 제거한 `BAD_REQUEST`로 반환합니다. 기존 단건 도구의 잘못된 UUID는 계속 `INVALID_UUID`입니다.

기본 호출은 다음과 같으며 `GET /v1/orders/closed`에 `states[]=done&states[]=cancel&limit=100&order_by=desc`를 전송합니다. 상태 배열 키는 실제 URL에서 인코딩됩니다.

```json
{"name":"get_upbit_closed_orders","arguments":{}}
```

시작 또는 종료만 지정하는 예시입니다. epoch 밀리초도 MCP에서는 문자열로 전달합니다. 두 예시 모두 지정한 시각은 `2026-07-01T01:00:00Z`로 정규화됩니다.

```json
{"name":"get_upbit_closed_orders","arguments":{"startTime":"2026-07-01T10:00:00+09:00"}}
```

```json
{"name":"get_upbit_closed_orders","arguments":{"endTime":"1782867600000"}}
```

```json
{
  "name": "get_upbit_closed_orders",
  "arguments": {
    "market": "KRW-BTC",
    "states": ["done", "cancel"],
    "startTime": "2026-07-01T10:00:00+09:00",
    "endTime": "2026-07-08T10:00:00+09:00",
    "limit": 1000,
    "orderBy": "asc"
  }
}
```

다음은 가상의 요약 예시이며 실계정 조회 결과가 아닙니다. 성공 `content`는 단일 JSON `TextContent`이며 그 전체 값은 `structuredContent`와 같습니다. 금액의 후행 0을 보존하고 시각은 UTC ISO8601로 노출합니다. `tradesCount`는 2^53과 int64 범위를 넘는 유효 정수도 정밀도 손실 없이 십진 **문자열**로 제공합니다.

```json
{
  "serviceId": "upbit",
  "orders": [
    {
      "orderId": "123e4567-e89b-12d3-a456-426614174000",
      "market": "KRW-BTC",
      "side": "bid",
      "orderType": "limit",
      "state": "cancel",
      "createdAt": "2026-07-01T01:00:00Z",
      "executedVolume": "0.10000000",
      "paidFee": "50.00000000",
      "tradesCount": "1"
    }
  ],
  "possiblyTruncated": false
}
```

빈 배열도 정상 결과(`orders=[]`)입니다. `done`, 체결이 있는 `cancel`, 무체결 `cancel` 모두 요약으로 반환하며 체결 상세는 제공하지 않습니다. `paidFee`는 주문 전체 수수료이며 수수료 통화·완결 `Trade`·매수/매도 매칭·손익을 추정하지 않습니다. 요청당 기존 GET은 정확히 한 번이며 주문별 추가 조회도 없습니다.

결과가 요청 `limit`에 도달하면 `possiblyTruncated=true`입니다. **짧거나 빈 결과 및 `possiblyTruncated=false`도 전체 내역 수집 완료를 보장하지 않습니다.** 단일 기간 최대 **7일·1000건**, 본문 **1 MiB**, 본문 수신을 포함한 전체 **10초**로 제한하며 페이지 순회와 자동 기간 분할을 지원하지 않습니다. 요청 limit 초과 또는 손상된 응답은 부분 반환 없이 `INVALID_RESPONSE`입니다. 키 누락·초기화·인증·권한·404·418·429·5xx·리다이렉트·연결 종료·시간 초과는 기존 안전한 오류 범주로만 전달하고 추가 조회·자동 재시도를 하지 않습니다.

종료 주문 계약의 근거는 [종료 주문 조회 공식 규격](https://docs.upbit.com/kr/reference/list-closed-orders)입니다. 공식 단독 경계·7일 기본 동작과 로컬 대역 테스트는 실계정 대조를 대신하지 않습니다. 실계정 동작은 미검증입니다.

### `get_binance_account_trades`

바이낸스 현물의 **단일 symbol 체결**을 한 번 조회합니다. 필수 `accountAlias`·`symbol`과 선택 `limit`·문자열 `fromId`·`startTime`·`endTime`·`orderId`를 받으며 키·시크릿·URL 인자는 없습니다. `orderId`를 생략하면 기존 최근·`fromId`·시간 조회의 정확한 쿼리를 보존하며 출력 구조는 동일합니다. 기존 요청 생성자와 3·4·6인자 Java 도구 호출자도 유지합니다. 등록 힌트는 `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`입니다.

| 인자 | 계약 |
| --- | --- |
| `accountAlias` | 비밀정보가 아닌 호출자 지정 로컬 표시 이름. 실제 인증 계정을 선택하지 않음 |
| `symbol` | 단일 현물 symbol, 예: `BTCUSDT`. 기존 요청 모델로 검증 |
| `limit` | 1~1000, 생략 시 500 |
| `fromId` | 선택적 **문자열**, 체결 ID의 **포함 하한**. `0` 또는 `[1-9][0-9]*` 형식의 ASCII 십진수, 0~9223372036854775807 |
| `startTime` | 선택적 **문자열**, 체결 시각의 **포함 하한**. 프로젝트 정책상 정규 ASCII 십진 문자열의 비음수 int64 epoch 밀리초만 지원 |
| `endTime` | 선택적 **문자열**, 체결 시각의 **포함 상한**. `startTime`과 같은 형식·범위 |
| `orderId` | 선택적 **문자열**, 지정 주문의 체결만 조회. 프로젝트 정책상 정규 ASCII 비음수 int64 십진 문자열, 0~9223372036854775807 |

지원 조합은 필터 생략(최근 조회), `fromId` 단독, `orderId` 단독, `orderId`+`fromId`, `startTime` 단독, `endTime` 단독, `startTime`·`endTime` 동시 지정입니다. `limit`은 모든 조합에서 선택적입니다. `fromId` 또는 `orderId`와 각 시간 필터의 혼합은 `BAD_REQUEST`로 거부합니다. 두 시간 경계가 있으면 `startTime <= endTime`이고 차이는 최대 **86,400,000ms(24시간)**여야 합니다. 같은 경계와 정확히 24시간은 허용하고 역전·24시간+1ms는 거부합니다. 기간은 정수로 오버플로 없이 검사합니다. 생략한 경계를 현재 시각 등으로 생성하거나 전송하지 않습니다.

별칭과 symbol은 각각 비어 있지 않은 최대 256 UTF-16 코드 단위 문자열이어야 합니다. 공백만 있는 값, ISO 제어문자, 짝이 맞지 않는 surrogate는 거부합니다. 유효 비ASCII 문자·대소문자·앞뒤 공백은 그대로 보존합니다. 이는 로컬 입력 정책이며 거래소의 모든 symbol 유효성을 보장하지 않습니다. 입력 오류는 클라이언트 생성 전에 입력값·원인을 제거한 `Binance request failed: BAD_REQUEST`로 처리합니다.

`accountAlias`는 응답에 그대로 포함하며 바이낸스로 전송하지 않습니다. 실제 조회 계정은 서버 환경의 한 쌍의 키로만 정해집니다. 중복 식별 범위는 **(accountAlias, serviceId, symbol, id)**이며 체결 ID만으로 서비스·계정 간 유일성을 가정하지 않습니다. 같은 실제 계정에는 일관된 별칭을 사용해야 합니다. 다른 별칭이 다른 인증 계정임을 증명하거나 서버가 중복을 저장·제거하는 기능은 없습니다.

```json
{
  "name": "get_binance_account_trades",
  "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "limit": 1}
}
```

주문·시간 필터 호출 예시입니다. 각 객체는 별개의 `tools/call` params이며, 단독 경계 호출에는 반대 경계를 넣지 않습니다.

```json
[
  {"name": "get_binance_account_trades", "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "orderId": "9007199254740993"}},
  {"name": "get_binance_account_trades", "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "orderId": "9223372036854775807", "fromId": "0"}},
  {"name": "get_binance_account_trades", "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "startTime": "1499865549590"}},
  {"name": "get_binance_account_trades", "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "endTime": "1499865549590"}},
  {"name": "get_binance_account_trades", "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "startTime": "1499865549590", "endTime": "1499951949590"}}
]
```

다음은 가상 출력이며 실계정 조회 결과가 아닙니다. 모든 체결 필드를 보존하고 `id`·`orderId`·`orderListId`를 출력 스키마에서도 문자열로 선언합니다. `Long.MAX_VALUE`, 2^53 초과 ID, `orderListId=-1`도 정확한 십진 문자열로 출력합니다. 금액 문자열은 고정밀 값·후행 0을 유지하고 `time`은 UTC ISO8601입니다. 성공 `content`는 단일 JSON `TextContent`이고 파싱한 전체 값이 `structuredContent`와 같습니다.

```json
{
  "serviceId": "binance",
  "accountAlias": "spot-main",
  "symbol": "BTCUSDT",
  "trades": [{
    "symbol": "BTCUSDT",
    "id": "9007199254740993",
    "orderId": "9223372036854775807",
    "orderListId": "-1",
    "price": "123.4500",
    "qty": "0.0200",
    "quoteQty": "2.469000",
    "commission": "0.0000100",
    "commissionAsset": "BNB",
    "time": "2017-07-12T13:19:09.590Z",
    "isBuyer": true,
    "isMaker": false,
    "isBestMatch": true
  }],
  "possiblyTruncated": true
}
```

빈 배열도 정상 결과입니다. 결과 수가 요청 `limit`과 같을 때만 `possiblyTruncated=true`이며 **짧거나 빈 결과 및 false도 전체 계정 내역이나 지정 기간의 수집 완료를 보장하지 않습니다.** 주문·시간 필터는 단일 결과의 조회 범위를 지정하며 지정 주문의 전체 체결 수집도 보장하지 않습니다. 단일 조회 최대 **1000건**, 응답 본문 **1 MiB**, 연결 **3초**, 본문 수신을 포함한 전체 **10초**로 제한합니다. 페이지 반복·자동 기간 분할은 지원하지 않습니다. 선물·파생상품, 수수료 자산 환산·합산, 매수/매도 매칭, 완결 거래·손익 추정도 지원하지 않습니다.

`orderId`·`fromId`·`startTime`·`endTime`은 같은 요청 검증기를 재사용합니다. 프로젝트 입력 정책으로 최대 19자인지 먼저 검사한 뒤 `0` 또는 `[1-9][0-9]*` 형식과 0~9223372036854775807 범위를 검사하며 부동소수점으로 변환하지 않습니다. 예를 들어 `"0"`, `"9007199254740993"`, `"9223372036854775807"`을 손실 없이 처리합니다. 빈 문자열·공백·음수·부호·소수·지수·선행 0·비ASCII 숫자·범위 초과·과도한 길이는 입력값과 원인을 제거한 `BAD_REQUEST`로 거부하고 지연 provider와 HTTP를 호출하지 않습니다. 요청 모델이 만든 쿼리를 기존 서명기에 그대로 전달하며 유효 호출당 고정 GET은 한 번입니다. 기존 최대 1000건 매퍼 순회에서 주문 일치, ID 하한과 지정 시간 범위를 검사합니다. 응답에 요청과 다른 `orderId`, 하한보다 작은 ID, `startTime` 미만 또는 `endTime` 초과인 `time`이 하나라도 있으면 정상 항목 뒤에 있더라도 기존 매퍼의 원문 없는 안전한 오류를 거쳐 전체 응답을 `INVALID_RESPONSE`로 거부합니다. 등록 핸들러에서는 `isError=true`이며 부분 `structuredContent`는 반환하지 않습니다. 같은 주문의 복수 체결·범위 안과 경계에 있는 체결·빈 배열은 허용합니다. `orderId`+`fromId`는 주문 일치와 포함 하한을 모두 적용합니다.

[Binance 공식 Account trade list](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account)는 `orderId` 단독 및 `orderId`+`fromId`, 시간 경계의 단독·동시 조합, 최대 24시간 간격, `fromId` 하한과 기본 limit 500·최대 1000을 명시합니다(2026-11-13 확인). [General REST API Information](https://developers.binance.com/en/docs/products/spot/rest-api)은 필터 생략 시 최근 항목, 시작 경계부터 오래된 순서, 종료 경계까지 최근 항목, 두 경계 안의 조회 동작 및 기본 응답 시각 단위가 밀리초임을 설명합니다(2026-11-13 확인). 공식 API의 시간 입력은 밀리초·마이크로초를 지원하지만, **이 프로젝트는 정규 ASCII 십진 문자열·비음수 int64·밀리초 전용 입력을 정책으로 제한합니다.** 마이크로초 단위 선택이나 자동 단위 추정·변환은 제공하지 않습니다. `get_binance_account_executions`도 같은 네 필터를 받으며 필터 생략 시 `RECENT_SYMBOL`, 지정 시 `FILTERED_SYMBOL`을 반환합니다. 추가 조회나 측정하지 않은 성능 개선을 주장하지 않습니다. **실계정 동작은 미검증입니다.**

`TradeLedgerMcpApplicationTests.binanceSchemaExposesOnlyAliasSymbolAndOptionalLimitAndFiltersWithStringIds`와 `BinanceAccountTradesToolsTest`는 입력 키가 정확히 `accountAlias`·`symbol`·`limit`·`fromId`·`startTime`·`endTime`·`orderId`인지 검사합니다. 네 필터의 문자열 자료형과 필수 인자가 `accountAlias`·`symbol`뿐인지 검증하며 아홉 도구·출력·등록 힌트도 검사합니다. 요청·매퍼·로컬 HTTP·등록 핸들러 테스트는 주문 필터의 허용 조합, 잘못된 입력, 큰 정수, 원시 쿼리 HMAC·단일 GET, 주문 불일치와 범위 위반의 전체 실패를 포함합니다.

유효 요청은 기존 `getAccountTrades`를 한 번 호출하고 GET을 정확히 한 번 전송합니다. 오류에 따른 추가 조회·자동 재시도는 없습니다. 키 누락·설정 거부·인증·보안 제한·418·429·5xx·리다이렉트·네트워크·시간 초과·본문 초과·손상 JSON은 기존 `BinanceErrorCode` 범주만 노출합니다. `-1021`은 `INVALID_TIMESTAMP`, `-1022`는 `INVALID_SIGNATURE`, `-2014`는 `INVALID_API_KEY_FORMAT`, `-2015`는 `KEY_IP_OR_PERMISSION_REJECTED`이며 **-2015의 원인을 키·IP·권한 중 하나로 단정하지 않습니다.** 안전한 범주가 없는 초기화 또는 예상 밖 오류는 원인 없는 `INTERNAL_ERROR`로 처리합니다.

[BinanceAccountTradesToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/BinanceAccountTradesToolsTest.java)는 가짜 키·고정 시계·루프백 주입점을 통한 등록 핸들러 테스트, 입력·출력 경계, 오류·로그 비노출, 독립 JVM 설정 거부와 종료 단언을 포함합니다. 클라이언트의 단일 전송·독립 JVM·자원 종료 검사도 있습니다.

### `get_binance_account_executions`

일곱 번째 도구는 바이낸스의 단일 symbol 체결을 공통 응답으로 제공합니다. 입력 속성은 정확히 필수 `accountAlias`·`symbol`과 선택 `limit`·문자열 `fromId`·`startTime`·`endTime`·`orderId`입니다. limit은 기본 500·범위 1~1000입니다. 기존 3인자 Java 호출은 등록되지 않는 오버로드로 유지하며 필터 생략 시 기존 쿼리와 `RECENT_SYMBOL`을 보존합니다. 기존 바이낸스 도구와 동일한 별칭 정책과 `validatedRequest`·`BinanceAccountTradesRequest`로 지연 클라이언트 획득 전에 검증합니다. 별칭은 최대 256 UTF-16 코드 단위이며 공백만인 값·ISO 제어문자·잘못된 surrogate는 거부하고 유효 비ASCII 값·앞뒤 공백은 보존합니다. 잘못된 입력은 원인 없는 `Binance request failed: BAD_REQUEST`이며 provider와 HTTP를 호출하지 않습니다. 등록 힌트는 `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`입니다.

네 필터는 프로젝트 정책상 `0`부터 `9223372036854775807`까지의 정규 ASCII 십진 **문자열**이며 최대 19자입니다. 부호·공백·선행 0(단독 `0` 제외)·소수·지수 표기·int64 초과를 거부합니다. `fromId`는 체결 ID의 포함 하한입니다. `startTime`·`endTime`은 밀리초 전용 포함 경계이며 각각 단독 또는 함께 지정할 수 있습니다. 동일 경계와 정확히 24시간은 허용하고 역전 또는 86400000ms 초과는 거부합니다. `orderId`는 단독 또는 `fromId`와 함께 허용하며 두 ID 중 하나라도 시간 경계와 혼합하면 거부합니다. 마이크로초 자동 추정·변환은 없습니다. 공식 API 근거는 위 [Account trade list](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account)와 [General REST API Information](https://developers.binance.com/en/docs/products/spot/rest-api) 참조이며, 정규 문자열·비음수 int64·밀리초 전용 제약은 프로젝트 입력 정책입니다.

```json
{
  "name": "get_binance_account_executions",
  "arguments": {"accountAlias": "spot-main", "symbol": "BTCUSDT", "limit": 1}
}
```

다음은 가상 출력이며 실계정 조회 결과가 아닙니다. `limit`을 생략한 호출은 같은 인자에서 `limit`만 제외하면 됩니다. 성공 `content`는 단일 JSON `TextContent`이고 파싱한 전체 값이 `structuredContent`와 같습니다. 출력 스키마에서도 ID·금액은 문자열이고 최상위 `orderId`는 nullable입니다.

```json
{
  "serviceId": "binance",
  "accountAlias": "spot-main",
  "symbol": "BTCUSDT",
  "queryScope": "RECENT_SYMBOL",
  "orderId": null,
  "possiblyTruncated": true,
  "executions": [{
    "serviceId": "binance",
    "accountAlias": "spot-main",
    "symbol": "BTCUSDT",
    "executionId": "9007199254740993",
    "orderId": "9223372036854775807",
    "side": "BUY",
    "price": "123.4500",
    "quantity": "0.0200",
    "amount": "2.469000",
    "executedAt": "2017-07-12T13:19:09.590Z"
  }],
  "fees": [{
    "scope": "FILL",
    "targetId": "9007199254740993",
    "amount": "0.0000100",
    "currency": "BNB"
  }]
}
```

`isBuyer=true`는 `BUY`, false는 `SELL`입니다. 2^53 초과·`Long.MAX_VALUE` ID, 고정밀 금액·후행 0과 UTC 시각을 보존합니다. 각 체결 commission은 `FILL`·해당 체결 ID·원본 `commissionAsset`으로 별도 기록하며 서로 다른 수수료 자산을 합산·환산하지 않습니다. 중복 식별 키는 **(serviceId, accountAlias, symbol, executionId)**입니다. 별칭은 인증 계정을 선택하거나 바이낸스로 전송되지 않으며, 같은 실제 계정에는 일관된 별칭을 사용해야 합니다. 서버는 호출 간 중복 저장·제거를 하지 않습니다.

네 필터를 모두 생략하면 `RECENT_SYMBOL`, 하나라도 지정하면 `FILTERED_SYMBOL`입니다. `orderId` 필터도 제한된 목록 조회이므로 업비트의 `SINGLE_ORDER` 의미로 바꾸지 않습니다. 두 바이낸스 범위 모두 최상위 `orderId`는 **명시적 JSON null**이며 원본 `possiblyTruncated` true/false를 빈 결과에서도 그대로 유지합니다. 기존 클라이언트는 결과 수가 limit에 도달하면 true를 제공하며, 공통 변환은 이를 재계산하지 않습니다. 빈 결과는 `executions=[]`, `fees=[]`입니다. **짧거나 빈 결과와 false도 전체 계정 내역 수집 완료를 뜻하지 않습니다.** 유효 요청은 지연 클라이언트의 `getAccountTrades`를 정확히 한 번 호출하고 최대 1000건을 추가 조회 없이 변환합니다. 기존 GET `/api/v3/myTrades`, 1 MiB·전체 10초 제한, 단일 전송·리다이렉트 거부·자동 재시도 금지를 유지합니다. 성능 개선 수치는 주장하지 않습니다.

응답 항목이 `fromId` 하한·시간 경계·`orderId`와 맞지 않으면 첫 항목 또는 정상 항목 뒤에 있더라도 기존 매퍼가 전체를 `INVALID_RESPONSE`로 거부합니다. 등록 핸들러는 `isError=true`이고 부분 `structuredContent`는 없습니다. 회귀 테스트는 등록 스키마·필터 조합·큰 ID·시간 경계·원시 쿼리 HMAC·단일 GET·안전한 오류와 로그·순수 변환의 범위 및 null 계약을 포함합니다. 실계정은 미검증입니다.

기존 환경 키와 두 JVM 시작 옵션이 그대로 필요하며 HTTP 진단 로깅 거부 정책도 같습니다. 키·시작 옵션 없이 서버 시작과 계산 도구 호출은 가능하고 세 거래소 클라이언트의 지연 생성도 유지합니다. 키 누락·설정 거부·인증·보안·호출 제한·서버·네트워크·시간 초과·본문 초과·손상 응답·초기화 실패는 기존 안전한 Binance 오류 범주로만 반환하며 추가 전송은 없습니다. 예상 밖 변환 실패는 원인 없는 `INTERNAL_ERROR`로 처리하고 응답 원문이나 원인 예외는 노출하지 않습니다. 실계정은 미검증입니다.

## 내부 공통 체결 변환

[CommonExecutionSnapshot](src/main/java/io/github/lmj/tradeledger/application/model/CommonExecutionSnapshot.java)은 불변 record이며 `fromUpbit(accountAlias, snapshot)`와 `fromBinance(snapshot)`·`fromBinance(snapshot, scope)` 정적 메서드로 기존 스냅샷만 변환합니다. 순수 변환 자체에는 별도 서비스·어댑터 인터페이스·의존성이나 HTTP·환경변수·파일 접근이 없습니다. 원본 체결 목록을 한 번 순회해 변환하며 추가 조회를 하지 않습니다. 업비트 단건 조회와 바이낸스 최근·필터 체결 조회는 이 변환과 [CommonExecutionResponse](src/main/java/io/github/lmj/tradeledger/mcp/CommonExecutionResponse.java)를 사용합니다. 성능 향상 수치는 측정하거나 주장하지 않습니다.

각 체결에는 서비스 ID, 비밀정보가 아닌 계정 별칭, 원본 시장/symbol, 체결 ID, 주문 ID, `BUY`/`SELL`, 가격·수량·체결 금액 문자열과 `Instant`를 담습니다. 업비트 `bid`/`ask`는 각각 `BUY`/`SELL`, 바이낸스 `isBuyer`는 true일 때 `BUY`, false일 때 `SELL`로 옮깁니다. 금액 문자열의 고정밀 값·후행 0과 원본 종목을 변경하지 않고, 바이낸스 long ID는 정확한 십진 문자열로 보존합니다. 체결 ID의 구조적 키는 `(serviceId, accountAlias, symbol, executionId)`이며 다른 서비스·별칭·종목의 같은 ID를 합치지 않습니다. 별칭은 기존 바이낸스 도구와 같은 최대 256 UTF-16 코드 단위·공백만인 값 거부·ISO 제어문자 거부·올바른 surrogate 쌍 정책을 적용하며 유효한 값은 그대로 유지합니다.

`fees`는 체결과 별도 목록입니다. 업비트 `paidFee`는 `ORDER` 범위와 주문 ID로 정확히 한 번 기록하며 통화는 `Optional.empty()`(미확인)입니다. 여러 체결에 배분하지 않고 무체결 주문의 수수료도 보존합니다. 바이낸스는 각 체결의 commission을 `FILL` 범위와 체결 ID로 기록하고 `commissionAsset` 원문을 통화로 유지합니다. 종목으로 통화를 추정하거나 서로 다른 수수료 자산을 합산·환산하지 않습니다. 주문 상태로 체결을 걸러내지 않으므로 체결이 있는 `cancel`과 무체결 주문도 변환합니다. 원본 주문 상태 등 서비스별 정보는 기존 스냅샷에 남습니다. 완결 `Trade` 생성, 매수/매도 매칭과 손익 계산은 수행하지 않습니다.

조회 범위 `SINGLE_ORDER`는 주문 ID가 있고 `possiblyTruncated`는 해당 없음(`Optional.empty()`)입니다. `RECENT_SYMBOL`과 `FILTERED_SYMBOL`은 단일 주문 ID가 없으며 원본 바이낸스 `possiblyTruncated` true/false를 그대로 담습니다. `fromBinance(snapshot)`은 기존 `RECENT_SYMBOL`을 유지하고 `fromBinance(snapshot, scope)`는 두 바이낸스 범위만 허용합니다. null 또는 `SINGLE_ORDER` 범위는 기존 안전한 공통 모델 오류로 거부합니다. 모델은 어댑터 요청 클래스에 의존하지 않습니다. **어느 범위도 전체 계정 내역 수집 완료를 표현하지 않습니다.** 빈 결과·false 역시 완료 보장이 아닙니다.

변환은 누락·잘못된 별칭·알 수 없는 업비트 방향·부모 주문과 체결의 시장/방향 불일치·부모 symbol과 체결 symbol 불일치·중복 키를 부분 결과 없이 거부합니다. 오류는 입력값·원인·suppressed 예외가 없는 고정 메시지 `Invalid common execution snapshot`입니다. 목록은 빈 목록부터 최대 1000건까지 허용하며 결과 체결·수수료 목록은 방어 복사하고 변경을 막습니다.

[CommonExecutionSnapshotTest](src/test/java/io/github/lmj/tradeledger/application/model/CommonExecutionSnapshotTest.java)는 합성 입력 필드 대응, 큰 ID·소수 정밀도·UTC 시각, 주문/체결 수수료, 취소·무체결, 키·입력 경계, 불변성과 조회 범위, 범위 지정 오버로드·원본 보존·범위 오류 거부 회귀 검사를 포함합니다. 실계정 원본 대조는 남아 있습니다.

## 현재 Trade로 표현할 수 있는 범위

[Trade](src/main/java/io/github/lmj/tradeledger/domain/model/Trade.java)는 수량 하나, 진입·청산 단가 하나씩, 동일 통화의 총수수료와 진입·청산 시각을 가진 완결 거래입니다. 개별 체결을 완결 거래로 간주하거나 미청산 거래에 임의 청산값을 넣을 수 없습니다. 분할 체결·부분 청산을 묶는 규칙, 원가 배분 및 포지션 매칭은 구현되어 있지 않습니다. 다른 통화로 낸 수수료도 통화 이름만 바꾸어 입력할 수 없고, 환율·기준 시각·출처를 정한 환산 기능은 없습니다. 따라서 원본 체결이나 서비스 파일을 이 형식으로 임의 변환해서는 안 됩니다. `SHORT` 값 지원이 특정 서비스의 공매도·파생상품 연동을 의미하지도 않습니다.

## 서비스별 상태와 다음 확인 자료

업비트의 단건 주문·종료 주문 요약, 바이낸스의 단일 symbol 최근 체결과 토스증권의 단건 주문·누적 체결 요약과 종료 주문 한 페이지는 어댑터·MCP 연결이 소스에 반영되어 있습니다. 공식 규격 근거, 구현, 모의 테스트, 실계정 대조는 서로 다른 상태입니다. 토스증권의 실계정·조회 전용 권한 분리는 미검증입니다.

| 서비스 | 공식 방식 확인 | 어댑터 구현 | 서비스 모의 검증 | 실제 연동 검증 | 다음 확인에 필요한 공식 문서·비식별 파일 예시 |
| --- | --- | --- | --- | --- | --- |
| 업비트 | 단건·종료 주문 조회·인증·호출 제한 공식 규격 참조. 종료 주문의 단독 경계와 7일 기본 동작 안내 | 두 조회 경로와 MCP 연결 반영. 매수·매도 매칭·실현손익 미구현 | 어댑터·등록 MCP 핸들러 모의 테스트 존재 | 미검증 | 최신 공식 주문조회 권한·default 공유 한도 확인, 조회 전용 실계정 원본과 UUID·종료 주문 요약·체결·주문 수수료 대조. 페이지 규격과 수수료 통화·시간대가 보존된 비식별 분할 체결 예시 |
| 바이낸스 | 현물 계정 체결 규격·공식 문서 참조 | 단일 symbol의 거래소별·공통 체결 MCP 응답, 문자열 ID·금액, 지연 생성과 종료 연결 반영 | 두 등록 핸들러의 입력·단일 전송·안전한 오류와 공통 응답 회귀 테스트 존재 | 미검증 | 현물 규격·조회 전용 권한·서명·HTTP 오류 계약을 모의 결과와 대조하고 MCP 연결 검토. 파생상품·파일 내보내기 방식은 별도 확인, 수수료 자산·시간대·부분 청산 예시 필요 |
| 토스증권 | 공식 OpenAPI 1.2.28의 주문·누적 체결 요약·종료 주문 목록·결제 거래내역 계약 참조 | 단건·종료 주문의 HTTP·Spring·MCP 연결과 별도 순수 결제 거래내역 한 페이지 매퍼 반영. 거래내역 전송·도구 연결은 미구현 | 합성 요청·모델·매퍼, 기존 주문 로컬 HTTP·등록 핸들러·독립 JVM 테스트 존재 | 실계정·조회 전용 권한 분리 미검증 | 아래 계약·운영 설정·남은 확인 참조. 실제 결제 내역 및 비용 귀속은 별도 대조 필요 |

### 토스증권 단건 주문 요약 계약

공식 근거는 [OpenAPI JSON](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)의 **문서 버전 1.2.28 / OpenAPI 3.1.0**, `getOrder`와 `Order`·`OrderExecution` 스키마입니다. [Order History 가이드](https://developers.tossinvest.com/docs/order-history)도 참조할 수 있습니다. `latest` 문서는 바뀔 수 있으므로 이후 연동 시 버전과 계약을 다시 대조해야 합니다. 상세 계약은 공개 OpenAPI 원문으로 확보했으며, 과거 웹 화면의 렌더링·접근 한계는 현재 계약 부재를 뜻하지 않습니다.

[TossOrderRequest](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossOrderRequest.java)는 `orderId`만 받습니다. [TossOrderClient](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossOrderClient.java)의 공개 기본 생성자는 목적지를 `https://openapi.tossinvest.com`으로 고정하며 `getOrder(request)`가 `GET /api/v1/orders/{인코딩된 ID}`를 한 번 전송합니다. 본문 없이 `Accept: application/json`을 사용하며 호스트·URL·인증정보를 조회 인자로 받지 않습니다. ID는 불투명한 문자열로 취급해 대소문자·공백을 포함한 유효 내용을 그대로 보존하고 UUID·정수·예시 토큰 길이로 제한하거나 디코딩하지 않습니다. null·공백뿐인 값·ISO 제어문자와 **256 UTF-16 코드 단위 초과**를 거부합니다. 256 상한은 공식 ID 규격이 아닌 프로젝트 정책입니다. 클라이언트는 null 요청과 짝이 맞지 않는 surrogate를 환경 조회·전송 전에 `BAD_REQUEST`로 거부합니다. 유효 ID는 trim 없이 UTF-8 단일 경로 세그먼트로 인코딩하며 `%`, `/`, `+`, 공백, `?`, `#`, 비ASCII 문자와 점도 인코딩하여 경로·쿼리 변경과 점 세그먼트 정규화를 막습니다.

[TossOrderMapper](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossOrderMapper.java)의 진입점은 `map(request, json)`입니다. 클라이언트는 제한된 UTF-8 성공 응답을 이 매퍼에 전달합니다. 매퍼는 검증된 요청과 성공 JSON의 `result` 객체를 받아 [TossOrderSnapshot](src/main/java/io/github/lmj/tradeledger/application/model/TossOrderSnapshot.java) 불변 record로 옮깁니다. 응답 ID가 요청과 정확히 일치해야 하며, 오류 봉투·필수 객체 누락·잘못된 자료형은 전체 실패합니다.

| 계약 구분 | 필드와 처리 |
| --- | --- |
| 필수 주문 문자열 | `orderId`, `symbol`, `side`, `orderType`, `timeInForce`, `status`, `quantity`, `currency`, `orderedAt`. 누락·null·빈 문자열 거부 |
| 선택 nullable 주문 필드 | `price`, `orderAmount`, `canceledAt`. 생략과 명시적 null 모두 Java null |
| 필수 누적 체결 객체 | `execution`은 객체이고 `filledQuantity`는 필수 비-null 십진 문자열 |
| 필수 nullable 누적 체결 필드 | `averageFilledPrice`, `filledAmount`, `commission`, `tax`, `filledAt`, `settlementDate`. 키 누락은 거부하고 명시적 null은 보존 |

일반 문자열에는 프로젝트 정책으로 공백뿐인 값·ISO 제어문자 거부와 256 UTF-16 코드 단위 상한을 적용합니다. `side`는 BUY/SELL이며 `orderType`·`timeInForce`·`status`와 `currency`는 공식 문서의 확장 지침에 따라 미지 코드도 원문 그대로 보존합니다.

십진 필드는 공식 **최대 30자** 계약을 적용합니다. 일반 십진 표기 `[+-]?[0-9]+(?:\.[0-9]+)?`만 받는 것은 프로젝트 정책입니다. 지수·NaN·Infinity·숫자 JSON 토큰을 거부하며, 부동소수점 변환 없이 문자열로 정밀도·후행 0·부호·null을 보존합니다. 문서에 없는 비용 부호나 수량×평균가=누적 금액 같은 산술 일치를 강제하지 않습니다. 주문 가격·주문 금액·평균 체결가·누적 체결 금액은 별도 필드이며 commission과 tax도 합산·배분·환산하지 않습니다. 주문 가격과 누적 체결 가격·금액 및 비용은 native currency, `orderAmount`는 USD 주문 금액입니다.

오프셋을 포함한 주문·취소·최종 체결 시각은 `Instant`로 변환하며 오프셋 없는 시각은 거부합니다. 결제 예정일은 유효한 `YYYY-MM-DD`의 `LocalDate`로 보존합니다. 이 예정일과 주문 누적 체결 요약은 실제 결제 거래내역이나 개별 체결 목록을 대신하지 않습니다. 취소·대체·거부 상태여도 누적 체결은 보존합니다. 가상 체결 ID·개별 체결·완결 `Trade`·손익을 만들지 않으며 기존 `OrderExecutionSnapshot`·`CommonExecutionSnapshot`으로 변환하지 않습니다. `/api/v1/trades`의 종목별 시장 체결도 개인 계좌 체결로 사용하지 않습니다.

### 종료 주문 한 페이지: HTTP 클라이언트·매퍼·MCP

[공식 주문 목록 계약](https://openapi.tossinvest.com/openapi-docs/latest/api-reference/Apis/OrderHistoryApi.md)과 [공식 OpenAPI의 PaginatedOrderResponse·Order 스키마](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)를 기준으로 `CLOSED` 한 페이지를 조회합니다. 공식 조회 범위는 Open API가 지원하는 지정가·시장가·장마감지정가 유형으로 접수된 주문입니다. 장후시간외 종가·장전시간외 종가 등 Open API로 주문할 수 없는 호가 유형은 목록과 상세 조회 모두에서 제외되므로 전체 계좌 주문 내역을 뜻하지 않습니다. [TossClosedOrdersRequest](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossClosedOrdersRequest.java)는 선택적 `symbol`·`from`·`to`·`cursor`·`limit`만 받고 `status()`는 항상 `CLOSED`입니다. limit은 생략·null이면 20, 허용 범위는 1~100입니다. `OPEN`은 서버가 limit·cursor를 무시하고 전량 반환하므로 이 모델에서 제공하지 않습니다. 그룹 필터 `CLOSED`와 각 주문의 status는 별개이며 `PARTIAL_FILLED`와 미지 status도 원문 그대로 보존합니다.

symbol은 공식 패턴 `[A-Za-z0-9.\-]+`를 적용하고 대소문자를 보존합니다. from·to는 각각 독립적으로 생략 가능한 엄격한 `YYYY-MM-DD` 날짜이며, 둘 다 있으면 from≤to입니다. 양끝을 포함하는 `orderedAt`의 **KST 날짜** 필터이며 누락 경계·최대 기간을 로컬에서 만들지 않습니다. cursor는 불투명 문자열로 trim·디코딩·생성·Base64 제한 없이 `=`·`+`·`/`·`%`·정상 Unicode와 앞뒤 공백을 그대로 보존합니다. symbol·cursor의 256 UTF-16 코드 단위 상한, cursor의 빈 값·공백뿐인 값·제어문자·짝이 맞지 않는 surrogate 거부는 공식 보장과 구별되는 프로젝트 입력 정책입니다.

[TossOrderClient](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossOrderClient.java)의 `getClosedOrders(request)`는 고정된 `https://openapi.tossinvest.com/api/v1/orders`로 본문 없는 GET을 정확히 한 번 보냅니다. 검증된 기존 endpoint와 같은 origin의 고정 경로를 사용하며 목적지를 인자로 받지 않습니다. `status=CLOSED`와 정규화된 limit은 항상, symbol·from·to·cursor는 지정된 값만 각각 한 번 전송합니다. 값은 trim·디코딩 없이 UTF-8 퍼센트 인코딩하며 `%2F`는 `%252F`, `+`는 `%2B`, 공백은 `%20`으로 보냅니다. cursor의 `&`·`?`·`#`도 값으로 인코딩하여 파라미터·경로·fragment 변경을 막습니다. null 요청은 환경 조회·전송 전에 `BAD_REQUEST`입니다.

아래 Java 사용 예시는 첫 한 페이지만 반환합니다. JVM 시작 옵션과 환경변수는 아래 '토스 HTTP 운영 설정과 제한'을 따릅니다. 인증정보를 예시 코드에 넣지 않습니다.

```java
try (var client = new TossOrderClient()) {
    var request = new TossClosedOrdersRequest("AAPL", "2026-03-01", "2026-03-28", null, 20);
    TossClosedOrdersSnapshot page = client.getClosedOrders(request);
    // page.orders(), page.nextCursor(), page.hasNext(): 이 페이지의 주문과 서버 메타데이터
}
```

흐름은 **검증된 요청 → 기존 인증·제한 전송 → TossOrderMapper.mapClosedOrders → TossClosedOrdersSnapshot**입니다. 인증정보를 호출마다 다시 읽고 Bearer·계좌·Accept 헤더, 전송 직전 설정 재검사, 상태별 오류 분류와 자원 종료를 단건 조회와 공유합니다. 성공 응답의 잘못된 UTF-8·JSON·페이지는 원문 없는 `INVALID_RESPONSE`이며 부분 결과가 없습니다. `result` 객체에는 `orders` 배열, nullable `nextCursor`, boolean `hasNext` 키가 모두 필요합니다. hasNext=true이면 유효한 비어 있지 않은 cursor, false이면 null을 요구하며 항목 수에서 hasNext를 계산하지 않습니다. 빈 페이지와 짧은 다음 페이지도 허용합니다. 목록은 원문 순서의 불변 `TossOrderSnapshot` 목록이며 요청 limit 및 100건을 초과하면 자르지 않고 실패합니다. null 항목·같은 원문 orderId 중복·마지막 항목 손상도 전체 실패합니다. ID 대소문자는 정규화하지 않으며 중복 거부는 프로젝트 정확성 정책입니다.

단건과 목록은 동일한 주문 객체 변환을 사용하며 단건의 요청 ID 일치 검사를 유지합니다. 아래 파싱 자원 제한은 목록에도 적용하고 `result.orders` 항목 수까지 트리 생성 전에 제한합니다. 요청·페이지 모델 오류는 각각 `Invalid Toss closed orders request`·`Invalid Toss closed orders summary`이며 매퍼 오류는 기존 `Invalid Toss order response`입니다. 원문·파서 진단·cause·suppressed 예외·수집 로그를 남기지 않습니다.

`get_toss_closed_orders`는 이 기존 목록 HTTP 메서드를 MCP에 연결하며 전체 등록 도구는 아홉 개입니다. 자동 페이지 순회·기간 분할·주문별 상세 추가 조회를 하지 않으므로 한 페이지의 결과는 전체 수집을 보장하지 않습니다. 결제 거래내역·개별 체결·완결 Trade·손익을 생성하지 않으며 commission·tax·통화를 합산·배분·환산하거나 가상 체결 ID를 만들지 않습니다. 실제 주문·취소·자금 이동 기능은 없습니다. 실계정 주문 목록·권한·누락 여부 대조는 미검증입니다.

`TossClosedOrdersRequestTest`·`TossClosedOrdersSnapshotTest`·`TossOrderMapperTest`에는 입력·페이지 메타데이터·항목 수·중복·불변성, 국내 완료·해외 부분 체결·무체결 거부·체결이 남은 취소·대체 주문, 마지막 항목 실패와 파싱 경계의 합성 검사가 있습니다. `TossOrderClientTest`는 가상 인증정보와 로컬 서버로 raw query·단일 전송·매퍼 연결을 검사하며 목록 경로에도 기존 상태 오류·본문 제한·타임아웃·취소·독립 JVM 안전 설정 검사를 적용합니다. 재현은 Java 21과 준비된 Gradle·의존성 캐시 및 실행 환경의 자원 제한에서 다음 명령을 사용합니다. Gradle 배포본의 설치 경로가 다르면 같은 옵션을 유지해 해당 실행 파일을 사용합니다. 실행별 검증 결과는 프로젝트 문서와 별도로 관리합니다.

```bash
JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' \
  /opt/gradle/bin/gradle --offline --no-daemon --max-workers=1 --no-watch-fs --no-build-cache --console=plain test
```

### 파싱 제한과 합성 검증 범위

트리 생성 전에 전체 문서를 스트리밍으로 한 번 검사합니다. 이후 트리를 읽는 두 번째 순회까지 입력 크기에 선형이며, 무시되는 필드에도 같은 제한을 적용합니다. 다음은 공식 거래소 한도가 아닌 프로젝트 자원 정책입니다.

| 대상 | 허용 경계 |
| --- | --- |
| 원본 JSON 문자열 | 1,048,576 UTF-16 코드 단위 |
| 컨테이너 깊이 | 루트 객체 포함 64 |
| 디코딩 문자열·속성명 | 각각 256 UTF-16 코드 단위 |
| 원본 숫자 토큰 | 부호·소수점·지수를 포함해 128자 |

중복 키, 후행 문서, 손상된 꼬리와 경계 초과는 부분 결과 없이 거부합니다. 오류는 요청의 `Invalid Toss order request`, 매퍼의 `Invalid Toss order response`, 모델의 `Invalid Toss order summary` 고정 메시지이며 원문·파서 진단·cause·suppressed 예외를 붙이지 않고 로그를 기록하지 않습니다. 공통 파서 계층이나 새 의존성은 추가하지 않습니다.

새 요청·모델·매퍼 테스트에는 국내 완료·해외 부분 체결·무체결 거부·체결이 남은 취소 및 대체 주문의 합성 fixture, 필수/선택/null 계약, 십진 30/31자, 시간·날짜, 각 파싱 제한 경계·초과와 무시되는 필드, 중복 키·손상 꼬리, 오류 그래프·수집 로그의 비노출 검사가 포함됩니다. Java 21과 준비된 Gradle·의존성 캐시에서 위 '검증 범위와 연동 한계'의 오프라인 전체 `test` 명령으로 재현합니다. 테스트 소스는 실행 통과나 실계정 대조의 증거를 대신하지 않습니다.

### 토스 HTTP 운영 설정과 제한

서버 실행 환경의 비밀정보 설정 기능으로 다음 값을 프로세스 환경에 전달합니다. 외부에서 발급한 토큰의 준비·유효성 관리·교체는 운영자의 책임입니다. 클라이언트는 토큰 발급·갱신이나 계좌 목록 조회를 수행하지 않습니다. 비밀값을 소스·예시 파일·명령행·조회 인자에 넣지 않습니다.

| 환경변수 | 전송 및 프로젝트 검증 정책 |
| --- | --- |
| `TOSS_ACCESS_TOKEN` | `Authorization: Bearer <값>`. 비어 있지 않고 공백 없는 출력 가능 ASCII(`0x21`~`0x7e`), 최대 8192자 |
| `TOSS_ACCOUNT_SEQ` | `X-Tossinvest-Account` 헤더에 원문 전달. 최대 20자의 정규 ASCII 부호 있는 int64 십진 문자열(`0` 또는 `-?[1-9][0-9]*`이며 long 범위 이내) |

이 제한은 공식 인증정보 규격과 구별되는 프로젝트 정책입니다. 계좌의 양수 여부나 실제 존재를 추정하지 않으며, `+1`, `01`, `-0` 등 비정규 표기를 거부합니다. 계좌를 부동소수점으로 변환하지 않아 2^53 초과 값도 보존합니다. 두 값은 호출마다 환경 공급자에서 다시 읽습니다. 환경 공급자 실패·누락·빈 값은 `MISSING_CREDENTIALS`, 형식·범위 위반은 `INVALID_CREDENTIALS`로 전송 전에 실패합니다. 환경변수 교체는 다음 호출에 프로세스 환경 공급자가 새 값을 제공하도록 운영해야 하며, 부모 셸의 변경이 이미 실행 중인 JVM 환경을 갱신한다고 가정하지 않습니다.

위 실행 예시와 같이 **JVM 시작부터** `-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1`을 모두 지정해야 합니다. 클라이언트 생성 전, 유효 요청의 환경 조회 전, 전송 직전에 시작 인자와 현재 속성을 검사합니다. 옵션 누락·오류·충돌은 `UNSAFE_TRANSPORT_CONFIGURATION`이며 늦은 `System.setProperty()`로 대체할 수 없습니다. `jdk.httpclient.HttpClient.log`는 프로세스 수명 동안 미설정 또는 빈 값이어야 합니다. 비어 있지 않은 시작 설정은 이후 삭제해도 거부하며, 비어 있지 않은 현재 설정도 거부합니다. 검사 사이의 임의 동시 속성 변경까지 방지하지 않으므로 실행 중 이 설정을 변경하지 않아야 합니다.

연결 제한은 **3초**, 본문 완료까지 요청 전체 제한은 **10초**, 실제 수신 본문 상한은 **1 MiB(1,048,576바이트)**입니다. 고정 길이·chunked 본문 모두 상한 초과 시 부분 반환 없이 `RESPONSE_TOO_LARGE`로 실패하며, 지연·끝나지 않는 본문은 `TIMEOUT`으로 종료합니다. 잘못된 UTF-8 또는 매퍼가 거부한 성공 JSON은 `INVALID_RESPONSE`입니다. 리다이렉트·자동 재시도·추가 조회·페이지 순회는 없습니다. 대기를 중단하면 구독과 미완료 요청을 취소하고 인터럽트 플래그를 보존합니다. Spring은 생성된 토스 클라이언트의 `close()`를 컨텍스트 종료 시 호출하여 소유 HTTP 연결과 작업 자원을 종료합니다. 클라이언트를 직접 사용하는 소유자는 try-with-resources 또는 `close()`로 종료해야 합니다.

HTTP 400·401·403·404·429는 각각 `BAD_REQUEST`·`AUTHENTICATION_FAILED`·`PERMISSION_DENIED`·`ORDER_NOT_FOUND`·`RATE_LIMITED`, 3xx는 `REDIRECT_REJECTED`, 5xx는 `SERVER_ERROR`, 나머지 비정상 상태는 `UNEXPECTED_STATUS`입니다. 비정상 상태의 본문을 파싱하거나 헤더 메시지로 원인을 추정하지 않습니다. 네트워크·시간초과·인터럽트는 `NETWORK_ERROR`·`TIMEOUT`·`INTERRUPTED`로 구분합니다. [TossApiException](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossApiException.java)은 `Toss request failed: <범주>`만 전달하며 원문·인증정보·주문 ID·파서 진단·cause·suppressed 예외를 남기지 않습니다.

[TossOrderClientTest](src/test/java/io/github/lmj/tradeledger/adapter/toss/TossOrderClientTest.java)는 로컬 합성 응답으로 국내 완료·해외 부분 체결·무체결 거부·체결이 남은 취소 주문을 기존 매퍼까지 연결합니다. raw path·헤더 보존, 환경 검증, 독립 JVM 시작 설정, 본문 제한·시간초과, 최초·재사용 연결의 단일 전송, 인터럽트·취소·자원 종료와 오류·로그 비노출 검사도 포함합니다. 패키지 내부 테스트 주입은 고정 `/api/v1/orders/` 접두 경로와 리터럴 loopback HTTP(`127.0.0.1` 또는 `[::1]`)만 허용하며 userinfo·query·fragment·다른 호스트·경로를 거부합니다. 이 테스트의 존재는 실행 통과나 실계정 검증을 뜻하지 않습니다.

### `get_toss_order`: 지연 생성과 누적 체결 요약

여덟 번째 도구의 입력은 필수 문자열 `orderId` 하나입니다. 토큰·계좌·호스트·URL 인자는 받지 않습니다. `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`와 출력 스키마를 제공합니다. 비밀값 없는 합성 요청 예시입니다.

```json
{"name":"get_toss_order","arguments":{"orderId":"example-order-1"}}
```

```json
{
  "serviceId": "toss",
  "orderId": "example-order-1",
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "timeInForce": "DAY",
  "status": "CANCELED",
  "price": "70000.00",
  "quantity": "10.00",
  "orderAmount": null,
  "currency": "KRW",
  "orderedAt": "2026-03-28T00:30:00Z",
  "canceledAt": "2026-03-28T00:32:00Z",
  "execution": {
    "filledQuantity": "2.00",
    "averageFilledPrice": "69999.5000",
    "filledAmount": "139999.0000",
    "commission": "140.00",
    "tax": "0.00",
    "filledAt": "2026-03-28T00:31:15Z",
    "settlementDate": "2026-03-30"
  }
}
```

응답 최상위는 `serviceId="toss"`와 기존 주문 요약의 13개 필드이며 `execution`은 위 일곱 필드만 담습니다. 십진 값·후행 0·불투명 ID·미지 코드를 문자열 그대로 보존하고 시각은 UTC 문자열, 결제 예정일은 날짜 문자열로 전달합니다. `price`·`orderAmount`·`canceledAt`와 누적 요약의 nullable 여섯 필드는 명시적 null 및 nullable 출력 스키마를 가집니다. commission과 tax는 분리합니다. 성공 `TextContent`의 JSON과 `structuredContent`는 같은 데이터입니다.

흐름은 **기존 요청 모델 및 UTF-16 선검증 → ObjectProvider로 기존 지연 클라이언트 획득 → getOrder 한 번 → 순수 응답 변환**입니다. 누락·null·공백뿐인 값·제어문자·257 UTF-16 코드 단위 이상·짝이 맞지 않는 surrogate·잘못된 입력 유형은 `Toss request failed: BAD_REQUEST`로 실패하며 클라이언트 생성·환경 조회·전송이 없습니다. 유효 ID의 공백·특수문자·surrogate 쌍은 보존하고 경로 인코딩은 기존 클라이언트가 담당합니다.

키와 시작 옵션 없이도 서버 등록과 두 계산 도구를 사용할 수 있고 토스 클라이언트는 생성되지 않습니다. 첫 유효 조회에서 지연 생성하며, 필수 JVM 옵션 누락이나 진단 로깅 설정은 전송 없이 `Toss request failed: UNSAFE_TRANSPORT_CONFIGURATION`으로 실패합니다. 유효 옵션과 누락 인증정보는 `MISSING_CREDENTIALS`입니다. MCP 경계는 지연 빈 생성에 포장된 오류도 안전한 `Toss request failed: <범주>`로 전달합니다. 알 수 없는 provider·변환 오류는 `INTERNAL_ERROR`이며 원문·원인·suppressed 그래프를 제거합니다. 오류는 `isError=true`이고 부분 `structuredContent`가 없습니다.

[TossOrderResponseTest](src/test/java/io/github/lmj/tradeledger/mcp/TossOrderResponseTest.java)와 [TossOrderToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/TossOrderToolsTest.java)는 국내 완료·해외 부분 체결·무체결·체결이 남은 취소 주문, 입력 경계, 단일 GET·경로 인코딩, 오류와 수집 로그의 비노출, 독립 JVM 지연 생성 및 Spring 종료 회귀 검사를 포함합니다. 기존 최초·재사용 연결 단절의 재전송 금지 검사는 클라이언트 테스트에서 유지합니다.

### `get_toss_closed_orders`: 읽기 전용 한 페이지

아홉 번째 도구는 선택적 문자열 `symbol`·`from`·`to`·`cursor`와 정수 `limit`만 받으며 필수 인자가 없습니다. `status`는 입력으로 받지 않고 `CLOSED`로 고정합니다. `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`와 출력 스키마를 제공합니다. 기본 조회는 필터를 생성하지 않고 limit 20으로 한 페이지만 요청합니다. 인자 맵 생략·null·빈 객체와 각 인자의 명시적 null도 같은 기본값 정책을 따릅니다.

MCP 초기화 후 기본 조회 예시입니다.

```json
{"name":"get_toss_closed_orders","arguments":{}}
```

필터를 지정할 때 from·to는 `orderedAt`의 **KST 날짜 양끝 포함** 조건입니다. 두 날짜는 독립적으로 생략 가능하고 엄격한 `YYYY-MM-DD`를 사용하며 함께 지정하면 from≤to여야 합니다. 생략 경계나 최대 기간을 생성하지 않습니다.

```json
{"name":"get_toss_closed_orders","arguments":{"symbol":"AAPL","from":"2026-03-01","to":"2026-03-28","limit":20}}
```

응답 속성은 정확히 `serviceId="toss"`·`orders`·nullable `nextCursor`·`hasNext`입니다. orders의 각 항목은 위 단건 응답과 같은 `TossOrderResponse`이며 항목의 serviceId도 유지합니다. 원본 순서의 불변 목록으로 투영하며 문자열 정밀도·후행 0·미지 코드·명시적 null·UTC 시각·날짜와 분리된 commission·tax를 보존합니다. 빈 마지막 페이지 예시입니다.

```json
{"serviceId":"toss","orders":[],"nextCursor":null,"hasNext":false}
```

`hasNext=true`이면 호출자가 응답의 nextCursor를 해석·trim·디코딩하지 않고 그대로 후속 호출에 넣습니다. 다음 예시의 cursor는 합성 값이며 실제 호출에는 반환받은 값을 사용합니다. 나머지 필터는 같은 조회 조건으로 유지합니다.

```json
{"name":"get_toss_closed_orders","arguments":{"symbol":"AAPL","from":"2026-03-01","to":"2026-03-28","cursor":" example%2F=+/&?#한😀 ","limit":20}}
```

짧거나 빈 페이지여도 hasNext를 건수에서 추정하지 않고 서버 메타데이터를 보존합니다. 성공 TextContent의 JSON과 structuredContent는 같은 데이터입니다. 공식 조회 범위는 Open API 지원 지정가·시장가·장마감지정가 주문이며 지원하지 않는 호가 유형은 목록·상세에서 제외됩니다. 한 페이지는 전체 계좌 주문 내역이나 수집 완료를 뜻하지 않습니다.

흐름은 **TossClosedOrdersRequest 선검증 → 기존 ObjectProvider 지연 클라이언트 획득 → getClosedOrders 한 번 → TossClosedOrdersResponse 순수 변환**입니다. 문자열 필드의 숫자·배열·객체·boolean, limit의 소수·문자열·범위 초과는 annotation 어댑터의 강제 변환 전에 `BAD_REQUEST`로 거부합니다. 잘못된 날짜·symbol·cursor도 provider 획득·환경 조회·전송 전에 거부합니다. symbol은 대소문자를 보존하는 `[A-Za-z0-9.\-]+`와 256 UTF-16 코드 단위 상한을 적용합니다. cursor도 256 단위까지 허용하고 빈 값·공백뿐인 값·제어문자·잘못된 surrogate를 거부하며 유효 공백·특수문자·surrogate 쌍은 그대로 보존합니다.

두 토스 조회는 같은 JVM 운영 설정과 오류 허용 목록을 사용합니다. 키·시작 옵션 없이도 서버 등록과 두 계산 도구가 동작하며 클라이언트를 생성하지 않습니다. 첫 유효 조회는 필수 시작 옵션 누락·진단 로깅 설정 시 `UNSAFE_TRANSPORT_CONFIGURATION`, 유효 옵션과 키 누락 시 `MISSING_CREDENTIALS`로 전송 없이 실패합니다. 위 Java 21 재현 명령의 `JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1'`을 서버 시작부터 적용합니다. 지연 빈 실패·알 수 없는 실패·변환 실패는 안전한 범주만 반환하며 원문·인증정보·cause·suppressed 그래프와 부분 structuredContent를 제거합니다.

자동 페이지 순회·기간 분할·상세 추가 조회, 결제 거래내역·개별 체결·공통 체결·완결 Trade·손익 생성, 비용·통화 합산·배분·환산과 실제 주문·취소·자금 이동은 수행하지 않습니다. 실계정·조회 전용 권한 분리·전체 내역 대조는 미검증입니다. [TossClosedOrdersResponseTest](src/test/java/io/github/lmj/tradeledger/mcp/TossClosedOrdersResponseTest.java)와 기존 TossOrderToolsTest는 순수 페이지 투영, 등록 핸들러·로컬 HTTP·독립 JVM 검사를 포함합니다. 테스트 소스의 존재는 실행 통과를 뜻하지 않습니다.

### 결제 거래내역 한 페이지: 순수 요청·모델·제한 매퍼

공식 근거는 [OpenAPI JSON](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)의 `GET /api/v1/transactions` (`getTransactions`), `Transaction`·`PaginatedTransactionResponse`입니다. 참조 계약은 문서 버전 1.2.28이며 `latest`는 바뀔 수 있습니다. 지원 흐름은 **검증된 TossTransactionsRequest + 합성 성공 JSON → TossTransactionsMapper.map(request, json) → 불변 TossTransactionsSnapshot**입니다. 인증정보·환경 조회·HTTP·Spring·MCP 연결은 없고 등록 도구는 기존 아홉 개입니다. 기존 주문 매퍼·공통 체결 모델과 독립적이며 새 의존성이나 공통 파서 계층을 추가하지 않습니다.

[TossTransactionsRequest](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossTransactionsRequest.java)는 선택적 `from`·`to`·`cursor`·`limit`만 받습니다. `transactionTypes`를 보내지 않는 전체 유형 조회 계약입니다. limit은 생략·null이면 20, 범위는 1~100입니다. from·to는 독립적으로 생략하는 엄격한 `YYYY-MM-DD`이며 둘 다 있으면 from ≤ to입니다. 윤년·동일 날짜·미래 날짜를 허용하며 생략된 경계를 생성하지 않습니다. 필터는 **양끝 포함 KST 결제일** 기준이고 미결제에는 예상 결제일을 적용합니다. 오늘을 to로 지정하면 예상 결제일이 미래인 미결제가 제외될 수 있습니다.

cursor는 trim·디코딩·생성 없이 보존합니다. **256 UTF-16 코드 단위** 상한, 빈 값·공백뿐인 값·ISO 제어문자·짝이 맞지 않는 surrogate 거부는 공식 길이 보장이 아닌 프로젝트 정책입니다. 유효한 앞뒤 공백·특수문자·Unicode는 그대로 유지합니다. 계좌나 날짜 필터를 바꾸면 이전 cursor를 재사용하지 않는 것이 공식 계약입니다.

[TossTransactionSnapshot](src/main/java/io/github/lmj/tradeledger/application/model/TossTransactionSnapshot.java)은 다음 17개 필드를 보존합니다. 성공 봉투의 `result`는 객체이고 `transactions`·`nextCursor`·`hasNext`와 각 항목의 17개 키가 모두 필수입니다. nullable 필드도 키 누락은 거부하고 명시적 null만 보존합니다.

| 필드 | 처리 |
| --- | --- |
| `transactionType`, `marketCountry`, `currency` | 필수 비-null 문자열. 미지 코드도 원문 보존 |
| `symbol`, `assetClass`, `isinCode` | nullable 문자열. assetClass의 미지 코드도 보존 |
| `quantity`, `filledPrice`, `amount`, `netAmount`, `exchangeRate` | nullable 십진 문자열 |
| `commission`, `tax` | 필수 비-null 십진 문자열. 통화·비용·정산 금액을 별도 보존 |
| `settlementDate`, `settled` | 필수 LocalDate와 boolean. 결제 여부를 날짜에서 추정하지 않음 |
| `transactionTime`, `isCancellation` | nullable 오프셋 시각을 Instant로 변환, 필수 boolean 취소 여부 보존 |

십진 필드는 공식 최대 30자를 적용하고 정밀도·후행 0·null을 문자열 그대로 유지합니다. 일반 비음수 십진 표기 `[0-9]+(?:\.[0-9]+)?`만 받는 것은 프로젝트 정책입니다. 부호·지수·NaN·Infinity·숫자 JSON 토큰을 거부합니다. 일반 문자열도 프로젝트 정책으로 256 UTF-16 단위 상한, 빈 값·공백뿐인 값·제어문자·잘못된 surrogate 거부를 적용합니다. settlementDate는 유효한 `YYYY-MM-DD`, transactionTime은 오프셋을 포함해야 합니다.

[TossTransactionsSnapshot](src/main/java/io/github/lmj/tradeledger/application/model/TossTransactionsSnapshot.java)은 순서를 방어 복사한 불변 목록입니다. 빈 마지막 페이지, 빈·짧은 다음 페이지를 허용하며 `hasNext=true`에는 유효 cursor, false에는 null을 요구합니다. 건수로 종료를 추정하지 않고 요청 limit 또는 100건을 초과하면 자르지 않고 실패합니다. 거래 식별자가 없으므로 동일 값의 반복 행도 보존하며 가상 ID·중복 제거·페이지 병합을 하지 않습니다.

[TossTransactionsMapper](src/main/java/io/github/lmj/tradeledger/adapter/toss/TossTransactionsMapper.java)는 설치된 Jackson으로 트리 생성 전에 전체 JSON을 순회합니다. 프로젝트 제한은 원본 **1,048,576 UTF-16 단위**, 루트 포함 깊이 **64**, 디코딩 문자열·속성명 각각 **256**, 원본 숫자 토큰 **128자**, 직접 `result.transactions` 항목 수 **요청 limit 이하 및 최대 100**입니다. 무시되는 필드도 검사하며 중복 키·후행 문서·손상된 마지막 항목·오류 봉투·null 입력은 부분 결과 없이 거부합니다. 고정 오류 메시지는 `Invalid Toss transactions request`, `Invalid Toss transaction summary`, `Invalid Toss transactions summary`, `Invalid Toss transactions response`이며 원문·파서 진단·cause·suppressed 예외·로그를 남기지 않습니다.

국내 미결제 합산 한 건이 결제 후 여러 행으로 바뀔 수 있고 미국 주식·옵션·채권의 제공 단위도 다릅니다. 거래내역을 개별 체결로 간주하지 않습니다. 금액 재계산·환산·잔액 변화 추정·주문 연결·개별 체결·공통 체결·Trade·손익 생성, 자동 페이지 수집과 주문·취소·자금 이동은 지원하지 않습니다. 국내 합산 미결제·결제 완료, 미국 주식·옵션·채권, 현금·환전·배당·증권 입출고·취소 내역은 합성 fixture로만 검사하며 **실계정 결제 거래내역 대조는 미검증**입니다.

대응하는 네 테스트 클래스는 요청 경계, 17개 필드와 9개 nullable 계약, 페이지·불변성·반복 행, 자원 경계와 오류 비노출을 검사합니다. 기존 전체 회귀와 함께 Java 21 및 준비된 Gradle·의존성 캐시에서 다음 명령으로 재현합니다. 실행별 결과는 프로젝트 문서에 기록하지 않습니다.

```bash
JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' \
  /opt/gradle/bin/gradle --offline --no-daemon --max-workers=1 --no-watch-fs --no-build-cache --console=plain test
```

### 남은 실계정·권한 확인

실계정·이용 자격·조회 전용 권한 분리는 미검증입니다. 허용 계좌·시장, 오류·호출 예산 및 실제 주문 요약과 수수료·세금의 의미를 공식 계약 및 비식별 표본으로 대조해야 합니다. 토큰 발급·갱신, 전체 내역 수집·페이지 반복·기간 분할은 별도 범위입니다.

실제 개별 체결의 안정된 식별자와 비용 귀속, 실제 결제 거래내역·정정 규칙은 요약 모델과 구분해 검토해야 합니다. 비식별 표본은 원본 필드·통화·시각 의미를 유지하되 개인·계좌 식별정보와 비밀값을 제거해야 합니다. 매수·매도 매칭·원가 배분 계약 없이 완결 거래나 손익을 추정하지 않습니다. 파일 내보내기 규격도 별도 확인 대상입니다.
