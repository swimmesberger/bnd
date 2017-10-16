package aQute.bnd.osgi;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aQute.bnd.annotation.headers.BundleCategory;
import aQute.bnd.annotation.headers.BundleContributors;
import aQute.bnd.annotation.headers.BundleCopyright;
import aQute.bnd.annotation.headers.BundleDevelopers;
import aQute.bnd.annotation.headers.BundleDocURL;
import aQute.bnd.annotation.headers.BundleLicense;
import aQute.bnd.annotation.headers.Category;
import aQute.bnd.annotation.headers.ProvideCapability;
import aQute.bnd.annotation.headers.RequireCapability;
import aQute.bnd.header.Attrs;
import aQute.bnd.header.Parameters;
import aQute.bnd.osgi.Descriptors.PackageRef;
import aQute.bnd.osgi.Descriptors.TypeRef;
import aQute.bnd.version.Version;
import aQute.lib.collections.MultiMap;
import aQute.lib.strings.Strings;

/**
 * This class parses the 'header annotations'. Header annotations are
 * annotations that cause headers in the manifest. There are a number of those
 * headers annotations defined in the aQute.bnd.annotation.headers package, e.g.
 * {@link BundleCopyright}. This module applies the semantics of the defined
 * fields in those annotations. It is called at the post parse phase in
 * Analyzer. This {@link ClassDataCollector} is called for all classes in our
 * scope. We first look if any header annotations are applied. We also keep
 * track of what other annotations are applied to these classes. After all the
 * classes have been parsed, we look at any of the annotations that was applied
 * to one of the contained classes. These annotations are also parsed then to
 * check if they have header annotations applied to them.
 * <p>
 * This may sound a bit bizarre, so let me explain. The idea is that you can
 * create a custom annotation for a specific resource.
 * 
 * <pre>
 * &#064;RequireCapability(&quot;osgi.webresource;filter:='(&amp;(osgi.
 * webresource=/google/angular)(version&gt;=${&#064;version}))&quot;) &#064;interface
 * Angular {}
 * </pre>
 * 
 * Now all a user has to do is apply the @Angular annotation. It will then
 * automatically create a Require-Capability, with the version of the package.
 * 
 * <pre>
 *  &#64;Angular public class MySpace {...}
 * </pre>
 * 
 * {@link About} provides some more information.
 */
class AnnotationHeaders extends ClassDataCollector implements Closeable {

	static final Pattern			SIMPLE_PARAM_PATTERN	= Pattern
			.compile("\\$\\{(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)\\}");

	final Analyzer					analyzer;
	final Set<String>				interesting				= new HashSet<>();
	final MultiMap<String,String>	headers					= new MultiMap<>();

	//
	// Constant Strings for a fast switch statement
	//

	static final String				BUNDLE_LICENSE			= "aQute.bnd.annotation.headers.BundleLicense";
	static final String				REQUIRE_CAPABILITY		= "aQute.bnd.annotation.headers.RequireCapability";
	static final String				PROVIDE_CAPABILITY		= "aQute.bnd.annotation.headers.ProvideCapability";
	static final String				BUNDLE_CATEGORY			= "aQute.bnd.annotation.headers.BundleCategory";
	static final String				BUNDLE_DOC_URL			= "aQute.bnd.annotation.headers.BundleDocURL";
	static final String				BUNDLE_DEVELOPERS		= "aQute.bnd.annotation.headers.BundleDevelopers";
	static final String				BUNDLE_CONTRIBUTORS		= "aQute.bnd.annotation.headers.BundleContributors";
	static final String				BUNDLE_COPYRIGHT		= "aQute.bnd.annotation.headers.BundleCopyright";

	// Class we're currently processing
	Clazz							current;

	// we parse the annotations separately at the end
	boolean							finalizing;

	/*
	 * Initialize
	 */
	AnnotationHeaders(Analyzer analyzer) {
		this.analyzer = analyzer;

		//
		// The analyser has its own domain of type refs, so we need to get our
		// standard set to do fast comparisons
		//

		interesting.add(BUNDLE_LICENSE);
		interesting.add(REQUIRE_CAPABILITY);
		interesting.add(PROVIDE_CAPABILITY);
		interesting.add(BUNDLE_CATEGORY);
		interesting.add(BUNDLE_DOC_URL);
		interesting.add(BUNDLE_DEVELOPERS);
		interesting.add(BUNDLE_CONTRIBUTORS);
		interesting.add(BUNDLE_COPYRIGHT);
	}

	@Override
	public boolean classStart(Clazz c) {

		//
		// Parse any classes except annotations
		//
		if (!c.isAnnotation() && c.annotations != null) {

			current = c;
			return true;
		}
		current = null;
		return false;
	}

