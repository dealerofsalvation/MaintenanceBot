package de.wikipedia.dealerofsalvation;

import java.time.ZoneId;
import java.util.Calendar;

import org.wikipedia.Wiki.Revision;

class CategoryStatistics {

	private final long now = System.currentTimeMillis();
	private int removed;
	private int newCount;
	private int added;
	private int oldCount;
	private String catName;
	private Calendar oldest;
	private long sumOfAges;

	CategoryStatistics(MaintenanceCategory category) {
		this(category.getName());
	}

	CategoryStatistics(String catName) {
		this.catName = catName;
	}

	void entryAdded() {
		newCount++;
		added++;
	}

	void analyze(Revision revision) {
		Calendar timestamp = revision.getTimestamp();
		if (null == oldest || timestamp.before(oldest)) {
			oldest = timestamp;
		}
		long age = (now - timestamp.getTimeInMillis()) / 24L / 3600L / 1000L;
		sumOfAges += age;
	}

	void setOldCount(int size) {
		oldCount = size;
	}

	void setCountAfterRemove(int size) {
		newCount = size;
		removed = oldCount - newCount;
	}

	final String buildSummary(boolean split, int subCount) {
		StringBuilder b = new StringBuilder();
		b.append("Bot: ");
		b.append("Liste aktualisiert");
		b.append(", aktuell ");
		if (split) {
			b.append("Teilliste: ");
			b.append(subCount);
			b.append(" Artikel, Gesamtliste: ");
			b.append(newCount);
		} else {
			b.append(subCount);
		}
		b.append(" Artikel (-");
		b.append(removed);
		b.append("/+");
		b.append(added);
		b.append(")");
		return b.toString();
	}

	void writeOverviewEntryPart1(StringBuilder overview, boolean split) {

		overview.append("|-\n");
		overview.append("! ");
		overview.append(catName);
		overview.append("\n");
		overview.append("| ");
		if (!split) {
			overview.append("[[/");
			overview.append(catName);
			overview.append("|");
		}
		overview.append(newCount);
		if (!split) {
			overview.append("]]");
		}

	}

	void writeOverviewEntryPart2(StringBuilder overview) {
		
		Calendar oldest = this.oldest;
		if (null == oldest) {
			throw new IllegalStateException("please call 'analyze' first");
		}
		overview.append(" || ");
		overview.append(oldest.getTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
		overview.append(" || ");
		overview.append(sumOfAges / newCount);
		overview.append("\n");
		
	}
	int getNewCount() {
		return newCount;
	}

}
