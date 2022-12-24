package de.wikipedia.dealerofsalvation;

import static java.lang.Character.toLowerCase;
import static java.lang.Character.toUpperCase;
import static java.util.regex.Pattern.DOTALL;

import java.beans.XMLDecoder;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MaintenanceCategory {

	private static final String CONFIG_FILE = "cats.xml";

	@SuppressWarnings("unchecked")
	static List<MaintenanceCategory> getCategoriesFromConfigFile() {
		try (FileInputStream in = new FileInputStream(CONFIG_FILE);
				XMLDecoder decoder = new XMLDecoder(in)) {
			return (List<MaintenanceCategory>) decoder.readObject();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private final String name;
	private final boolean split;
	private transient List<Pattern> patterns = new ArrayList<>();

	// Wird zum XML-Deserialisieren aufgerufen.
	public MaintenanceCategory(String name, boolean split,
			List<String> templates) {
		this(name, split, templates.toArray(new String[0]));
	}

	public MaintenanceCategory(String name, boolean split,
			String... templates) {
		this.name = name;
		this.split = split;
		for (String template : templates) {
			patterns.add(toTemplatePattern(template));
			// \s - "A whitespace character"
			patterns.add(toTemplatePattern("Vorlage:\\s*"
					+ toTitleRegexp(template)));
		}
		patterns.add(toCategoryPattern(name));
	}

	private Pattern toTemplatePattern(String template) {
		template = toTitleRegexp(template);
		String regexp = ".*\\{\\{\\s*" + template + "\\b.*\\}\\}.*";
		// Result e. g.: .*\{\{[lL]ückenhaft\b.*\}\}.*
		return Pattern.compile(regexp, DOTALL);
	}

	private Pattern toCategoryPattern(String cat) {
		String regexp = ".*\\[\\[Kategorie:"
				+ toTitleRegexp("Wikipedia:" + name) + "\\]\\].*";
		return Pattern.compile(regexp, DOTALL);
	}

	private String toTitleRegexp(String s) {
		char c = s.charAt(0);
		char c1 = toUpperCase(c);
		char c2 = toLowerCase(c);
		s = "[" + c1 + c2 + "]" + s.substring(1);
		// Wherever there is a whitestring, recognize underscore too
		return s.replace(" ", "[ _]");
	}

	boolean matches(String text) {
		text = stripComments(text);
		for (Pattern pattern : patterns) {
			if (pattern.matcher(text).matches()) {
				return true;
			}
		}
		return false;
	}

	public final String getName() {
		return name;
	}

	private static String stripComments(String text) {
		int i1;
		while ((i1 = text.indexOf("<!--")) > -1) {
			int i2 = text.indexOf("-->", i1);
			String t1 = text.substring(0, i1);
			if (i2 > -1) {
				text = t1 + text.substring(i2 + 3);
			} else {
				text = t1;
			}
		}
		return text;
	}

	public final boolean isSplit() {
		return split;
	}

}
