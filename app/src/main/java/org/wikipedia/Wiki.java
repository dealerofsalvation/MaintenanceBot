/**
 *  @(#)Wiki.java 0.30 05/12/2013
 *  Copyright (C) 2007 - 2013 MER-C and contributors
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 3
 *  of the License, or (at your option) any later version. Additionally
 *  this file is subject to the "Classpath" exception.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

package org.wikipedia;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;
//import java.util.zip.GZIPInputStream;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.CredentialException;
import javax.security.auth.login.CredentialExpiredException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * This is a somewhat sketchy bot framework for editing MediaWiki wikis.
 * Requires JDK 1.6 (6.0) or greater. Uses the
 * <a href="https://www.mediawiki.org/wiki/API:Main_page">MediaWiki API</a> for
 * most operations. It is recommended that the server runs the latest version of
 * MediaWiki (1.20), otherwise some functions may not work.
 * <p>
 * Extended documentation is available
 * <a href="https://code.google.com/p/wiki-java/wiki/ExtendedDocumentation" >
 * here</a>. All wikilinks are relative to the English Wikipedia and all
 * timestamps are in your wiki's time zone.
 * </p>
 * Please file bug reports <a href=
 * "https://en.wikipedia.org/w/index.php?title=User_talk:MER-C&action=edit&section=new"
 * >here</a> (fast) or at the
 * <a href="https://code.google.com/p/wiki-java/issues/list">Google code bug
 * tracker</a> (slow).
 * 
 * @author MER-C and contributors
 * @version 0.30
 */
@SuppressWarnings({ "rawtypes" })
public class Wiki implements Serializable {

	/**
	 * Denotes the main namespace, with no prefix.
	 * 
	 * @since 0.03
	 */
	private static final int MAIN_NAMESPACE = 0;

	// RC OPTIONS

	// REVISION OPTIONS

	private final XMLInputFactory factory = XMLInputFactory.newInstance();

	// the domain of the wiki
	protected String query, apiUrl;
	private boolean wgCapitalLinks = true;

	// user management
	private HashMap<String, String> cookies = new HashMap<String, String>(12);
	private User user;

	// various caches
	private HashMap<String, Integer> namespaces = null;

	private int slowmax = 50;
	private int maxlag = 5;
	private String useragent = "WartungslistenBot@dewiki";
	private boolean markminor = false, markbot = false;
	private boolean resolveredirect = false;

	// serial version
	private static final long serialVersionUID = -8745212681497644126L;

	// time to open a connection
	private static final int CONNECTION_CONNECT_TIMEOUT_MSEC = 30000; // 30
																		// seconds
	// time for the read to take place. (needs to be longer, some connections
	// are slow
	// and the data volume is large!)
	private static final int CONNECTION_READ_TIMEOUT_MSEC = 180000; // 180

	public Wiki(String apiUrl) {
		this.apiUrl = apiUrl + "?maxlag=" + maxlag + "&format=xml&";

		// init variables
		// This is fine as long as you do not have parameters other than domain
		// and scriptpath in constructors and do not do anything else than
		// super(x)!
		// http://stackoverflow.com/questions/3404301/whats-wrong-with-overridable-method-calls-in-constructors
		// TODO: make this more sane.
		// TODO: use new continue format, see
		// https://www.mediawiki.org/wiki/API:Raw_Query_Continue
		log(Level.CONFIG, "<init>", "Using MaintenanceBot");
		query = this.apiUrl + "action=query&rawcontinue&";
		if (resolveredirect)
			query += "redirects&";
	}

	// META STUFF

	/**
	 * Logs in to the wiki. This method is thread-safe. If the specified
	 * username or password is incorrect, the thread blocks for 20 seconds then
	 * throws an exception.
	 * 
	 * @param username
	 *            a username
	 * @param password
	 *            a password (as a char[] due to JPasswordField)
	 * @throws FailedLoginException
	 *             if the login failed due to incorrect username and/or password
	 * @throws IOException
	 *             if a network error occurs
	 * @see #logout
	 */
	private synchronized void login(String username, char[] password)
			throws IOException, FailedLoginException {
		// post login request
		StringBuilder buffer = new StringBuilder(500);
		buffer.append("lgname=");
		buffer.append(URLEncoder.encode(username, "UTF-8"));
		// fetch token
		String response = post(apiUrl + "action=login", buffer.toString(),
				"login");
		String wpLoginToken = parseAttribute(response, "token", 0);
		buffer.append("&lgpassword=");
		buffer.append(URLEncoder.encode(new String(password), "UTF-8"));
		buffer.append("&lgtoken=");
		buffer.append(URLEncoder.encode(wpLoginToken, "UTF-8"));
		String line = post(apiUrl + "action=login", buffer.toString(), "login");
		buffer = null;

		// check for success
		if (line.contains("result=\"Success\"")) {
			user = new User(username);
			boolean apihighlimit = user.isAllowedTo("apihighlimits");
			if (apihighlimit) {
				slowmax = 500;
			}
			log(Level.INFO, "login", "Successfully logged in as " + username
					+ ", highLimit = " + apihighlimit);
		} else {
			log(Level.WARNING, "login", "Failed to log in as " + username);
			try {
				Thread.sleep(2); // to prevent brute force
			} catch (InterruptedException e) {
				// nobody cares
			}
			// test for some common failure reasons
			if (line.contains("WrongPass") || line.contains("WrongPluginPass"))
				throw new FailedLoginException(
						"Login failed: incorrect password.");
			else if (line.contains("NotExists"))
				throw new FailedLoginException(
						"Login failed: user does not exist.");
			throw new FailedLoginException("Login failed: unknown reason."+line);
		}
	}

