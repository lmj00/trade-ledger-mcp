# trade-ledger-mcp

완결된 거래의 실현 손익을 계산하고 업비트 주문·바이낸스 현물 체결을 조회하는 읽기 전용 MCP 서버입니다. 로컬 CSV 저널을 요약하는 `summarize_journal`, 구조화된 완결 거래를 분석하는 `analyze_trades`, 주문 UUID로 주문·실제 체결을 조회하는 `get_upbit_order`, 종료 주문 요약을 조회하는 `get_upbit_closed_orders`, 바이낸스 단일 symbol 최근 체결을 조회하는 `get_binance_account_trades`, 업비트 단건 주문을 공통 체결 형식으로 조회하는 `get_upbit_order_executions`, 바이낸스 최근·필터 체결을 공통 형식으로 조회하는 `get_binance_account_executions`를 제공합니다. 등록 도구는 이 일곱 개이며 주문 실행·취소, 입출금·이체 등 자금 이동 기능은 없습니다.

두 계산 도구의 입력은 프로젝트가 정의한 **사용자 정의 완결 거래 형식**입니다. 거래소 조회 응답은 별도 주문·체결 또는 종료 주문 요약 스냅샷으로 노출하며 완결 거래나 손익으로 변환하지 않습니다. 바이낸스 공통 체결 도구까지 기존 일곱 도구가 현재 소스에 반영되어 있습니다. 2026-11-13 공식 검색 색인 조사로 토스증권 주문 조회 API의 존재 근거를 확보했지만 상세 계약 일부·이용 자격·조회 전용 권한 분리 가능성은 미확인이고, 어댑터는 미구현·실계정은 미검증입니다. 이번 후보는 `get_binance_account_executions`에 기존 선택적 문자열 `fromId`·`startTime`·`endTime`·`orderId` 필터를 연결하고 `RECENT_SYMBOL`과 `FILTERED_SYMBOL`을 구분합니다. 의존성·인증 흐름은 기존 경로를 사용합니다. 등록 도구 수·출력 구조·필터 생략 시 최근 조회 계약과 Java 시작 옵션은 유지합니다. **이번 공통 필터 후보의 테스트는 미실행이며 서버 지정 검증과 독립 검토는 대기 상태입니다.** 기존 적용·검토 이력, 별도 게시 CI 관측, 실계정 검증은 아래에서 구분합니다.

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

업비트와 바이낸스 클라이언트는 각각 첫 조회 시 생성하며 생성된 클라이언트는 Spring 컨텍스트 종료 시 닫습니다. **키나 전송 시작 옵션이 없어도 두 클라이언트를 생성하지 않고 서버 시작과 두 계산 도구의 등록·호출은 가능합니다.** 업비트 조회에는 서버 프로세스 환경변수 `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY`와 **주문조회 권한만** 필요합니다. 주문·출금 권한은 필요하지 않습니다. 비밀값은 프로세스 환경으로 전달하며 MCP 인자·예시 파일·명령행에 넣지 않습니다. 호스트와 URL은 환경변수나 도구 인자로 받지 않습니다. 업비트 목적지는 단건 조회의 `https://api.upbit.com/v1/order`와 종료 주문 요약의 `https://api.upbit.com/v1/orders/closed` 두 고정 GET 경로입니다.

바이낸스 조회는 서버를 실행하는 환경의 비밀정보 설정 기능으로 아래 두 환경변수를 지정한 뒤 같은 서버 프로세스에 전달합니다. 조회 전용 키를 사용하며 주문·자금 이동 권한을 부여할 필요가 없습니다. 키는 도구 호출마다 서버 환경에서 읽고 별칭으로 선택하지 않습니다.

| 환경변수 | 용도 |
| --- | --- |
| `BINANCE_API_KEY` | 바이낸스 현물 계정 조회용 API 키. `X-MBX-APIKEY` 헤더로 전송 |
| `BINANCE_SECRET_KEY` | 쿼리 HMAC-SHA256 서명용 시크릿. 원문은 전송하지 않음 |

바이낸스 목적지는 고정된 `https://api.binance.com/api/v3/myTrades`입니다. 두 변수를 설정한 서버도 위 실행 JAR 예시의 두 `-D` 시작 옵션이 필요합니다. `accountAlias`에는 키·시크릿·계좌 식별정보를 넣지 않습니다.

조회 프로세스는 시작부터 `-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1`을 지정해야 합니다. JDK HTTP 클라이언트의 암묵적 재전송까지 차단하기 위한 기존 보호 장치이며 시작 후 속성을 바꾸는 것으로 대체할 수 없습니다. 옵션이 없으면 조회는 `UNSAFE_TRANSPORT_CONFIGURATION`으로 실패하고 계산 도구는 계속 사용할 수 있습니다. 옵션이 올바르고 키가 없으면 `MISSING_CREDENTIALS`로 실패합니다. **자동 재시도와 리다이렉트 추적은 없습니다.** 테스트의 로컬 HTTP 클라이언트에도 같은 시작 옵션이 필요하므로 위 테스트 명령은 이를 테스트 JVM에도 전달합니다. `build`도 테스트를 실행하므로 같은 `JAVA_TOOL_OPTIONS`를 적용합니다.

