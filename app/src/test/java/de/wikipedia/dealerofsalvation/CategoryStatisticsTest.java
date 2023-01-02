package de.wikipedia.dealerofsalvation;

import static org.junit.Assert.*;

import org.junit.Test;

public class CategoryStatisticsTest {

	@Test
	public void testBuildSummary1() {
		CategoryStatistics stats = new CategoryStatistics("Beispielkategorie");
		stats.setOldCount(119);
		stats.setCountAfterRemove(115);
		for (int i = 0; i < 8; i++) {
			stats.entryAdded();
		}
		assertEquals("Bot: Liste aktualisiert, aktuell 123 Artikel (-4/+8)",
				stats.buildSummary(false, 123));
	}

	@Test
	public void testBuildSummary2() {
		CategoryStatistics stats = new CategoryStatistics("Beispielkategorie");
		stats.setOldCount(4563);
		stats.setCountAfterRemove(4559);
		for (int i = 0; i < 8; i++) {
			stats.entryAdded();
		}
		assertEquals(
				"Bot: Liste aktualisiert, aktuell Teilliste: 123 Artikel, Gesamtliste: 4567 Artikel (-4/+8)",
				stats.buildSummary(true, 123));
	}
}
