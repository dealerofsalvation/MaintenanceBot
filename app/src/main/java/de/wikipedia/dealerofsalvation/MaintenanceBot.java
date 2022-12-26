package de.wikipedia.dealerofsalvation;

import static java.util.Calendar.YEAR;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikipedia.Wiki.CategoryMember;
import org.wikipedia.Wiki.Revision;
import org.wikipedia.Wiki.RevisionWalker;

public class MaintenanceBot {

	private static final Logger logger = Logger.getLogger(MaintenanceBot.class
			.getName());

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) {
		try {
			Properties properties = new Properties();
			//logger.log(Level.INFO, "ap:" + new java.io.File("foo").getAbsolutePath());
			try (FileInputStream in = new FileInputStream("mbot.properties")) {
				properties.load(in);
			}
			// TODO Not-Schreiben über Runtime.getRuntime().addShutdownHook
			new MaintenanceBot(properties).run();
		} catch (LoginException | IOException | RuntimeException | Error e) {
			logger.log(Level.SEVERE, "Bot stopped", e);
		}
	}

	private final Wiki wiki;

	private final String tablePrefix;

	private final String user;

	private String password;

	private static final int FIRST_YEAR = 2004;

	private final int currentYear = Calendar.getInstance().get(YEAR);

	int grandTotal;

	/**
	 * Stores the old list contents. They are used to compare old and new text,
	 * and to skip editing in case they are equal. With content size close to
	 * the limit, this might typically avoid a no-effect edit taking half a
	 * minute.
	 */
	private final Map<String, String> listContents = new HashMap<>();

	public MaintenanceBot(Properties properties) {
		wiki = Wiki.newSession("de.wikipedia.org");
		tablePrefix = properties.getProperty("tablePrefix");
		user = properties.getProperty("user");
		password = properties.getProperty("password");
	}

	public void run() throws LoginException, IOException {
		wiki.login(user, password);
		password = null;

		StringBuilder overview = new StringBuilder("{{/Intro}}\n");
		overview.append("{| class=\"wikitable\" style=\"text-align:right\"\n");
		overview.append("|-\n");
		overview.append("! Wartungskategorie !! Artikel gesamt");
		for (int year = FIRST_YEAR; year <= currentYear; year++) {
			overview.append(" !! ");
			overview.append(year);
		}
		overview.append(" !! Ältester Baustein !! Durchschnittsalter (Tage)\n");

		for (MaintenanceCategory category : MaintenanceCategory
				.getCategoriesFromConfigFile()) {
			CategoryStatistics stats = new CategoryStatistics(category);
			// Work map, starting with entries read from last state of wiki page
			Map<String, Revision> entries = readMaintenanceInfo(category);
			stats.setOldCount(entries.size());

			// Map of pages in category, as currently queried from API, and, if
			// MaintenanceCategory.queryTimestamp is set, the
			// timestamp the category was added to the page.
			Map<String, OffsetDateTime> currentEntries = readCurrentEntries(category);

			// Remove old entries from work map:
			entries.keySet().retainAll(currentEntries.keySet());
			stats.setCountAfterRemove(entries.size());
			try {
				// Add new entries to work map:
				for (Entry<String, OffsetDateTime> entry : currentEntries.entrySet()) {
					String title = entry.getKey();
					if (!entries.containsKey(title)) {
						// Timestamp the article was added according to API
						OffsetDateTime timestamp = entry.getValue();
						try {
							Revision revision = queryFirstRevisionWithTemplate(
									category, title, timestamp);
							entries.put(title, revision);
							stats.entryAdded();
						} catch (NoMaintenanceTemplateFoundException e) {
							logger.warning(e.getMessage());
						}
					}
				}
				for (Revision revision : entries.values()) {
					stats.analyze(revision);
				}
				writeMaintenanceInfo(category, entries, stats, overview);
				grandTotal += stats.getNewCount();
			} catch (IOException e) {
				throw new RuntimeException(e);
				// TODO entries in Datei retten. Kann man shutdownRequest darauf
				// aufbauend implementieren?
			}
		}
		overview.append("|}\n");
		wiki.edit(tablePrefix, overview.toString(),
				"Bot: Übersicht aktualisiert, " + grandTotal
						+ " Artikel in allen Listen.");
	}

	private Map<String, OffsetDateTime> readCurrentEntries(
			MaintenanceCategory category) throws IOException {
		String name = category.getName();
		List<CategoryMember> members = wiki.getCategoryMembers("Wikipedia:" 
				+ name, 0);
		Map<String, OffsetDateTime> result = new HashMap<>();
		for (CategoryMember member : members) {
			result.put(member.getTitle(), member.getTimestamp());
		}
		return result;
	}

	private void writeMaintenanceInfo(MaintenanceCategory category,
			Map<String, Revision> entries, CategoryStatistics stats,
			StringBuilder overview) throws LoginException, IOException {
		String catName = category.getName();
		String listName = tablePrefix + "/" + catName;
		boolean split = category.isSplit();
		
		stats.writeOverviewEntryPart1(overview, split);
		
		SortedMap<Integer, Map<String, Revision>> entriesByYear = null;
		if (split) {
			entriesByYear = splitEntriesByYear(entries);
		}
		for (int year = FIRST_YEAR; year <= currentYear; year++) {
			overview.append(" || ");
			if (null != entriesByYear) {
				Map<String, Revision> subEntries = entriesByYear.get(year);
				if (subEntries.size() > 0) {
					writeOverviewSubentry(overview, subEntries, year, category);
				}
				String listNameSplit = listName + "/" + year;
				writeMaintenanceInfo(listNameSplit, subEntries, split, stats);
			}
		}
		if (!split) {
			writeMaintenanceInfo(listName, entries, split, stats);
		}
		stats.writeOverviewEntryPart2(overview);
	}

	private void writeOverviewSubentry(StringBuilder overview,
			Map<String, Revision> entries, Integer year,
			MaintenanceCategory category) {
		overview.append("[[/");
		overview.append(category.getName());
		overview.append("/");
		overview.append(year);
		overview.append("|");
		overview.append(entries.size());
		overview.append("]]");
	}

	private SortedMap<Integer, Map<String, Revision>> splitEntriesByYear(
			Map<String, Revision> entries) {
		// SortedMap gets pages edited in correct order:
		SortedMap<Integer, Map<String, Revision>> entriesByYear = new TreeMap<>();
		for (int year = FIRST_YEAR; year <= currentYear; year++) {
			entriesByYear.put(year, new HashMap<String, Revision>());
		}
		for (Entry<String, Revision> entry : entries.entrySet()) {
			String title = entry.getKey();
			Revision revision = entry.getValue();
			if (null == revision) {
				logger.warning("null revision. title=" + title);
			} else {
				int year = revision.getTimestamp().getYear();
				entriesByYear.get(year).put(title, revision);
			}
		}
		return entriesByYear;
	}

	private void writeMaintenanceInfo(String listName,
			Map<String, Revision> entries, boolean split,
			CategoryStatistics stats) throws LoginException, IOException {
		String text = buildText(entries, split);
		String oldContent = listContents.remove(listName);
		// Wenn Seite nicht existiert und keine Einträge vorhanden, dann
		// Seite nicht anlegen
		if ((oldContent == null || oldContent.length() == 0)
				&& entries.isEmpty()) {
			return;
		}
		if (!text.equals(oldContent)) {
			int subCount = entries.size();
			String summary = stats.buildSummary(split, subCount);
			wiki.edit(listName, text, summary);
		}
	}

	private String buildText(Map<String, Revision> entries, boolean split) {
		LineFormat format = new LineFormat();
		Set<String> lines = new TreeSet<>();
		for (Entry<String, Revision> entry : entries.entrySet()) {
			format.formatLine(lines, entry, split);
		}
		StringBuilder b = new StringBuilder();
		b.append(split ? "{{../../Intro}}\n" : "{{../Intro}}\n");
		b.append("{| class=\"wikitable sortable\"\n");
		b.append("! Wartung seit !! Titel\n");
		for (String line : lines) {
			b.append(line);
		}
		b.append("|}");
		return b.toString();
	}

	static class LineFormat {
		private NumberFormat revisionFormat;

		{
			revisionFormat = DecimalFormat.getNumberInstance();
			revisionFormat.setGroupingUsed(false);
			revisionFormat.setMinimumIntegerDigits(9);
		}

		private void formatLine(Set<String> lines,
				Entry<String, Revision> entry, boolean split) {
			String title = entry.getKey();
			Revision revision = entry.getValue();
			if (revision == null) {
				logger.warning("null revision: " + title);
			} else {
				LocalDate date = revision.getTimestamp().toLocalDate();
				Long revid = revision.getID();
				StringBuilder b = new StringBuilder();
				b.append("{{../");
				if (split) {
					b.append("../");
				}
				b.append("z|");
				b.append(revisionFormat.format(revid));
				b.append("|");
				b.append(date);
				b.append("|");
				b.append(title);
				b.append("}}\n");
				String line = b.toString();
				lines.add(line);
			}
		}
	}

	private Map<String, Revision> readMaintenanceInfo(
			MaintenanceCategory category) throws IOException {
		Map<String, Revision> result = new HashMap<>();
		String catName = category.getName();
		if (category.isSplit()) {
			for (int year = FIRST_YEAR; year <= currentYear; year++) {
				readMaintenanceInfo(tablePrefix + "/" + catName + "/" + year,
						result);
			}
		} else {
			readMaintenanceInfo(tablePrefix + "/" + catName, result);
		}
		return result;
	}

	private void readMaintenanceInfo(String pageName,
			Map<String, Revision> result) throws IOException {
		String text;
		try {
			text = wiki.getPageText(Collections.singletonList(pageName)).get(0);
			if (null == text) {
				logger.log(Level.WARNING, "Null page text for " + pageName);
				text = "";
			}
		} catch (FileNotFoundException e1) {
			text = "";
		}
		listContents.put(pageName, text);
		String[] lines = text.split("\n");
		for (String line : lines) {
			if ((line.startsWith("{{../z|") || line.startsWith("{{../../z|"))
					&& line.endsWith("}}")) {
				String[] tokens = line.split("\\|");
				long revID;
				LocalDate date;
				String title;
				try {
					revID = Long.parseLong(tokens[1]);
					date = LocalDate.parse(tokens[2].substring(0,10)); // sometimes people add notes after the date
					title = tokens[3].substring(0, tokens[3].length() - 2)
							.replaceAll("_", " ");
				} catch (DateTimeParseException | NumberFormatException
						| ArrayIndexOutOfBoundsException e) {
					logger.warning("parse error at line: " + line);
					e.printStackTrace();
					continue;
				}
				Revision revision = wiki.new Revision(revID, OffsetDateTime.of(date, LocalTime.MIDNIGHT, ZoneOffset.UTC), null);
				result.put(title, revision);
			}
		}
	}

	private Revision queryFirstRevisionWithTemplate(
			final MaintenanceCategory cat, final String title, OffsetDateTime rvStart)
			throws IOException, NoMaintenanceTemplateFoundException {
		// for each handler invocation, the oldest revision containing the
		// maintenance
		// template
		Revision revision1 = null;
		String text1 = null;

		// for each handler invocation, the oldest revision by the same
		// author as the
		// nearest older revision than revision 1.
		Revision revision2 = null;

		try (RevisionWalker walker = wiki.new RevisionWalker(title, rvStart)) {
			while (walker.next()) {
				String text = walker.text();
				Revision revision = walker.revision();
				if (cat.matches(text)) {
					// Check if contributions between revision1 and
					// revision2
					// where reverted.
					if (null != revision2 && !text.equals(text1)) {
						// not reverted
						break;
					} else {
						// current revision contains category
						revision1 = revision;
						text1 = text;
						revision2 = null;
					}
				} else if (null == revision1) {
					throw new NoMaintenanceTemplateFoundException(cat, title);
				} else if (null == revision2) {
					// remember this revision for revert check
					revision2 = revision;
				} else {
					// perform revert check
					String user2 = revision2.getUser();
					String user = revision.getUser();
					if (user.equals(user2)) {
						// remember another revision for revert check
						revision2 = revision;
					} else {
						break;
					}
				}
			}
		}
		return revision1;
	}

	/**
	 * Newest revision doesn't contain category. Possible causes:
	 * <ul>
	 * <li>The maintenance template is included indirectly via some other
	 * template
	 * <li>The template is substituted
	 * <li>someone has just included the maintenance category, but not the
	 * template
	 * <li>this algorithm needs to be improved
	 * <li>the page was edited and the category was removed after querying page
	 * history.
	 */
	static class NoMaintenanceTemplateFoundException extends Exception {

		public NoMaintenanceTemplateFoundException(MaintenanceCategory cat,
				String title) {
			super(
					"Maintenance template not found in latest article revision. cat="
					+ cat.getName() + ", title=" + title);
		}

	}
}
