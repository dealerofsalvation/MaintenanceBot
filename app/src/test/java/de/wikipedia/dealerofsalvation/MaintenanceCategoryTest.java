package de.wikipedia.dealerofsalvation;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public class MaintenanceCategoryTest {

	@Test
	public void testTemplateMatching() {
		assertWartung("Belege fehlen", "asdf\n{{Belege}}\nafsd");
		assertWartung("Belege fehlen", "asdf\n{{Vorlage:Belege}}\nafsd");
		assertWartung("Belege fehlen", "asdf\n{{vorlage:belege}}\nafsd");
		assertWartung("Belege fehlen",
				"asdf\n{{Vorlage: Belege fehlen|Es fehlen Belege}}\nafsd");
		assertWartung("Belege fehlen", "asdf\n{{ Belege}}\nafsd");
		assertWartung("Belege fehlen", "asdf\n{{belege}}\nafsd");
		assertWartung("Belege fehlen", "asdf\n{{Belege fehlen}}\nafsd");
		assertWartung("Belege fehlen", "asdf\n{{Belege_fehlen}}\nafsd");
		assertWartung("Belege fehlen",
				"asdf\n{{Belege fehlen|grund=asdf}}\nafsd");
		assertWartung("Belege fehlen",
				"asdf\n{{Belege fehlen|\ngrund=asdf}}\nafsd");
		assertWartung("Belege fehlen",
				"Blindtext\n[[Kategorie:Wikipedia:Belege fehlen]]\n");
		assertWartung("Belege fehlen",
				"Blindtext\n [[Kategorie:wikipedia:Belege fehlen]] \n");
		assertWartung("Schweizlastig", "asdf\n{{Staatslastig|CH}}\nsdgaga");
		assertWartung("Schweizlastig", "asdf\n{{Staatslastig | CH}}\nsdgaga");
		assertWartung("Schweizlastig", "asdf\n{{Staatslastig|1=CH}}\nsdgaga");
		assertNull(getWartungskategorie("asdf\n{{Belegen}}\nafsd"));
		assertNull(getWartungskategorie("asdf\n{{Belege\nafsd"));
		assertNull(getWartungskategorie("asdf\n<!--\n{{Belege}}\n-->sadfa"));
		assertNull(getWartungskategorie("asdf<!--\n{{Belege}}-->sadfa"));
		assertNull(getWartungskategorie("asdf\n<!--\n{{Belege}}"));
	}

	private static void assertWartung(String kat, String text) {
		assertEquals(kat, getWartungskategorie(text));
	}

	private static String getWartungskategorie(String text) {
		for (MaintenanceCategory cat : getSampleCategories()) {
			if (cat.matches(text)) {
				return cat.getName();
			}
		}
		return null;
	}

	public static List<MaintenanceCategory> getSampleCategories() {
		return asList(

		new MaintenanceCategory("Belege fehlen", true, "Belege", "Quellen",
				"Quelle", "Belege fehlen", "Quellen fehlen"),
				new MaintenanceCategory("Schweizlastig", false,
						"Staatslastig *\\| *CH", "Staatslastig\\|1=CH"));
	}

}
