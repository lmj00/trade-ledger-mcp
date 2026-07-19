package io.github.lmj.tradeledger.adapter.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import io.github.lmj.tradeledger.domain.model.Direction;
import io.github.lmj.tradeledger.domain.model.Market;
import io.github.lmj.tradeledger.domain.model.Trade;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

class CsvTradeMapperTest {

	private static final String HEADER = String.join(",", CsvTradeSchema.HEADERS);

	private final CsvTradeMapper mapper = new CsvTradeMapper();

	@Test
	void mapsCsvFieldsToTrade() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,crypto,long,0.1,60000,62000,5,usdt,\
				2026-07-01T10:00:00+09:00,2026-07-01T12:00:00+09:00,breakout
				""");

		Trade trade = mapper.map(record, 2);

		assertThat(trade.tradeId()).isEqualTo("trade-001");
		assertThat(trade.symbol().value()).isEqualTo("BTC-USDT");
		assertThat(trade.market()).isEqualTo(Market.CRYPTO);
		assertThat(trade.direction()).isEqualTo(Direction.LONG);
		assertThat(trade.quantity()).isEqualByComparingTo("0.1");
		assertThat(trade.entryPrice().amount()).isEqualByComparingTo("60000");
		assertThat(trade.exitPrice().amount()).isEqualByComparingTo("62000");
		assertThat(trade.fees().amount()).isEqualByComparingTo("5");
		assertThat(trade.entryPrice().currency().value()).isEqualTo("USDT");
		assertThat(trade.openedAt()).isEqualTo(Instant.parse("2026-07-01T01:00:00Z"));
		assertThat(trade.closedAt()).isEqualTo(Instant.parse("2026-07-01T03:00:00Z"));
		assertThat(trade.strategy()).isEqualTo("breakout");
	}

	@Test
	void preservesCommaInsideQuotedStrategy() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,"breakout, volume"
				""");

		Trade trade = mapper.map(record, 2);

		assertThat(trade.strategy()).isEqualTo("breakout, volume");
	}

	@Test
	void rejectsScientificNotationWithFieldAndLineNumber() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,1e-1,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout
				""");

		assertThatThrownBy(() -> mapper.map(record, 2))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(2);
					assertThat(exception.field()).hasValue("quantity");
				});
	}

	@Test
	void rejectsTimestampWithoutOffset() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,0.1,60000,62000,5,USDT,\
				2026-07-01T01:00:00,2026-07-01T03:00:00Z,breakout
				""");

		assertThatThrownBy(() -> mapper.map(record, 2))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(2);
					assertThat(exception.field()).hasValue("opened_at");
				});
	}

	@Test
	void translatesDomainValidationFailureToCsvField() throws IOException {
		CSVRecord record = record("""
				trade-001,BTC-USDT,CRYPTO,LONG,0,60000,62000,5,USDT,\
				2026-07-01T01:00:00Z,2026-07-01T03:00:00Z,breakout
				""");

		assertThatThrownBy(() -> mapper.map(record, 2))
				.isInstanceOfSatisfying(CsvJournalException.class, exception -> {
					assertThat(exception.code()).isEqualTo(CsvJournalErrorCode.INVALID_FIELD);
					assertThat(exception.lineNumber()).hasValue(2);
					assertThat(exception.field()).hasValue("quantity");
					assertThat(exception).hasMessageContaining("positive");
				});
	}

	private static CSVRecord record(String row) throws IOException {
		String csv = HEADER + System.lineSeparator() + row;
		try (CSVParser parser = CSVFormat.DEFAULT.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.setTrim(true)
				.get()
				.parse(new StringReader(csv))) {
			List<CSVRecord> records = parser.getRecords();
			assertThat(records).hasSize(1);
			return records.getFirst();
		}
	}
}