**HTTP 진단 로깅을 활성화하지 마십시오.** `jdk.httpclient.HttpClient.log`의 요청·헤더·본문 로깅은 API 키·JWT·서명·응답 원문을 노출할 수 있습니다. 바이낸스와 업비트 클라이언트는 이 속성의 비어 있지 않은 현재 설정 또는 JVM 시작 인자를 원인 예외 없이 `UNSAFE_TRANSPORT_CONFIGURATION`으로 거부합니다. 업비트는 단건 주문(`get_upbit_order`)과 종료 주문(`get_upbit_closed_orders`) 모두 HTTP 클라이언트 생성 전, 유효 조회의 인증정보 조회·서명 전, 실제 전송 직전에 검사합니다. 시작 후 지우거나 뒤에 빈 설정을 덧붙여도 비어 있지 않았던 시작 설정을 허용하지 않습니다. 미설정 또는 빈 값만 허용하며 **프로세스 실행 중 진단 로깅 설정을 변경하지 않는 운영 조건**이 필요합니다. 검사 사이의 임의 동시 속성 변경까지 방지하지는 않습니다. 애플리케이션 콘솔 로깅을 끄는 것만으로 JDK HTTP 진단 로깅을 차단할 수는 없습니다. [Java 21 공식 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html)는 진단 로깅 대상과 실행 중 `System.setProperty()` 적용이 보장되지 않음을 설명합니다.

## 적용·검토 이력과 검증 상태

현재 공통 필터 후보는 공통 도구·모델·응답 설명과 README, 순수 변환·DTO·로컬 HTTP·등록 핸들러·애플리케이션 스키마 테스트를 수정했습니다. 요청·클라이언트·매퍼의 기존 검증과 전송을 재사용합니다. 개발 환경의 `java -version`은 `java: command not found`(종료 코드 127)였고 Java·Gradle 실행기를 PATH에서 찾지 못했으며 테스트는 실행하지 않았습니다. 서버 지정 Java 21·오프라인·자원 제한·필수 `JAVA_TOOL_OPTIONS` 환경의 검증과 독립 검토는 대기입니다. 아래 이전 후보의 기록은 이 후보의 통과 근거가 아닙니다.

시간 필터 구현의 이전 후보(`865d743032037a414a48f706891e076c621d5686acc32186a25130adf0506a31`): 제공된 서버 기록에서 Java **21.0.11**, Gradle **9.5.1** 및 필수 `JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1'`을 적용한 `/opt/gradle/bin/gradle test`의 전체 지정 검증이 **BUILD SUCCESSFUL, 종료 코드 0, OOM 없음**으로 기록되었습니다. 지정된 `--offline --no-daemon --max-workers=1 --no-watch-fs --no-build-cache --console=plain --init-script /opt/hub-test/limits.gradle --info`, `GRADLE_USER_HOME`, 읽기 전용 의존성 캐시, `JAVA_OPTS`·`-Dorg.gradle.jvmargs`의 JVM 접근·메모리·CPU 제한도 포함한 결과입니다. 이 기록은 해당 digest의 서버 실행 증거이며 개발 단계에서 직접 실행한 결과가 아닙니다. 이전 독립 리뷰는 README의 시간 필터 설명·검증 기록 누락으로 승인을 보류했습니다. 이번 단계에서는 README를 수정했으며 **수정 후보의 테스트는 직접 실행하지 않았고 서버 지정 재검증·독립 검토·적용은 대기 상태입니다.**

과거 `fromId` 후보의 실패·수정 이력: 제공된 서버 기록은 **653개 테스트 중 1개 실패, 종료 코드 1, OOM 없음**이었습니다. `TradeLedgerMcpApplicationTests.binanceSchemaExposesOnlyAliasSymbolAndOptionalLimitWithStringIds`의 입력 속성 단언이 세 인자만 허용해 추가된 `fromId`와 충돌했고, 이후 네 입력 속성과 문자열 타입·두 필수 인자를 검사하도록 수정되었습니다. 이는 과거 후보의 기록이며 현재 공통 필터 후보의 검증 결과가 아닙니다. 현재 스키마는 아래와 같이 일곱 입력 속성을 검사합니다.

| 구분 | 근거와 범위 | 현재 소스에 대한 해석 |
| --- | --- | --- |
| 기존 작업의 적용·검토 기록 | 제공된 기록 `db597e38-1ca8-4d6f-8a7c-a1ec5a4e00cc`. 현재 소스에는 일곱 도구와 공통 체결 변환이 반영되어 있음 | 기존 작업의 이력으로 참조합니다. 제공된 식별자만으로 상세 테스트 수·검토 판정을 새로 단정하지 않으며 이번 공통 필터 후보의 검증 결과와 구분합니다. |
| 별도 게시 CI 관측 | 제공된 서비스 관측 `01c02598-3710-49b5-bddb-de1e74147d67` | 게시 CI의 별도 관측 이력입니다. 대상 소스 리비전·실행 환경·검증 범위의 일치 근거가 없어 현재 소스의 테스트·독립 검토 결과로 대체하지 않습니다. |
| 이전 시간 필터 README 수정 후보 | 2026-11-13 Binance 공식 계정 체결·일반 REST 문서와 요청 모델·도구 소스를 대조. 테스트 추가·실행 없음 | 이전 시간 필터 후보의 서버 통과와 구분하며, 수정 후보의 서버 지정 재검증과 독립 검토는 대기입니다. 문서 대조는 승인이나 적용을 뜻하지 않습니다. |
| 실계정 대조 | 이번 작업에서 계정 접근 없음 | 업비트·바이낸스·토스증권 모두 미검증입니다. 적용 기록이나 게시 CI 관측도 실계정 검증을 대신하지 않습니다. |

