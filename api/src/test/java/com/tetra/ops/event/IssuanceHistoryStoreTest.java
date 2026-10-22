package com.tetra.ops.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssuanceHistoryStoreTest {

	@Test
	void selloutRateIsFourDigitsAndZeroWhenNoStock() {
		assertThat(IssuanceHistoryStore.selloutRate(1L, 3, 8)).isEqualByComparingTo("0.3750");
		assertThat(IssuanceHistoryStore.selloutRate(1L, 1, 3)).isEqualByComparingTo("0.3333");
		assertThat(IssuanceHistoryStore.selloutRate(1L, 2, 3)).isEqualByComparingTo("0.6667");
		assertThat(IssuanceHistoryStore.selloutRate(1L, 0, 0)).isEqualByComparingTo("0");
		assertThat(IssuanceHistoryStore.selloutRate(1L, 8, 8)).isEqualByComparingTo("1");
		// 재고보다 성공이 많으면 CHECK(0~1)에 걸리지 않게 1
		assertThat(IssuanceHistoryStore.selloutRate(1L, 9, 8)).isEqualByComparingTo("1");
	}

	@Test
	void csvFieldQuotesOnlyWhenNeeded() {
		assertThat(IssuanceHistoryStore.csvField("user-1")).isEqualTo("user-1");
		assertThat(IssuanceHistoryStore.csvField("a,b")).isEqualTo("\"a,b\"");
		assertThat(IssuanceHistoryStore.csvField("a\"b")).isEqualTo("\"a\"\"b\"");
		assertThat(IssuanceHistoryStore.csvField("a\nb")).isEqualTo("\"a\nb\"");
	}

}
