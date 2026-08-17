package io.github.lmj.tradeledger.config;

import java.nio.file.Path;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the allowlisted root directory containing readable trade journals.
 */
@ConfigurationProperties("trade-ledger.journal")
public record TradeJournalProperties(Path root) {

	public TradeJournalProperties {
		Objects.requireNonNull(root, "journal root must not be null");
		root = root.toAbsolutePath().normalize();
	}
}