이전 후보의 서버 검증 기록(현재 문서 후보의 결과 아님): Java **21.0.11**, Gradle **9.5.1**에서 `JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1'`을 적용해 `/opt/gradle/bin/gradle --offline --no-daemon --max-workers=1 --no-watch-fs --no-build-cache --console=plain --init-script /opt/hub-test/limits.gradle --info test`를 실행한 기록이 있습니다. 서버가 지정한 `GRADLE_USER_HOME`, 읽기 전용 의존성 캐시, `JAVA_OPTS`와 `-Dorg.gradle.jvmargs`의 JVM 접근·메모리·CPU 제한도 적용된 결과입니다. **467개 테스트 중 3개 실패, 종료 코드 1, OOM 없음**으로 기록되었으며 통과하지 못했습니다. 제공된 실패 스택에서는 독립 JVM의 `ApplicationContextRunner`가 다른 테스트의 `UpbitOrderToolsTest.LocalConfiguration`을 스캔해 업비트 로컬 클라이언트를 즉시 생성했고, `UNSAFE_TRANSPORT_CONFIGURATION`으로 컨텍스트 시작이 실패했습니다. 나머지 두 실패의 상세 원인은 잘린 서버 출력으로 확인할 수 없습니다.

수정 반영 기록: 직접 생성하는 테스트 컨텍스트의 스캔을 운영 구성 패키지로 제한하고 도구를 명시적으로 등록해 테스트용 로컬 구성이 유입되지 않도록 했습니다. 오류 주입 컨텍스트와 저널 오류 검증 컨텍스트에도 같은 격리를 적용하고 다른 로컬 클라이언트 Bean이 없다는 단언을 추가했습니다. 기존 단일 전송·독립 JVM·`@DirtiesContext`·자원 종료 단언은 유지합니다.

이전 개발 단계 기록: **업비트 진단 로깅 차단 후보의 테스트는 해당 개발 단계에서 실행하지 않았습니다.** 당시 `java -version`은 `java: command not found`(종료 코드 127)로 실행 불가였습니다. 독립 JVM 회귀 검사에는 두 조회의 시작 로깅 설정·설정 삭제·빈 값 덮어쓰기·생성 후 활성화 거부, 인증정보 조회와 전송 0회, 빈 로깅 설정의 합성 응답 성공 및 등록 MCP 핸들러의 안전한 오류를 추가했습니다. 바이낸스 공통 응답도 해당 개발 단계에서는 테스트를 실행하지 않았습니다. 이들은 과거 개발·실패·수정 이력입니다. 이번 공통 필터 후보의 검증은 서버가 별도로 실행하는 전체 `test`와 독립 검토 결과를 기다립니다. Java 21, 필수 `JAVA_TOOL_OPTIONS`, 서버 지정 오프라인·자원 제한·초기화 스크립트를 포함한 명령을 기준으로 하며, 위의 축약 명령만으로 검증 범위의 동등성이나 통과를 주장하지 않습니다.

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

일곱 도구 모두 읽기 전용입니다. 아래 요청은 MCP 초기화 이후 `tools/call`에 전달할 `params` 예시이며, 출력은 응답 DTO의 JSON 데이터 예시입니다. 전체 JSON-RPC 요청·응답 봉투와 MCP `content` 등 전송 계층 필드는 생략했습니다. 금액 출력은 정밀도 손실을 피하도록 일반 십진수 **문자열**입니다. 계산 도구의 건수는 정수이고 종료 주문의 `tradesCount`와 바이낸스의 `id`·`orderId`·`orderListId`는 큰 정수의 정밀도를 보존하는 십진 문자열입니다.

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

프로젝트 상한은 연결 **3초**, 본문 수신까지 요청 전체 **10초**, 응답 본문 **1 MiB(1,048,576바이트)**, 단건 주문당 체결 **0~1,000건**, 십진 문자열 **128자**, 기타 원문 문자열 **256자**입니다. UUID는 공백 없이 36자이며 초과 데이터는 부분 반환하지 않습니다. 이 상한은 업비트의 공식 서비스 상한과 구별됩니다. 단건 조회와 아래의 한 기간 종료 주문 요약 조회를 지원하며 페이지 순회·기간 분할 수집·자동 재시도·호출 속도 조절기는 구현하지 않았습니다.

제공 규격에 따른 공식 호출 제한은 **`default` 그룹 초당 30회 공유 한도**입니다. 이 단건 주문 조회만의 전용 30회가 아니며 같은 계정의 해당 그룹 API 사용량과 공유합니다. 호출자는 다른 클라이언트의 사용량도 함께 조정해야 하며 429는 `RATE_LIMITED`로 반환합니다.