	// Enables login while using a string password
	public synchronized void login(String username, String password)
			throws IOException, FailedLoginException {
		login(username, password.toCharArray());
	}

	/**
	 *  Fetches edit and other types of tokens.
	 *  @param type one of "csrf", "patrol", "rollback", "userrights", "watch"
	 *  or "login"
	 *  @return the token
	 *  @throws IOException if a network error occurs
	 */
	public String getToken(String type) throws IOException {
		String content = fetch(query + "action=query&meta=tokens&type=" + type, "getToken");
		return parseAttribute(content, type + "token", 0);
	}

	/**
	 * Returns the namespace a page is in. No need to override this to add
	 * custom namespaces, though you may want to define static fields e.g.
	 * <tt>public static final int PORTAL_NAMESPACE = 100;</tt> for the Portal
	 * namespace on the English Wikipedia.
	 * 
	 * @param title
	 *            any valid page name
	 * @return an integer array containing the namespace of <tt>title</tt>
	 * @throws IOException
	 *             if a network error occurs while populating the namespace
	 *             cache
	 * @see #namespaceIdentifier(int)
	 * @since 0.03
	 */
	private int namespace(String title) throws IOException {
		// cache this, as it will be called often
		if (namespaces == null)
			populateNamespaceCache();

		// sanitise
		if (!title.contains(":"))
			return MAIN_NAMESPACE;
		String namespace = title.substring(0, title.indexOf(':'));

		// look up the namespace of the page in the namespace cache
		if (!namespaces.containsKey(namespace))
			return MAIN_NAMESPACE; // For titles like UN:NRV
		else
			return namespaces.get(namespace).intValue();
	}

	/**
	 * For a given namespace denoted as an integer, fetch the corresponding
	 * identification string e.g. <tt>namespaceIdentifier(1)</tt> should return
	 * "Talk" on en.wp. (This does the exact opposite to <tt>namespace()</tt>).
	 * Strings returned are localized.
	 * 
	 * @param namespace
	 *            an integer corresponding to a namespace. If it does not
	 *            correspond to a namespace, we assume you mean the main
	 *            namespace (i.e. return "").
	 * @return the identifier of the namespace
	 * @throws IOException
	 *             if the namespace cache has not been populated, and a network
	 *             error occurs when populating it
	 * @see #namespace(java.lang.String)
	 * @since 0.25
	 */
	private String namespaceIdentifier(int namespace) throws IOException {
		if (namespaces == null)
			populateNamespaceCache();

		// anything we cannot identify is assumed to be in the main namespace
		if (!namespaces.containsValue(namespace))
			return "";
		for (Map.Entry<String, Integer> entry : namespaces.entrySet())
			if (entry.getValue().equals(namespace))
				return entry.getKey();
		return ""; // never reached...
	}

	/**
	 * Populates the namespace cache.
	 * 
	 * @throws IOException
	 *             if a network error occurs.
	 * @since 0.25
	 */
	private void populateNamespaceCache() throws IOException {
		String line = fetch(query + "meta=siteinfo&siprop=namespaces",
				"namespace");
		namespaces = new HashMap<String, Integer>(30);

		// xml form: <ns id="-2" ... >Media</ns> or <ns id="0" ... />
		for (int a = line.indexOf("<ns "); a > 0; a = line.indexOf("<ns ",
				++a)) {
			String ns = parseAttribute(line, "id", a);
			int b = line.indexOf('>', a) + 1;
			int c = line.indexOf('<', b);
			namespaces.put(normalize(decode(line.substring(b, c))),
					new Integer(ns));
		}

		log(Level.INFO, "namespace", "Successfully retrieved namespace list ("
				+ namespaces.size() + " namespaces)");
	}

	/**
	 * Gets the raw wikicode for a page. WARNING: does not support special
	 * pages. Check [[User talk:MER-C/Wiki.java#Special page equivalents]] for
	 * fetching the contents of special pages. Use <tt>getImage()</tt> to fetch
	 * an image.
	 * 
	 * @param title
	 *            the title of the page.
	 * @return the raw wikicode of a page.
	 * @throws UnsupportedOperationException
	 *             if you try to retrieve the text of a Special: or Media: page
	 * @throws FileNotFoundException
	 *             if the page does not exist
	 * @throws IOException
	 *             if a network error occurs
	 * @see #edit
	 */
	public String getPageText(String title) throws IOException {
		// go for it
		String url = query + "&prop=revisions&rvprop=content&titles="
				+ URLEncoder.encode(normalize(title), "UTF-8");
		try (InputStream stream = fetchStream(url, "getPageText")) {
			XMLStreamReader reader = factory.createXMLStreamReader(stream);
			while (reader.hasNext()) {
				reader.next();
				if (reader.isStartElement()) {
					switch (reader.getLocalName()) {
					case "warnings":
						parseWarnings(reader);
						break;
					case "page":
						if (reader.getAttributeValue("", "missing") != null) {
							throw new FileNotFoundException(title);
						}
						break;
					case "rev":
						log(Level.INFO, "getPageText",
								"Successfully retrieved text of " + title);
						return reader.getElementText();
					}
				}
			}
			throw new RuntimeException("rev element not fount in " + url);
		} catch (XMLStreamException e) {
			throw new RuntimeException(e);
		}

	}