	/*
	 * Called when an annotation is found. Dispatch on the known types.
	 */
	public void annotation(Annotation annotation) throws Exception {
		TypeRef name = annotation.getName();
		if (name.isJava())
			return;

		switch (name.getFQN()) {
			case BUNDLE_LICENSE :
				doLicense(annotation);
				break;
			case REQUIRE_CAPABILITY :
				doRequireCapability(annotation);
				break;
			case PROVIDE_CAPABILITY :
				doProvideCapability(annotation);
				break;
			case BUNDLE_CATEGORY :
				doBundleCategory(annotation.getAnnotation(BundleCategory.class));
				break;
			case BUNDLE_DOC_URL :
				doBundleDocURL(annotation.getAnnotation(BundleDocURL.class));
				break;
			case BUNDLE_DEVELOPERS :
				doBundleDevelopers(annotation.getAnnotation(BundleDevelopers.class));
				break;
			case BUNDLE_CONTRIBUTORS :
				doBundleContributors(annotation.getAnnotation(BundleContributors.class));
				break;
			case BUNDLE_COPYRIGHT :
				doBundeCopyright(annotation.getAnnotation(BundleCopyright.class));
				break;
			default :
				doAnnotatedAnnotation(annotation, name);
				break;
		}
	}

	/**
	 * Handle the case where an annotation is annotated by one of our header
	 * annotations.
	 * 
	 * @param annotation
	 * @param name
	 * @throws Exception
	 */
	void doAnnotatedAnnotation(final Annotation annotation, TypeRef name) throws Exception {
		final Clazz c = analyzer.findClass(annotation.getName());
		if (c != null && c.annotations != null) {
			if (c.annotations.stream().map(TypeRef::getFQN).anyMatch(interesting::contains)) {
				c.parseClassFileWithCollector(new ClassDataCollector() {
					@Override
					public void annotation(Annotation a) throws Exception {
						if (interesting.contains(a.getName().getFQN())) {
							a.merge(annotation);
							a.addDefaults(c);
							AnnotationHeaders.this.annotation(a);
						}
					}
				});
			}
		}
	}

	/*
	 * Called after the class space has been parsed. We then continue to parse
	 * the used annotations.
	 */
	public void close() throws IOException {}

	/*
	 * Bundle-Developers header
	 */
	private void doBundleDevelopers(BundleDevelopers annotation) throws IOException {
		StringBuilder sb = new StringBuilder(annotation.value());
		if (annotation.name() != null) {
			sb.append(";name='");
			escape(sb, annotation.name());
			sb.append("'");
		}
		if (annotation.roles() != null) {
			sb.append(";roles='");
			escape(sb, annotation.roles());
			sb.append("'");
		}
		if (annotation.organizationUrl() != null) {
			sb.append(";organizationUrl='");
			escape(sb, annotation.organizationUrl());
			sb.append("'");
		}
		if (annotation.organization() != null) {
			sb.append(";organization='");
			escape(sb, annotation.organization());
			sb.append("'");
		}
		if (annotation.timezone() != 0)
			sb.append(";timezone=").append(annotation.timezone());

		add(Constants.BUNDLE_DEVELOPERS, sb.toString());
	}

	/*
	 * Bundle-Contributors header
	 */

	private void doBundleContributors(BundleContributors annotation) throws IOException {
		StringBuilder sb = new StringBuilder(annotation.value());
		if (annotation.name() != null) {
			sb.append(";name='");
			escape(sb, annotation.name());
			sb.append("'");
		}
		if (annotation.roles() != null) {
			sb.append(";roles='");
			escape(sb, annotation.roles());
			sb.append("'");
		}
		if (annotation.organizationUrl() != null) {
			sb.append(";organizationUrl='");
			escape(sb, annotation.organizationUrl());
			sb.append("'");
		}
		if (annotation.organization() != null) {
			sb.append(";organization='");
			escape(sb, annotation.organization());
			sb.append("'");
		}
		if (annotation.timezone() != 0)
			sb.append(";timezone=").append(annotation.timezone());
		add(Constants.BUNDLE_CONTRIBUTORS, sb.toString());
	}

	/*
	 * Bundle-Copyright header
	 */
	private void doBundeCopyright(BundleCopyright annotation) throws IOException {
		add(Constants.BUNDLE_COPYRIGHT, annotation.value());
	}

	/*
	 * Bundle-DocURL header
	 */
	private void doBundleDocURL(BundleDocURL annotation) throws IOException {
		add(Constants.BUNDLE_DOCURL, annotation.value());
	}

	/*
	 * Bundle-Category header
	 */
	private void doBundleCategory(BundleCategory annotation) throws IOException {
		if (annotation.custom() != null)
			for (String s : annotation.custom()) {
				add(Constants.BUNDLE_CATEGORY, s);
			}

		if (annotation.value() != null)
			for (Category s : annotation.value()) {
				add(Constants.BUNDLE_CATEGORY, s.toString());
			}
	}