근거를 식별하는 공식 문서 링크는 [개별 주문 조회](https://docs.upbit.com/kr/reference/get-order), [인증](https://docs.upbit.com/kr/reference/auth), [호출 수 제한](https://docs.upbit.com/kr/reference/rate-limits)입니다. 제공된 규격의 `GET /v1/order?uuid=...`, 주문조회 권한, HS512 JWT의 `access_key`·새 `nonce`·미인코딩 쿼리 SHA-512 해시를 구현 기준으로 삼았습니다. 시크릿은 Base64 디코딩하지 않습니다. 이번 작업에서는 외부 링크의 최신 내용과 실계정 동작을 확인하지 않았습니다.

[UpbitOrderToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/UpbitOrderToolsTest.java)는 가짜 키와 로컬 HTTP 대역을 실제 등록 MCP `callHandler`에 연결합니다. 기존 단건 체결·주문 수수료·정밀도·오류 검사와 종료 주문의 기본값·선택 인자·시간 정규화·입력 경계·빈 배열·큰 정수 문자열·잘림 가능성·오류별 단일 전송 검사를 포함합니다. 성공 텍스트 JSON 전체와 `structuredContent`가 같은지, 응답과 수집한 애플리케이션/MCP 로그·예외 스택에 키·JWT·비공개 원문 표식이 없는지도 검사하도록 작성되어 있습니다. 기존 테스트의 존재와 이번 공통 필터 후보의 서버 검증 결과는 구분합니다.

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

[CommonExecutionResponseTest](src/test/java/io/github/lmj/tradeledger/mcp/CommonExecutionResponseTest.java), 등록 스키마 테스트와 기존 로컬 HTTP 주입점의 [UpbitOrderToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/UpbitOrderToolsTest.java)는 null·필드 대응·불변성·별칭 경계·단일 전송·안전한 오류·로그 비노출 회귀 검사를 포함합니다. 기존 적용·검토 이력은 위 검증 상태 절을 참조합니다. 이번 문서 변경에서는 테스트를 추가하거나 실행하지 않았으며 실계정은 미검증입니다.

### `get_upbit_closed_orders`

종료 주문(`done`, `cancel`)의 **요약만** 한 번 조회합니다. 지정한 일곱 인자는 모두 선택 사항이며 키·시크릿·호스트·URL 인자는 없습니다. 등록 힌트는 `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=true`입니다.

| 인자 | 계약 |
| --- | --- |
| `market` | 대문자 시장 코드, 예: `KRW-BTC`. 생략 시 전송하지 않음 |
| `state` | `done` 또는 `cancel`. `states`와 동시 지정 금지 |
| `states` | 중복 없는 비어 있지 않은 `done`/`cancel` 배열. 상태 인자 모두 생략 시 `["done", "cancel"]` |
| `startTime`, `endTime` | 두 경계를 함께 지정하거나 모두 생략. 오프셋 ISO8601 또는 정수 epoch 밀리초 문자열을 UTC ISO8601로 정규화. 시작 ≤ 종료, 최대 정확히 7일 허용 |
| `limit` | 1~1000, 기본 100 |
| `orderBy` | `asc` 또는 `desc`, 기본 `desc` |

기간을 생략하면 경계를 전송하지 않고 제공된 업비트 규격의 최근 7일 기본 동작에 맡깁니다. 단독 시간 경계, 역전 또는 7일 초과 기간, 상태 충돌·중복·오류, 잘못된 시장·정렬, 범위 밖 `limit`은 클라이언트 생성 전에 입력값과 원인을 제거한 `BAD_REQUEST`로 반환합니다. 기존 단건 도구의 잘못된 UUID는 계속 `INVALID_UUID`입니다.

기본 호출은 다음과 같으며 `GET /v1/orders/closed`에 `states[]=done&states[]=cancel&limit=100&order_by=desc`를 전송합니다. 상태 배열 키는 실제 URL에서 인코딩됩니다.

```json
{"name":"get_upbit_closed_orders","arguments":{}}
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

종료 주문 계약의 제공 근거는 [종료 주문 조회 공식 규격](https://docs.upbit.com/kr/reference/list-closed-orders)입니다. 이번 작업에서는 이 링크를 새로 조회하거나 실계정 동작을 검증하지 않았습니다. 로컬 대역 검증과 실계정 대조는 별도 단계입니다.

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

[Binance 공식 Account trade list](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account)는 `orderId` 단독 및 `orderId`+`fromId`, 시간 경계의 단독·동시 조합, 최대 24시간 간격, `fromId` 하한과 기본 limit 500·최대 1000을 명시합니다(2026-11-13 확인). [General REST API Information](https://developers.binance.com/en/docs/products/spot/rest-api)은 필터 생략 시 최근 항목, 시작 경계부터 오래된 순서, 종료 경계까지 최근 항목, 두 경계 안의 조회 동작 및 기본 응답 시각 단위가 밀리초임을 설명합니다(2026-11-13 확인). 공식 API의 시간 입력은 밀리초·마이크로초를 지원하지만, **이 프로젝트는 정규 ASCII 십진 문자열·비음수 int64·밀리초 전용 입력을 정책으로 제한합니다.** 마이크로초 단위 선택이나 자동 단위 추정·변환은 제공하지 않습니다. `get_binance_account_executions`도 같은 네 필터를 받으며 필터 생략 시 `RECENT_SYMBOL`, 지정 시 `FILTERED_SYMBOL`을 반환합니다. 추가 조회나 측정하지 않은 성능 개선을 주장하지 않습니다. **실계정 동작은 미검증이며, 이번 공통 필터 후보의 서버 재검증과 독립 검토는 대기 상태입니다.**

현재 `TradeLedgerMcpApplicationTests.binanceSchemaExposesOnlyAliasSymbolAndOptionalLimitAndFiltersWithStringIds`와 `BinanceAccountTradesToolsTest`는 입력 키가 정확히 `accountAlias`·`symbol`·`limit`·`fromId`·`startTime`·`endTime`·`orderId`인지 검사합니다. 네 필터의 문자열 자료형과 필수 인자가 `accountAlias`·`symbol`뿐인지 검증하며 기존 일곱 도구·출력·등록 힌트 단언도 유지합니다. 요청·매퍼·로컬 HTTP·등록 핸들러 테스트에 주문 필터의 허용 조합, 잘못된 입력, 큰 정수, 원시 쿼리 HMAC·단일 GET, 주문 불일치와 범위 위반의 전체 실패를 추가했습니다. 이 후보의 서버 지정 Java 21·오프라인·자원 제한 및 `JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1'` 환경의 `/opt/gradle/bin/gradle test` 결과는 아직 제공되지 않았습니다. 테스트 실행과 독립 검토는 대기이며 미실행을 통과로 표시하지 않습니다.

유효 요청은 기존 `getAccountTrades`를 한 번 호출하고 GET을 정확히 한 번 전송합니다. 오류에 따른 추가 조회·자동 재시도는 없습니다. 키 누락·설정 거부·인증·보안 제한·418·429·5xx·리다이렉트·네트워크·시간 초과·본문 초과·손상 JSON은 기존 `BinanceErrorCode` 범주만 노출합니다. `-1021`은 `INVALID_TIMESTAMP`, `-1022`는 `INVALID_SIGNATURE`, `-2014`는 `INVALID_API_KEY_FORMAT`, `-2015`는 `KEY_IP_OR_PERMISSION_REJECTED`이며 **-2015의 원인을 키·IP·권한 중 하나로 단정하지 않습니다.** 안전한 범주가 없는 초기화 또는 예상 밖 오류는 원인 없는 `INTERNAL_ERROR`로 처리합니다.

위 공식 규격과 확인 날짜는 기존 문서 작업의 기록입니다. 이번 공통 필터 구현 단계에서는 외부 문서를 다시 조회하지 않았으며 기존 요청 정책을 재사용했습니다. [BinanceAccountTradesToolsTest](src/test/java/io/github/lmj/tradeledger/mcp/BinanceAccountTradesToolsTest.java)는 가짜 키·고정 시계·기존 루프백 주입점을 통한 등록 핸들러 테스트, 입력·출력 경계, 오류·로그 비노출, 독립 JVM 설정 거부와 종료 단언을 포함합니다. 기존 클라이언트의 단일 전송·독립 JVM·자원 종료 검사도 있습니다. 기존 적용·검토 이력과 이번 공통 필터 후보의 서버 검증 대기는 위 검증 상태 절에서 구분합니다.

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

응답 항목이 `fromId` 하한·시간 경계·`orderId`와 맞지 않으면 첫 항목 또는 정상 항목 뒤에 있더라도 기존 매퍼가 전체를 `INVALID_RESPONSE`로 거부합니다. 등록 핸들러는 `isError=true`이고 부분 `structuredContent`는 없습니다. 등록 스키마·필터 조합·큰 ID·시간 경계·원시 쿼리 HMAC·단일 GET·안전한 오류와 로그·순수 변환의 범위 및 null 계약 회귀 테스트를 소스에 추가했습니다. **이번 공통 필터 후보의 테스트는 개발 환경에서 미실행입니다.** `java`·`gradle` 실행 파일이 없어 서버 지정 Java 21·오프라인·자원 제한·초기화 스크립트와 `JAVA_TOOL_OPTIONS='-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1'` 환경의 `/opt/gradle/bin/gradle test` 결과는 대기입니다. 서버 테스트·독립 검토·변경 적용은 별도이며 실계정은 미검증입니다.

기존 환경 키와 두 JVM 시작 옵션이 그대로 필요하며 HTTP 진단 로깅 거부 정책도 같습니다. 키·시작 옵션 없이 서버 시작과 계산 도구 호출은 가능하고 두 거래소 클라이언트의 지연 생성도 유지합니다. 키 누락·설정 거부·인증·보안·호출 제한·서버·네트워크·시간 초과·본문 초과·손상 응답·초기화 실패는 기존 안전한 Binance 오류 범주로만 반환하며 추가 전송은 없습니다. 예상 밖 변환 실패는 원인 없는 `INTERNAL_ERROR`로 처리하고 응답 원문이나 원인 예외는 노출하지 않습니다. 회귀 테스트와 MCP 연결은 현재 소스에 반영되어 있으며 기존 적용·검토 이력과 별도 게시 CI 관측은 위 검증 상태 절을 참조합니다. 실계정은 미검증입니다.

## 내부 공통 체결 변환

[CommonExecutionSnapshot](src/main/java/io/github/lmj/tradeledger/application/model/CommonExecutionSnapshot.java)은 불변 record이며 `fromUpbit(accountAlias, snapshot)`와 `fromBinance(snapshot)`·`fromBinance(snapshot, scope)` 정적 메서드로 기존 스냅샷만 변환합니다. 순수 변환 자체에는 별도 서비스·어댑터 인터페이스·의존성이나 HTTP·환경변수·파일 접근이 없습니다. 원본 체결 목록을 한 번 순회해 변환하며 추가 조회를 하지 않습니다. 업비트 단건 조회와 바이낸스 최근·필터 체결 조회는 이 변환과 [CommonExecutionResponse](src/main/java/io/github/lmj/tradeledger/mcp/CommonExecutionResponse.java)를 사용합니다. 성능 향상 수치는 측정하거나 주장하지 않습니다.

각 체결에는 서비스 ID, 비밀정보가 아닌 계정 별칭, 원본 시장/symbol, 체결 ID, 주문 ID, `BUY`/`SELL`, 가격·수량·체결 금액 문자열과 `Instant`를 담습니다. 업비트 `bid`/`ask`는 각각 `BUY`/`SELL`, 바이낸스 `isBuyer`는 true일 때 `BUY`, false일 때 `SELL`로 옮깁니다. 금액 문자열의 고정밀 값·후행 0과 원본 종목을 변경하지 않고, 바이낸스 long ID는 정확한 십진 문자열로 보존합니다. 체결 ID의 구조적 키는 `(serviceId, accountAlias, symbol, executionId)`이며 다른 서비스·별칭·종목의 같은 ID를 합치지 않습니다. 별칭은 기존 바이낸스 도구와 같은 최대 256 UTF-16 코드 단위·공백만인 값 거부·ISO 제어문자 거부·올바른 surrogate 쌍 정책을 적용하며 유효한 값은 그대로 유지합니다.

`fees`는 체결과 별도 목록입니다. 업비트 `paidFee`는 `ORDER` 범위와 주문 ID로 정확히 한 번 기록하며 통화는 `Optional.empty()`(미확인)입니다. 여러 체결에 배분하지 않고 무체결 주문의 수수료도 보존합니다. 바이낸스는 각 체결의 commission을 `FILL` 범위와 체결 ID로 기록하고 `commissionAsset` 원문을 통화로 유지합니다. 종목으로 통화를 추정하거나 서로 다른 수수료 자산을 합산·환산하지 않습니다. 주문 상태로 체결을 걸러내지 않으므로 체결이 있는 `cancel`과 무체결 주문도 변환합니다. 원본 주문 상태 등 서비스별 정보는 기존 스냅샷에 남습니다. 완결 `Trade` 생성, 매수/매도 매칭과 손익 계산은 수행하지 않습니다.

조회 범위 `SINGLE_ORDER`는 주문 ID가 있고 `possiblyTruncated`는 해당 없음(`Optional.empty()`)입니다. `RECENT_SYMBOL`과 `FILTERED_SYMBOL`은 단일 주문 ID가 없으며 원본 바이낸스 `possiblyTruncated` true/false를 그대로 담습니다. `fromBinance(snapshot)`은 기존 `RECENT_SYMBOL`을 유지하고 `fromBinance(snapshot, scope)`는 두 바이낸스 범위만 허용합니다. null 또는 `SINGLE_ORDER` 범위는 기존 안전한 공통 모델 오류로 거부합니다. 모델은 어댑터 요청 클래스에 의존하지 않습니다. **어느 범위도 전체 계정 내역 수집 완료를 표현하지 않습니다.** 빈 결과·false 역시 완료 보장이 아닙니다.

변환은 누락·잘못된 별칭·알 수 없는 업비트 방향·부모 주문과 체결의 시장/방향 불일치·부모 symbol과 체결 symbol 불일치·중복 키를 부분 결과 없이 거부합니다. 오류는 입력값·원인·suppressed 예외가 없는 고정 메시지 `Invalid common execution snapshot`입니다. 목록은 빈 목록부터 최대 1000건까지 허용하며 결과 체결·수수료 목록은 방어 복사하고 변경을 막습니다.

[CommonExecutionSnapshotTest](src/test/java/io/github/lmj/tradeledger/application/model/CommonExecutionSnapshotTest.java)는 합성 입력 필드 대응, 큰 ID·소수 정밀도·UTC 시각, 주문/체결 수수료, 취소·무체결, 키·입력 경계, 불변성과 조회 범위 회귀 검사를 포함합니다. 이번 공통 필터 변경에는 범위 지정 오버로드·원본 보존·범위 오류 거부 회귀 테스트를 추가했으며 테스트 실행은 대기입니다. 과거 실패 기록이나 게시 CI 관측을 이번 공통 필터 후보의 통과 근거로 사용하지 않습니다. 실계정 원본 대조는 남아 있습니다.

## 현재 Trade로 표현할 수 있는 범위

[Trade](src/main/java/io/github/lmj/tradeledger/domain/model/Trade.java)는 수량 하나, 진입·청산 단가 하나씩, 동일 통화의 총수수료와 진입·청산 시각을 가진 완결 거래입니다. 개별 체결을 완결 거래로 간주하거나 미청산 거래에 임의 청산값을 넣을 수 없습니다. 분할 체결·부분 청산을 묶는 규칙, 원가 배분 및 포지션 매칭은 구현되어 있지 않습니다. 다른 통화로 낸 수수료도 통화 이름만 바꾸어 입력할 수 없고, 환율·기준 시각·출처를 정한 환산 기능은 없습니다. 따라서 원본 체결이나 서비스 파일을 이 형식으로 임의 변환해서는 안 됩니다. `SHORT` 값 지원이 특정 서비스의 공매도·파생상품 연동을 의미하지도 않습니다.

## 서비스별 상태와 다음 확인 자료

업비트의 단건 주문·종료 주문 요약과 바이낸스의 단일 symbol 최근 체결은 어댑터·MCP 연결이 현재 소스에 반영되어 있습니다. 기존 적용·검토 기록과 게시 CI 관측의 범위는 위 검증 상태 절을 참조합니다. 공식 규격 근거, 구현, 모의 테스트, 실계정 대조는 서로 다른 상태입니다. 토스증권은 2026-11-13 조사에서 **조회 API 존재 근거 확보·상세 계약 일부 미확인·어댑터 미구현·실계정 미검증** 상태이며 지원 완료를 뜻하지 않습니다.

| 서비스 | 공식 방식 확인 | 어댑터 구현 | 서비스 모의 검증 | 실제 연동 검증 | 다음 확인에 필요한 공식 문서·비식별 파일 예시 |
| --- | --- | --- | --- | --- | --- |
| 업비트 | 단건·종료 주문 조회·인증·호출 제한 규격 제공. 이번 문서 작업에서 최신 외부 문서 재확인 미수행 | 두 조회 경로와 MCP 연결 반영. 매수·매도 매칭·실현손익 미구현 | 어댑터·등록 MCP 핸들러 모의 테스트 존재. 적용·검토 이력은 위 기록 참조, 이번 공통 필터 후보의 결과와 구분 | 미검증 | 최신 공식 주문조회 권한·default 공유 한도 확인, 조회 전용 실계정 원본과 UUID·종료 주문 요약·체결·주문 수수료 대조. 페이지 규격과 수수료 통화·시간대가 보존된 비식별 분할 체결 예시 |
| 바이낸스 | 기존 현물 계정 체결 규격·공식 문서 참조 존재. 이번 문서 작업에서 최신 규격 재확인 미수행 | 단일 symbol의 거래소별·공통 체결 MCP 응답, 문자열 ID·금액, 지연 생성과 종료 연결 반영 | 두 등록 핸들러의 입력·단일 전송·안전한 오류와 공통 응답 회귀 테스트 존재. 적용·검토 이력은 위 기록 참조 | 미검증 | 현물 규격·조회 전용 권한·서명·HTTP 오류 계약을 모의 결과와 대조하고 MCP 연결 검토. 파생상품·파일 내보내기 방식은 별도 확인, 수수료 자산·시간대·부분 청산 예시 필요 |
| 토스증권 | 2026-11-13 공식 검색 색인에서 주문 목록·상세 조회 API 존재 근거 확보. 상세 계약 일부·이용 자격·조회 전용 권한 분리·내보내기 규격 미확인 | 어댑터·MCP 연결 미구현 | 미수행 | 실계정 미검증 | 아래 공식 조회 근거와 미확인 계약 표 참조. 국내·해외 주문 요약 및 실제 개별 체결, 실제 수수료·세금의 통화·시각 의미가 보존된 비식별 표본 필요 |

### 토스증권 공식 조회 근거와 확보 한계

**조사·확인일: 2026-11-13 (UTC).** 공개 웹 검색 도구의 공식 도메인 검색 색인과 공식 URL 직접 열기를 구분해 기록합니다. 검색 색인은 과거에 수집된 안내일 수 있으므로 현재 상세 규격 원문을 확보하거나 실제 API를 호출한 것과 같지 않습니다.

| 공식 출처 | 확인일 | 확보 방식 | 확인한 사실 또는 조회 한계 |
| --- | --- | --- | --- |
| [Open API 가이드](https://developers.tossinvest.com/docs) | 2026-11-13 | 공식 URL의 검색 색인 | OAuth 2.0 Client Credentials 인증, 계좌·주문 요청의 `X-Tossinvest-Account` 계좌 식별 헤더 안내, `GET /api/v1/orders` 주문 목록(대기중/종료) 및 `GET /api/v1/orders/{orderId}` 상세 조회 안내를 확인했습니다. 조회 API 존재의 근거이며 상세 요청·응답 계약 확보나 이용 자격·권한 확보의 증거는 아닙니다. |
| [Market Data](https://developers.tossinvest.com/docs/market-data) | 2026-11-13 | 공식 URL의 검색 색인 | `/api/v1/trades`는 계좌 정보 없이 종목별 최근 시장 체결을 조회하는 시세 API로 안내됩니다. 개인 계좌 주문·체결 내역 조회가 아니며 개인 거래 저널의 근거로 사용하지 않습니다. |
| [Open API 가이드](https://developers.tossinvest.com/docs), [Market Data](https://developers.tossinvest.com/docs/market-data) | 2026-11-13 | 각 공식 URL 직접 열기 | JavaScript 렌더링·로딩 안내와 `/llms.txt` 링크만 확보했습니다. 검색 색인에 나온 상세 본문을 직접 조회로 확보하지 못했습니다. |
| [Order History](https://developers.tossinvest.com/docs/order-history) | 2026-11-13 | 공식 URL 직접 열기 | JavaScript 렌더링·로딩 안내만 반환되어 주문 조회의 상세 스키마·페이지 동작은 확보하지 못했습니다. |
| [문서 텍스트 경로](https://developers.tossinvest.com/llms.txt) | 2026-11-13 | 공식 URL 직접 열기 | 조회 도구가 접근 불가 오류를 반환해 본문을 확보하지 못했습니다. 자료 미확보이며 API 부재나 서비스 장애 판정이 아닙니다. |

인증 방식과 계좌 헤더 이름을 검색 색인에서 확인한 것만으로 일반 이용자의 신청·승인 조건, 지원 계좌·시장 또는 **주문·자금 이동 권한과 조회 전용 권한의 분리 가능성**을 확인한 것은 아닙니다. 모두 공식 이용 안내와 권한 계약의 추가 확인이 필요합니다. 인증 토큰·클라이언트 비밀값·실제 계좌 식별정보는 MCP 인자, 표시용 `accountAlias`, 로그, README 예시나 표본 파일에 넣지 않습니다. 이번 문서 변경에는 토스증권 인증·토큰 발급 흐름을 구현하거나 실행하는 예시가 없습니다.

### 토스증권 다음 구현에 필요한 계약

다음 항목은 **확보해야 할 자료**이며 추정한 필드명이나 구현된 동작이 아닙니다. 검색 색인의 기능 목록만으로 주문 응답에 개별 체결·실제 비용이 포함된다고 가정하지 않습니다.

| 확인 대상 | 필요한 공식 계약·비식별 표본 |
| --- | --- |
| 요청·응답 스키마 | 주문 목록과 상세 각각의 경로·인자 위치, 필수/선택·기본값·자료형·허용값, 국내/해외·시장·종목·상태 필터, 응답 봉투·배열·nullable 필드와 오류 구조. 대기·부분 체결·종료·취소·무체결 주문 표본으로 주문 요약과 실제 개별 체결 제공 여부를 구분해야 합니다. |
| 페이지·기간 제한 | 페이지/커서 유무·유효 범위·최대 크기·다음 페이지 표식·종료 조건, 정렬과 같은 시각의 순서, 조회 가능 과거 범위·최대 기간·시작/끝 경계 포함 여부·기준 시간대. 빈 페이지와 마지막 페이지, 페이지 사이 중복·누락 및 조회 중 갱신의 의미가 필요합니다. 짧거나 빈 결과를 전체 내역 완료로 단정하지 않습니다. |
| 주문·체결 식별자 | 원본 주문 ID와 개별 체결 ID의 자료형·안정성·계좌/시장/종목/거래일별 유일 범위, 정정·취소와 원주문 연결, 재조회 중복 및 정정 체결 처리 규칙. 안정된 체결 ID가 실제 제공되는지 확인하고 기존 `(serviceId, accountAlias, symbol, executionId)` 키로 충분한지 판단해야 합니다. ID를 평균가·수량·시각으로 만들어내지 않습니다. |
| 가격·수량·금액 | 개별 체결가·수량·금액과 주문 누적 수량·평균 체결가를 구분하는 필드 정의, 십진 정밀도·단위·거래 통화. 주문 요약과 평균 체결가는 요약으로 보존하며 개별 체결이나 완결 `Trade`로 변환하지 않습니다. |
| 실제 수수료·세금 | 요율·예상 비용 안내와 실제 부과액의 구분, 수수료·세금별 금액·통화·부호·반올림·면제/환급, 주문/개별 체결/정산 중 귀속 범위와 대상 식별자, 확정·정정 시점. 국내·해외 및 거래 통화와 다른 비용 통화 표본이 필요합니다. 통화를 종목에서 추정하거나 비용을 임의 배분·합산·환산하지 않습니다. 기존 공통 `fees`에 세금을 수수료로 섞어 넣지 않고 모델 적합성을 먼저 판단합니다. |
| 시각 의미 | 주문 접수·체결·취소·응답 생성·수수료/세금 부과·정산 시각의 차이, 거래일과 실제 발생 시각의 구분, 포맷·정밀도·시간대·UTC 오프셋·해외 일광절약시간 처리. 날짜나 오프셋 없는 시각을 임의의 `Instant`로 만들지 않습니다. |
| 이용 자격·권한·호출 제약 | 공식 신청·승인 조건과 지원 계좌·시장, 조회 전용 권한 분리 가능성, 토큰 유효기간·만료/권한 오류 계약, 주문 조회에 적용되는 호출 제한 그룹·공유 범위·응답 헤더·429 처리 규칙. 페이지 수와 토큰 재발급을 포함한 호출 예산은 미확인이며 자동 페이지 반복·자동 토큰 재발급의 호출량·성능을 보장하지 않습니다. |

### 규격 확보 후 구현 판단 순서

현재 구조는 **MCP 입력 검증 → 지연 클라이언트 → 고정 GET → 서비스별 스냅샷 → 순수 공통 변환·응답**입니다. `ObjectProvider`와 `@Lazy`로 첫 조회 전 클라이언트 생성을 늦추고, 호스트·URL을 MCP 입력으로 받지 않습니다. 토스증권에도 이 경계를 기준으로 삼되 기존 서비스의 필드·1000건 제한·수수료 범위·단일 symbol 조회 범위를 그대로 적용할 수 있다고 가정하지 않습니다.

공식 상세 규격과 비식별 표본을 확보한 다음, 우선 작은 순수 요청·스냅샷 모델과 매퍼로 원본 의미를 손실 없이 담을 수 있는지 판단합니다. 환경변수·인증·HTTP 없이 누락 필드, 정밀도, ID 범위, 부분 체결·취소, 비용 통화와 시각 의미를 검증할 수 있어야 합니다. 주문 요약만 제공된다면 요약으로 남기고 평균 체결가로 가상의 체결을 만들지 않습니다. 실제 개별 체결과 안정된 식별자를 확보한 경우에만 `CommonExecutionSnapshot`의 범위·비용 모델과 맞는지 검토하며, 완결 `Trade`·매수/매도 매칭·손익으로의 자동 변환은 계획하지 않습니다.

순수 모델의 적합성과 이용 자격·조회 권한 계약을 확인한 뒤에야 고정 조회 경로·지연 클라이언트·MCP 연결의 별도 구현을 판단합니다. 기존 Java 시작 옵션, 진단 로깅 거부, 비밀정보 비노출, 응답 크기·시간 제한, 안전한 오류와 자원 종료 경계를 보존해야 합니다. 기존 조회 도구의 단일 전송 원칙과 자동 재시도·리다이렉트 금지는 그대로이며, 토스증권의 페이지 반복·자동 기간 분할·자동 토큰 재발급은 이번 작업에 포함하지 않습니다. 이러한 추가 동작은 공식 제한과 호출 예산을 확보한 뒤 별도로 검토할 사항이고 성능 개선 수치는 측정하거나 주장하지 않습니다.

서비스별로 다음 단계를 구분해 진행해야 합니다.

1. **공식 방식·계약 확인:** 공식 제공 채널과 문서의 버전·지원 대상·이용 조건을 확인합니다. 토스증권은 위 검색 색인의 API 존재 근거를 출발점으로 상세 계약과 조회 전용 권한 분리 가능성을 추가 확인합니다. 파일 방식은 제공 여부부터 확인하고 실제 헤더·인코딩·구분자·통화·파일 시간대와 오프셋 유무를 확보합니다.
2. **모의 검증:** 공식 상세 규격과 비식별 표본을 확보한 뒤 순수 모델·매퍼부터 검증하고 어댑터 연결 여부를 판단합니다. 페이지 지원이 확인되고 구현 범위에 포함되는 경우 경계 중복·누락을 검증하며, 호출 제한 오류, 부분 체결·미청산 거래, 다른 통화 비용, 시각 해석도 대조합니다. 현재 CSV·계산 테스트는 프로젝트 자체 형식에 대한 것이며 토스증권 어댑터의 모의 검증 근거가 아닙니다.
3. **실제 연동 검증:** 공식 방식·조회 전용 접근 가능성과 모의 검증을 확인한 후 별도로 승인된 환경에서 기간별 원본 내역과 주문·체결 수, 실제 수수료·세금을 대조해야 합니다. 완결 거래·손익은 매칭·원가 배분 규칙을 별도로 확보한 경우에만 대조할 수 있으며 현재 조회 스냅샷으로 추정하지 않습니다. 모의 검증 통과만으로 실제 연동 완료를 선언할 수 없습니다. 현재 세 서비스 모두 이 단계는 미검증입니다.

다음 확인용 파일은 원본의 열 이름·형식·통화·시간대 의미를 유지하면서 개인·계좌 식별정보를 제거하고 거래 식별자를 일관된 가명으로 바꾼 예시가 필요합니다. 인증정보, API 키·시크릿, 토큰 등 비밀값은 문서나 표본 파일에 포함하지 않습니다. 이 문서는 공식 자료나 실제 연동 검증을 대체하지 않습니다.