	/**
	 * Edits a page by setting its text to the supplied value. This method is
	 * thread safe and blocks for a minimum time as specified by the throttle.
	 * The edit will be marked bot if <tt>isMarkBot() == true</tt> and minor if
	 * <tt>isMarkMinor() == true</tt>.
	 * 
	 * @param text
	 *            the text of the page
	 * @param title
	 *            the title of the page
	 * @param summary
	 *            the edit summary. See [[Help:Edit summary]]. Summaries longer
	 *            than 200 characters are truncated server-side.
	 * @throws IOException
	 *             if a network error occurs
	 * @throws AccountLockedException
	 *             if user is blocked
	 * @throws CredentialException
	 *             if page is protected and we can't edit it
	 * @throws UnsupportedOperationException
	 *             if you try to edit a Special: or a Media: page
	 * @see #getPageText
	 */
	public void edit(String title, String text, String summary)
			throws IOException, LoginException {
		edit(title, text, summary, markminor, markbot, -2);
	}

	/**
	 * Edits a page by setting its text to the supplied value. This method is
	 * thread safe and blocks for a minimum time as specified by the throttle.
	 * 
	 * @param text
	 *            the text of the page
	 * @param title
	 *            the title of the page
	 * @param summary
	 *            the edit summary. See [[Help:Edit summary]]. Summaries longer
	 *            than 200 characters are truncated server-side.
	 * @param minor
	 *            whether the edit should be marked as minor, See [[Help:Minor
	 *            edit]].
	 * @param bot
	 *            whether to mark the edit as a bot edit (ignored if one does
	 *            not have the necessary permissions)
	 * @param section
	 *            the section to edit. Use -1 to specify a new section and -2 to
	 *            disable section editing.
	 * @param basetime
	 *            the timestamp of the revision on which <tt>text</tt> is based,
	 *            used to check for edit conflicts. <tt>null</tt> disables this.
	 * @throws IOException
	 *             if a network error occurs
	 * @throws AccountLockedException
	 *             if user is blocked
	 * @throws CredentialExpiredException
	 *             if cookies have expired
	 * @throws CredentialException
	 *             if page is protected and we can't edit it
	 * @throws UnsupportedOperationException
	 *             if you try to edit a Special: or Media: page
	 * @see #getPageText
	 * @since 0.17
	 */
	private synchronized void edit(String title, String text, String summary,
			boolean minor, boolean bot, int section)
					throws IOException, LoginException {
		// @revised 0.16 to use API edit. No more screenscraping - yay!
		// @revised 0.17 section editing
		// @revised 0.25 optional bot flagging

		// protection and token
		String csrfToken = getToken("csrf");

		// post data
		StringBuilder buffer = new StringBuilder(300000);
		buffer.append("title=");
		buffer.append(URLEncoder.encode(normalize(title), "UTF-8"));
		buffer.append("&text=");
		buffer.append(URLEncoder.encode(text, "UTF-8"));
		buffer.append("&summary=");
		buffer.append(URLEncoder.encode(summary, "UTF-8"));
		buffer.append("&token=");
		buffer.append(URLEncoder.encode(csrfToken, "UTF-8"));
		safePost(apiUrl + "action=edit", buffer.toString(), "edit");
	}

	// USER METHODS

	/**
	 * Gets the user we are currently logged in as. If not logged in, returns
	 * null.
	 * 
	 * @return the current logged in user
	 * @since 0.05
	 */
	public User getCurrentUser() {
		return user;
	}

	// LISTS

	public class CategoryMember {
		private final String title;
		private final Calendar timestamp;

		private CategoryMember(String title, Calendar timestamp) {
			super();
			this.title = title;
			this.timestamp = timestamp;
		}

		public String getTitle() {
			return title;
		}

		public Calendar getTimestamp() {
			return timestamp;
		}

	}