	/*
	 * Provide-Capability header
	 */
	private void doProvideCapability(Annotation a) throws Exception {
		ProvideCapability annotation = a.getAnnotation(ProvideCapability.class);

		Parameters p = new Parameters();
		Attrs attrs = getAttributes(a, "ns");
		directivesAndVersion(attrs, "uses", "mandatory", "effective");
		p.put(annotation.ns(), attrs);

		String value = attrs.remove("name");
		if (value != null)
			attrs.put(annotation.ns(), value);

		value = attrs.remove("value");

		String s = p.toString();
		if (value != null)
			s += ";" + annotation.value();

		add(Constants.PROVIDE_CAPABILITY, s);
	}

	/*
	 * Require-Capability header
	 */
	private void doRequireCapability(Annotation a) throws Exception {
		RequireCapability annotation = a.getAnnotation(RequireCapability.class);
		Parameters p = new Parameters();
		Attrs attrs = getAttributes(a, "ns");
		directivesAndVersion(attrs, "filter", "effective", "resolution");
		replaceParameters(attrs);

		if ("".equals(attrs.get(Constants.FILTER_DIRECTIVE)))
			attrs.remove(Constants.FILTER_DIRECTIVE);

		p.put(annotation.ns(), attrs);

		String s = p.toString();

		String extra = annotation.extra();
		if (extra != null) {
			extra = extra.trim();
			if (extra.length() > 0)
				s += ";" + extra;
		}

		add(Constants.REQUIRE_CAPABILITY, s);
	}

	private void replaceParameters(Attrs attrs) throws IllegalArgumentException {
		for (Entry<String,String> entry : attrs.entrySet()) {
			boolean modified = false;
			StringBuffer sb = new StringBuffer();

			Matcher matcher = SIMPLE_PARAM_PATTERN.matcher(entry.getValue());
			while (matcher.find()) {
				modified = true;
				String key = matcher.group(1);
				String substitution = attrs.get(key);
				if (substitution == null) {
					matcher.appendReplacement(sb, "");
					sb.append(matcher.group(0));
				} else if (SIMPLE_PARAM_PATTERN.matcher(substitution).find())
					throw new IllegalArgumentException("nested substitutions not permitted");
				else
					matcher.appendReplacement(sb, substitution);
			}

			if (modified) {
				matcher.appendTail(sb);
				entry.setValue(sb.toString());
			}
		}
	}

	/*
	 * Bundle-License header
	 */
	private void doLicense(Annotation a) throws Exception {
		BundleLicense annotation = a.getAnnotation(BundleLicense.class);
		Parameters p = new Parameters();
		p.put(annotation.name(), getAttributes(a, "name"));
		add(Constants.BUNDLE_LICENSE, p.toString());
	}

	private void directivesAndVersion(Attrs attrs, String... directives) {
		for (String directive : directives) {
			String s = attrs.remove(directive);
			if (s != null) {
				attrs.put(directive + ":", s);
			}
		}

		String remove = attrs.remove(Constants.VERSION_ATTRIBUTE);
		if (remove != null) {
			attrs.putTyped("version", Version.parseVersion(remove));
		}
	}

	private Attrs getAttributes(Annotation a, String... ignores) {
		Attrs attrs = new Attrs();
		outer: for (String key : a.keySet()) {

			for (String ignore : ignores) {
				if (key.equals(ignore))
					continue outer;
			}

			attrs.putTyped(key, a.get(key));
		}
		return attrs;
	}

	/*
	 * Adds a header. Will preprocess the text.
	 */
	private void add(String name, String value) throws IOException {
		if (value == null)
			return;

		Processor next = new Processor(analyzer);
		next.setProperty("@class", current.getFQN());
		next.setProperty("@class-short", current.getClassName().getShortName());
		PackageRef pref = current.getClassName().getPackageRef();
		next.setProperty("@package", pref.getFQN());
		Attrs info = analyzer.getClasspathExports().get(pref);
		if (info == null)
			info = analyzer.getContained().get(pref);

		if (info != null && info.containsKey("version")) {
			next.setProperty("@version", info.get("version"));
		}
		Macro macro = next.getReplacer();

		/*
		 * These strings come from code, which might also be included from
		 * external parties. So we just do not want to call any system commands
		 * from these sources
		 */
		boolean prev = macro.setNosystem(true);
		try {
			value = macro.process(value);
			headers.add(name, value);
			next.close();
		} finally {
			macro.setNosystem(prev);
		}
	}

	/*
	 * This method is a pass thru for the properties of the analyzer. If we have
	 * such a header, we get the analyzer header and concatenate our values
	 * after removing dups.
	 */

	public String getHeader(String name) {
		String value = analyzer.getProperty(name);
		if (headers.containsKey(name)) {
			//
			// Remove duplicates and sort
			//
			Set<String> set = new TreeSet<String>(headers.get(name));
			String header = Strings.join(set);
			if (value == null)
				return header;
			else
				return value + "," + header;
		}
		return value;
	}

	private void escape(StringBuilder app, String s[]) throws IOException {
		String joined = Strings.join(s);
		escape(app, joined);
	}

	private void escape(StringBuilder app, String s) throws IOException {
		Processor.quote(app, s);
	}

}