	public CategoryMember[] queryCategoryMembers(String name, int... ns)
			throws IOException {
		StringBuilder url = new StringBuilder(query);
		url.append("list=categorymembers&cmprop=title|timestamp"
				+ "&cmlimit=max&cmtitle=Category:");
		url.append(URLEncoder.encode(normalize(name), "UTF-8"));
		constructNamespaceString(url, "cm", ns);
		ArrayList<CategoryMember> members = new ArrayList<CategoryMember>();
		String next = "";
		do {
			if (!next.isEmpty())
				next = "&cmcontinue=" + URLEncoder.encode(next, "UTF-8");
			String line = fetch(url.toString() + next, "getCategoryMembers");

			// parse cmcontinue if it is there
			if (line.contains("cmcontinue"))
				next = parseAttribute(line, "cmcontinue", 0);
			else
				next = null;

			// xml form: <cm pageid="24958584" ns="3"
			// title="User talk:86.29.138.185" />
			for (int x = line.indexOf("<cm "); x > 0; x = line.indexOf("<cm ",
					++x)) {
				CategoryMember member = new CategoryMember(
						decode(parseAttribute(line, "title", x)),
						timestampToCalendar(
								parseAttribute(line, "timestamp", x)));

				members.add(member);
			}
		} while (next != null);

		int size = members.size();
		log(Level.INFO, "getCategoryMembers",
				"Successfully retrieved contents of Category:" + name + " ("
						+ size + " items)");
		return members.toArray(new CategoryMember[size]);
	}

	// INNER CLASSES

	/**
	 * Subclass for wiki users.
	 * 
	 * @since 0.05
	 */
	private class User implements Cloneable {
		private String username;
		private String[] rights = null; // cache

		/**
		 * Creates a new user object. Does not create a new user on the wiki (we
		 * don't implement this for a very good reason). Shouldn't be called for
		 * anons.
		 * 
		 * @param username
		 *            the username of the user
		 * @since 0.05
		 */
		private User(String username) {
			this.username = username;
		}

		/**
		 * Gets various properties of this user. Groups and rights are cached
		 * for the current logged in user. Returns:
		 * 
		 * <pre>
		 *  {
		 *      "rights"    => { "edit", "read", "block", "email"},   // the stuff the user can do (String[])
		 *  }
		 * </pre>
		 * 
		 * @return (see above)
		 * @throws IOException
		 *             if a network error occurs
		 * @since 0.24
		 */
		public HashMap<String, Object> getUserInfo() throws IOException {
			String info = fetch(
					query + "list=users&usprop=editcount%7Cgroups%7Crights%7Cemailable%7Cblockinfo%7Cgender%7Cregistration&ususers="
							+ URLEncoder.encode(username, "UTF-8"),
					"getUserInfo");
			HashMap<String, Object> ret = new HashMap<String, Object>(10);

			// rights
			ArrayList<String> temp = new ArrayList<String>(50);
			String[] temp2;
			for (int x = info.indexOf("<r>"); x > 0; x = info.indexOf("<r>",
					++x)) {
				int y = info.indexOf("</r>", x);
				temp.add(info.substring(x + 3, y));
			}
			temp2 = temp.toArray(new String[temp.size()]);
			// cache
			if (this.equals(getCurrentUser()))
				rights = temp2;
			ret.put("rights", temp2);
			return ret;
		}

		/**
		 * Returns true if the user is allowed to perform the specified action.
		 * Uses the rights cache. Read [[Special:Listgrouprights]] before using
		 * this!
		 * 
		 * @param right
		 *            a specific action
		 * @return whether the user is allowed to execute it
		 * @since 0.24
		 * @throws IOException
		 *             if a network error occurs
		 */
		private boolean isAllowedTo(String right) throws IOException {
			// We can safely assume the user is allowed to { read, edit, create,
			// writeapi }.
			if (rights == null)
				rights = (String[]) getUserInfo().get("rights");
			for (String r : rights)
				if (r.equals(right))
					return true;
			return false;
		}

		/**
		 * Tests whether this user is equal to another one.
		 * 
		 * @return whether the users are equal
		 * @since 0.08
		 */
		@Override
		public boolean equals(Object x) {
			return x instanceof User && username.equals(((User) x).username);
		}

		/**
		 * Returns a hashcode of this user.
		 * 
		 * @return see above
		 * @since 0.19
		 */
		@Override
		public int hashCode() {
			return username.hashCode() * 2 + 1;
		}
	}

	/**
	 * Represents a contribution and/or a revision to a page.
	 * 
	 * @since 0.17
	 */
	public static class Revision {
		private long revid;
		private Calendar timestamp;
		private String user;

		/**
		 * Constructs a new Revision object.
		 * 
		 * @param revid
		 *            the id of the revision (this is a long since
		 *            {{NUMBEROFEDITS}} on en.wikipedia.org is now (January
		 *            2012) ~25% of <tt>Integer.MAX_VALUE</tt>
		 * @param timestamp
		 *            when this revision was made
		 * @param user
		 *            the user making this revision (may be anonymous, if not
		 *            use <tt>User.getUsername()</tt>)
		 * @since 0.17
		 */
		public Revision(long revid, Calendar timestamp, String user) {
			this.revid = revid;
			this.timestamp = timestamp;
			this.user = user;
		}

		/**
		 * Returns the user or anon who created this revision. You should pass
		 * this (if not an IP) to <tt>getUser(String)</tt> to obtain a User
		 * object. WARNING: returns null if the user was RevisionDeleted.
		 * 
		 * @return the user or anon
		 * @since 0.17
		 */
		public String getUser() {
			return user;
		}

		/**
		 * Returns the oldid of this revision. Don't confuse this with
		 * <tt>rcid</tt>
		 * 
		 * @return the oldid (long)
		 * @since 0.17
		 */
		public long getRevid() {
			return revid;
		}

		/**
		 * Gets the time that this revision was made.
		 * 
		 * @return the timestamp
		 * @since 0.17
		 */
		public Calendar getTimestamp() {
			return timestamp;
		}

	}

	// INTERNALS

	// miscellany

	/**
	 * A generic URL content fetcher. This is only useful for GET requests,
	 * which is almost everything that doesn't modify the wiki. Might be useful
	 * for subclasses.
	 * 
	 * Here we also check the database lag and wait if it exceeds
	 * <tt>maxlag</tt>, see
	 * <a href="https://mediawiki.org/wiki/Manual:Maxlag_parameter"> here</a>
	 * for how this works.
	 * 
	 * @param url
	 *            the url to fetch
	 * @param caller
	 *            the caller of this method
	 * @return the content of the fetched URL
	 * @throws IOException
	 *             if a network error occurs
	 * @since 0.18
	 */
	private String fetch(String url, String caller) throws IOException {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(fetchStream(url, caller), "UTF-8"))) {
			String line;
			StringBuilder text = new StringBuilder(100000);
			while ((line = in.readLine()) != null) {
				text.append(line);
				text.append("\n");
			}
			String temp = text.toString();
			if (temp.contains("<error code="))
				// Something *really* bad happened. Most of these are
				// self-explanatory
				// and are indicative of bugs (not necessarily in this
				// framework) or
				// can be avoided entirely.
				throw new UnknownError(
						"MW API error. Server response was: " + temp);
			return temp;
		}
	}

	public class RevisionWalker implements AutoCloseable {

		private String url;

		private String rvcontinue;

		private XMLStreamReader reader;

		private InputStream stream;

		private Revision revision;

		private String text;

		private int rvLimit = 5;

		public RevisionWalker(String title, Calendar rvStart)
				throws IOException {
			StringBuilder url = new StringBuilder(query);
			url.append("&prop=revisions");
			if (rvStart != null) {
				url.append("&rvstart=");
				url.append(calendarToTimestamp(rvStart));
			}
			url.append("&rvprop=ids|content|user|timestamp");
			url.append("&titles=");
			try {
				url.append(URLEncoder.encode(normalize(title), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				// Won't happen - any conformat JVM supports UTF-8
				throw new RuntimeException(e);
			}
			this.url = url.toString();
		}

		public boolean next() throws IOException {
			try {
				if (null == reader) {
					String url = this.url;
					url = url + "&rvlimit=" + rvLimit;
					rvLimit *= 2;
					if (rvcontinue != null) {
						// FIXME noch ungetesteter Zweig
						url = url + "&rvcontinue=" + rvcontinue;
						rvcontinue = null;
					}
					stream = fetchStream(url, "RevisionWalker");
					reader = factory.createXMLStreamReader(stream);
					top: while (true) {
						reader.nextTag();
						if (reader.isStartElement()) {
							switch (reader.getLocalName()) {
							case "query-continue":
								reader.nextTag();
								rvcontinue = reader.getAttributeValue(null,
										"rvcontinue");
								break;
							case "warnings":
								parseWarnings(reader);
								break;
							case "revisions":
								break top;
							}
						}
					}
				}
				reader.nextTag();
				if (reader.isStartElement()) {
					assert"rev".equals(reader.getLocalName());
					String revidStr = reader.getAttributeValue(null, "revid");
					long revid = (null == revidStr) ? 0
							: Long.parseLong(revidStr);
					String timestampStr = reader.getAttributeValue(null,
							"timestamp");
					Calendar timestamp = (null == timestampStr) ? null
							: timestampToCalendar(timestampStr);
					String user = reader.getAttributeValue(null, "user");
					revision = new Revision(revid, timestamp, user);
					text = reader.getElementText();
					return true;
				}
				close();
				if (null == rvcontinue) {
					// FIXME noch ungetesteter Zweig
					return false;
				}
				return next();
			} catch (XMLStreamException e) {
				throw new RuntimeException(e);
			}

			// FIXME berücksichtigen
			// case "error":
			// String code = attributes.getValue("code");
			// String info = attributes.getValue("info");
			// throw new RuntimeException("MW API error: code=" + code
			// + "info=" + info);
		}

		@Override
		public void close() throws IOException {
			if (null != stream) {
				stream.close();
				stream = null;
			}
			if (null != reader) {
				try {
					reader.close();
					reader = null;
				} catch (XMLStreamException e) {
					throw new RuntimeException(e);
				}
			}
		}

		// XXX Zur Konsistenz mit Revision besser über revision.getText
		// zurückgeben.
		// Dann kann evtl. auch revision() die Funktion von next() übernehmen.
		public String text() {
			return text;
		}

		public Revision revision() {
			return revision;
		}
	}

	private InputStream fetchStream(String url, String caller)
			throws IOException {
		// connect
		logurl(url, caller);
		URLConnection connection = new URL(url).openConnection();
		connection.setConnectTimeout(CONNECTION_CONNECT_TIMEOUT_MSEC);
		connection.setReadTimeout(CONNECTION_READ_TIMEOUT_MSEC);
		setCookies(connection);
		connection.connect();
		checkStatus(connection);
		grabCookies(connection);

		// check lag
		int lag = connection.getHeaderFieldInt("X-Database-Lag", -5);
		if (lag > maxlag) {
			try {
				synchronized (this) {
					int time = connection.getHeaderFieldInt("Retry-After", 10);
					log(Level.WARNING, caller,
							"Current database lag " + lag + " s exceeds "
									+ maxlag + " s, waiting " + time + " s.");
					Thread.sleep(time * 1000);
				}
			} catch (InterruptedException ex) {
				// nobody cares
			}
			return fetchStream(url, caller); // retry the request
		}

		// get the text
		//return new GZIPInputStream(connection.getInputStream());
		return connection.getInputStream();
	}

	private String safePost(String url, String text, String caller)
			throws IOException, LoginException {
		int delay = 10000;
		while (true) {
			String response = post(url, text, caller);
			try {
				checkErrors(response, "edit");
				return response;
			} catch (IOException e) {
				log(Level.WARNING, "edit",
						"Exception: " + e.getMessage() + " Retrying...");
				// All IOExceptionsa are assumed recoverable
				// --> wait & retry
				try {
					Thread.sleep(delay);
					// double delay for next run
					delay *= 2;
				} catch (InterruptedException e1) {
					// won't happen & don't care
				}
			}
		}
	}

	/**
	 * Does a text-only HTTP POST.
	 * 
	 * @param url
	 *            the url to post to
	 * @param text
	 *            the text to post
	 * @param caller
	 *            the caller of this method
	 * @throws IOException
	 *             if a network error occurs
	 * @return the server response
	 * @see #multipartPost(java.lang.String, java.util.Map, java.lang.String)
	 * @since 0.24
	 */
	private String post(String url, String text, String caller)
			throws IOException {
		logurl(url, caller);
		URLConnection connection = new URL(url).openConnection();
		setCookies(connection);
		connection.setDoOutput(true);
		connection.setConnectTimeout(CONNECTION_CONNECT_TIMEOUT_MSEC);
		connection.setReadTimeout(CONNECTION_READ_TIMEOUT_MSEC);
		connection.connect();
		OutputStreamWriter out = new OutputStreamWriter(
				connection.getOutputStream(), "UTF-8");
		out.write(text);
		out.close();
		checkStatus(connection);
		BufferedReader in = new BufferedReader(new InputStreamReader(
				//new GZIPInputStream(connection.getInputStream()), "UTF-8"));
				connection.getInputStream(), "UTF-8"));
		grabCookies(connection);
		String line;
		StringBuilder temp = new StringBuilder();
		while ((line = in.readLine()) != null) {
			temp.append(line);
			temp.append("\n");
		}
		in.close();
		return temp.toString();
	}

	private void checkStatus(URLConnection connection) throws IOException {
		if (connection instanceof HttpURLConnection) {
			HttpURLConnection httpURLConnection = (HttpURLConnection) connection;
			int responseCode = httpURLConnection.getResponseCode();
			if (HttpURLConnection.HTTP_OK != responseCode) {
				throw new IOException("HTTP " + responseCode + " "
						+ httpURLConnection.getResponseMessage());
			}

		}
	}

	/**
	 * Checks for errors from standard read/write requests.
	 * 
	 * @param line
	 *            the response from the server to analyze
	 * @param caller
	 *            what we tried to do
	 * @throws AccountLockedException
	 *             if the user is blocked
	 * @throws HttpRetryException
	 *             if the database is locked or action was throttled and a retry
	 *             failed
	 * @throws UnknownError
	 *             in the case of a MediaWiki bug
	 * @since 0.18
	 */
	private void checkErrors(String line, String caller)
			throws IOException, LoginException {
		// empty response from server
		if (line.isEmpty())
			throw new UnknownError("Received empty response from server!");
		// successful
		if (line.contains("result=\"Success\""))
			return;
		// rate limit (automatic retry), though might be a long one (e.g. email)
		if (line.contains("error code=\"ratelimited\"")) {
			log(Level.WARNING, caller, "Server-side throttle hit.");
			throw new HttpRetryException("Action throttled.", 503);
		}
		// blocked! (note here the \" in blocked is deliberately missing for
		// emailUser()
		if (line.contains("error code=\"blocked")
				|| line.contains("error code=\"autoblocked\"")) {
			log(Level.SEVERE, caller,
					"Cannot " + caller + " - user is blocked!.");
			throw new AccountLockedException("Current user is blocked!");
		}
		// cascade protected
		if (line.contains("error code=\"cascadeprotected\"")) {
			log(Level.WARNING, caller, "Cannot " + caller
					+ " - page is subject to cascading protection.");
			throw new CredentialException("Page is cascade protected");
		}
		// database lock (automatic retry)
		if (line.contains("error code=\"readonly\"")) {
			log(Level.WARNING, caller, "Database locked!");
			throw new HttpRetryException("Database locked!", 503);
		}
		// unknown error
		if (line.contains("error code=\"unknownerror\""))
			throw new UnknownError(
					"Unknown MediaWiki API error, response was " + line);
		// generic (automatic retry)
		throw new IOException("MediaWiki error, response was " + line);
	}

	/**
	 * Strips entity references like &quot; from the supplied string. This might
	 * be useful for subclasses.
	 * 
	 * @param in
	 *            the string to remove URL encoding from
	 * @return that string without URL encoding
	 * @since 0.11
	 */
	private String decode(String in) {
		// Remove entity references. Oddly enough, URLDecoder doesn't nuke
		// these.
		in = in.replace("&lt;", "<").replace("&gt;", ">"); // html tags
		in = in.replace("&amp;", "&");
		in = in.replace("&quot;", "\"");
		in = in.replace("&#039;", "'");
		return in;
	}

	/**
	 * Parses the next XML attribute with the given name.
	 * 
	 * @param xml
	 *            the xml to search
	 * @param attribute
	 *            the attribute to search
	 * @param index
	 *            where to start looking
	 * @return the value of the given XML attribute, or null if the attribute is
	 *         not present
	 * @since 0.28
	 */
	private String parseAttribute(String xml, String attribute, int index) {
		// let's hope the JVM always inlines this
		if (xml.contains(attribute + "=\"")) {
			int a = xml.indexOf(attribute + "=\"", index) + attribute.length()
					+ 2;
			int b = xml.indexOf('\"', a);
			return xml.substring(a, b);
		} else
			return null;
	}

	/**
	 * Convenience method for converting a namespace list into String form.
	 * 
	 * @param sb
	 *            the url StringBuilder to append to
	 * @param id
	 *            the request type prefix (e.g. "pl" for prop=links)
	 * @param namespaces
	 *            the list of namespaces to append
	 * @since 0.27
	 */
	private void constructNamespaceString(StringBuilder sb, String id,
			int... namespaces) {
		int temp = namespaces.length;
		if (temp == 0)
			return;
		sb.append("&");
		sb.append(id);
		sb.append("namespace=");
		for (int i = 0; i < temp - 1; i++) {
			sb.append(namespaces[i]);
			sb.append("%7C");
		}
		sb.append(namespaces[temp - 1]);
	}

	/**
	 * Cuts up a list of titles into batches for prop=X&titles=Y type queries.
	 * 
	 * @param titles
	 *            a list of titles.
	 * @return the titles ready for insertion into a URL
	 * @throws IOException
	 *             if a network error occurs
	 * @since 0.29
	 */
	private String[] constructTitleString(String[] titles) throws IOException {
		// remove duplicates, sort and pad
		// Set<String> set = new TreeSet(Arrays.asList(titles));
		// String[] temp = set.toArray(new String[set.size()]);
		// String[] aaa = new String[titles.length];
		// System.arraycopy(temp, 0, titles, 0, temp.length);
		// System.arraycopy(aaa, 0, titles, temp.length, titles.length -
		// temp.length);

		// actually construct the string
		String[] ret = new String[titles.length / slowmax + 1];
		StringBuilder buffer = new StringBuilder();
		for (int i = 0; i < titles.length; i++) {
			buffer.append(normalize(titles[i]));
			if (i == titles.length - 1 || i == slowmax - 1) {
				ret[i / slowmax] = URLEncoder.encode(buffer.toString(),
						"UTF-8");
				buffer = new StringBuilder();
			} else
				buffer.append("|");
		}
		return ret;
	}

	/**
	 * Convenience method for normalizing MediaWiki titles. (Converts all
	 * underscores to spaces).
	 * 
	 * @param s
	 *            the string to normalize
	 * @return the normalized string
	 * @throws IllegalArgumentException
	 *             if the title is invalid
	 * @throws IOException
	 *             if a network error occurs (rare)
	 * @since 0.27
	 */
	private String normalize(String s) throws IOException {
		if (s.isEmpty())
			return s;
		char[] temp = s.toCharArray();
		if (wgCapitalLinks) {
			// convert first character in the actual title to upper case
			int ns = namespace(s);
			if (ns == MAIN_NAMESPACE)
				temp[0] = Character.toUpperCase(temp[0]);
			else {
				// don't forget the extra colon
				int index = namespaceIdentifier(ns).length() + 1;
				temp[index] = Character.toUpperCase(temp[index]);
			}
		}
		for (int i = 0; i < temp.length; i++) {
			switch (temp[i]) {
			// illegal characters
			case '{':
			case '}':
			case '<':
			case '>':
			case '[':
			case ']':
			case '|':
				throw new IllegalArgumentException(s + " is an illegal title");
			case '_':
				temp[i] = ' ';
				break;
			}
		}
		// https://www.mediawiki.org/wiki/Unicode_normalization_considerations
		return Normalizer.normalize(new String(temp), Normalizer.Form.NFC);
	}

	// cookie methods

	/**
	 * Sets cookies to an unconnected URLConnection and enables gzip compression
	 * of returned text.
	 * 
	 * @param u
	 *            an unconnected URLConnection
	 */
	private void setCookies(URLConnection u) {
		StringBuilder cookie = new StringBuilder(100);
		for (Map.Entry<String, String> entry : cookies.entrySet()) {
			cookie.append(entry.getKey());
			cookie.append("=");
			cookie.append(entry.getValue());
			cookie.append("; ");
		}
		u.setRequestProperty("Cookie", cookie.toString());

	//	u.setRequestProperty("Accept-encoding", "gzip");
		u.setRequestProperty("User-Agent", useragent);
	}

	/**
	 * Grabs cookies from the URL connection provided.
	 * 
	 * @param u
	 *            an unconnected URLConnection
	 * @param map
	 *            the cookie store
	 */
	private void grabCookies(URLConnection u) {
		String headerName;
		for (int i = 1; (headerName = u.getHeaderFieldKey(i)) != null; i++)
			if (headerName.equals("Set-Cookie")) {
				String cookie = u.getHeaderField(i);
				cookie = cookie.substring(0, cookie.indexOf(';'));
				String name = cookie.substring(0, cookie.indexOf('='));
				String value = cookie.substring(cookie.indexOf('=') + 1,
						cookie.length());
				cookies.put(name, value);
			}
	}

	// logging methods

	/**
	 * Logs a successful result.
	 * 
	 * @param text
	 *            string the string to log
	 * @param method
	 *            what we are currently doing
	 * @param level
	 *            the level to log at
	 * @since 0.06
	 */
	private void log(Level level, String method, String text) {
		Logger logger = Logger.getLogger("wiki");
		logger.logp(level, "Wiki", method, "{0}", new Object[] { text });
	}

	/**
	 * Logs a url fetch.
	 * 
	 * @param url
	 *            the url we are fetching
	 * @param method
	 *            what we are currently doing
	 * @since 0.08
	 */
	private void logurl(String url, String method) {
		Logger logger = Logger.getLogger("wiki");
		logger.logp(Level.INFO, "Wiki", method, "Fetching URL {0}", url);
	}

	// calendar/timestamp methods

	/**
	 * Creates a Calendar object with the current time. Wikimedia wikis use UTC,
	 * override this if your wiki is in another timezone.
	 * 
	 * @return see above
	 * @since 0.26
	 */
	private Calendar makeCalendar() {
		return new GregorianCalendar(TimeZone.getTimeZone("UTC"));
	}

	/**
	 * Turns a calendar into a timestamp of the format yyyymmddhhmmss. Might be
	 * useful for subclasses.
	 * 
	 * @param c
	 *            the calendar to convert
	 * @return the converted calendar
	 * @see #timestampToCalendar
	 * @since 0.08
	 */
	private String calendarToTimestamp(Calendar c) {
		return String.format("%04d%02d%02d%02d%02d%02d", c.get(Calendar.YEAR),
				c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
				c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
				c.get(Calendar.SECOND));
	}

	/**
	 * Turns a timestamp into a Calendar object. Might be useful for subclasses.
	 * 
	 * @param timestamp
	 *            the timestamp to convert
	 * @param api
	 *            whether the timestamp is of the format yyyy-mm-ddThh:mm:ssZ as
	 *            opposed to yyyymmddhhmmss (which is the default)
	 * @return the converted Calendar
	 * @see #calendarToTimestamp
	 * @since 0.08
	 */
	private final Calendar timestampToCalendar(String timestamp) {
		Calendar calendar = makeCalendar();
		timestamp = convertTimestamp(timestamp);
		int year = Integer.parseInt(timestamp.substring(0, 4));
		int month = Integer.parseInt(timestamp.substring(4, 6)) - 1; // January
																		// == 0!
		int day = Integer.parseInt(timestamp.substring(6, 8));
		int hour = Integer.parseInt(timestamp.substring(8, 10));
		int minute = Integer.parseInt(timestamp.substring(10, 12));
		int second = Integer.parseInt(timestamp.substring(12, 14));
		calendar.set(year, month, day, hour, minute, second);
		return calendar;
	}

	/**
	 * Converts a timestamp of the form used by the API (yyyy-mm-ddThh:mm:ssZ)
	 * to the form yyyymmddhhmmss.
	 * 
	 * @param timestamp
	 *            the timestamp to convert
	 * @return the converted timestamp
	 * @see #timestampToCalendar
	 * @since 0.12
	 */
	private String convertTimestamp(String timestamp) {
		StringBuilder ts = new StringBuilder(timestamp.substring(0, 4));
		ts.append(timestamp.substring(5, 7));
		ts.append(timestamp.substring(8, 10));
		ts.append(timestamp.substring(11, 13));
		ts.append(timestamp.substring(14, 16));
		ts.append(timestamp.substring(17, 19));
		return ts.toString();
	}

	private void parseWarnings(XMLStreamReader reader)
			throws XMLStreamException {
		while (true) {
			reader.nextTag();
			if (reader.isStartElement()) {
				// e. g.
				// "This result was truncated because it would otherwise be
				// larger than the limit of 12582912 bytes"
				String message = reader.getElementText();
				log(Level.WARNING, "parseWarnings", message);
			} else if (reader.isEndElement()
					&& "warnings".equals(reader.getLocalName())) {
				break;
			}
		}
	}

}
