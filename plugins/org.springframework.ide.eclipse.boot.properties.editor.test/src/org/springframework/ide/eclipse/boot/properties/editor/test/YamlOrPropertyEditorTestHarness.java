/*******************************************************************************
 * Copyright (c) 2015, 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.boot.properties.editor.test;

import static org.springsource.ide.eclipse.commons.tests.util.StsTestCase.assertContains;
import static org.springsource.ide.eclipse.commons.tests.util.StsTestCase.assertElements;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.m2e.jdt.IClasspathManager;
import org.eclipse.m2e.jdt.MavenJdtPlugin;
import org.mockito.Mockito;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.boot.configurationmetadata.Deprecation;
import org.springframework.boot.configurationmetadata.ValueHint;
import org.springframework.boot.configurationmetadata.ValueProvider;
import org.springframework.ide.eclipse.boot.properties.editor.DocumentContextFinder;
import org.springframework.ide.eclipse.boot.properties.editor.FuzzyMap;
import org.springframework.ide.eclipse.boot.properties.editor.SpringPropertyIndex;
import org.springframework.ide.eclipse.boot.properties.editor.metadata.CachingValueProvider;
import org.springframework.ide.eclipse.boot.properties.editor.metadata.PropertyInfo;
import org.springframework.ide.eclipse.boot.properties.editor.metadata.ValueProviderRegistry;
import org.springframework.ide.eclipse.boot.properties.editor.util.SpringPropertyIndexProvider;
import org.springframework.ide.eclipse.boot.test.BootProjectTestHarness;
import org.springframework.ide.eclipse.boot.test.MockPrefsStore;
import org.springframework.ide.eclipse.editor.support.completions.CompletionFactory;
import org.springframework.ide.eclipse.editor.support.hover.HoverInfoProvider;
import org.springframework.ide.eclipse.editor.support.reconcile.DefaultSeverityProvider;
import org.springframework.ide.eclipse.editor.support.reconcile.FixableProblem;
import org.springframework.ide.eclipse.editor.support.reconcile.IReconcileEngine;
import org.springframework.ide.eclipse.editor.support.reconcile.ProblemType;
import org.springframework.ide.eclipse.editor.support.reconcile.QuickfixContext;
import org.springframework.ide.eclipse.editor.support.reconcile.ReconcileProblem;
import org.springframework.ide.eclipse.editor.support.reconcile.SeverityProvider;
import org.springframework.ide.eclipse.editor.support.util.UserInteractions;
import org.springsource.ide.eclipse.commons.core.util.StringUtil;
import org.springsource.ide.eclipse.commons.frameworks.test.util.ACondition;

import junit.framework.TestCase;

/**
 * @author Kris De Volder
 */
public abstract class YamlOrPropertyEditorTestHarness extends TestCase {

	protected final UserInteractions ui = Mockito.mock(UserInteractions.class);
	protected final MockPrefsStore projectPrefs = new MockPrefsStore();
	protected final MockPrefsStore workspacePrefs = new MockPrefsStore();

	protected QuickfixContext getQuickfixContext(MockEditor editor) {
		return new QuickfixContext() {

			@Override
			public IPreferenceStore getWorkspacePreferences() {
				return workspacePrefs;
			}

			@Override
			public UserInteractions getUI() {
				return ui;
			}

			@Override
			public IPreferenceStore getProjectPreferences() {
				return projectPrefs;
			}

			@Override
			public IProject getProject() {
				if (javaProject!=null) {
					return javaProject.getProject();
				}
				return null;
			}

			@Override
			public IJavaProject getJavaProject() {
				return javaProject;
			}

			@Override
			public IDocument getDocument() {
				return editor.getDocument();
			}
		};
	}

	public class ItemConfigurer {

		private ConfigurationMetadataProperty item;

		public ItemConfigurer(ConfigurationMetadataProperty item) {
			this.item = item;
		}

		/**
		 * Add a provider with a single parameter.
		 * @return
		 */
		public ItemConfigurer provider(String name, String paramName, Object paramValue) {
			ValueProvider provider = new ValueProvider();
			provider.setName(name);
			provider.getParameters().put(paramName, paramValue);
			item.getHints().getValueProviders().add(provider);
			return this;
		}

		/**
		 * Add a value hint. If description contains a '.' the dot is used
		 * to break description into a short and long description.
		 * @return
		 */
		public ItemConfigurer valueHint(Object value, String description) {
			ValueHint hint = new ValueHint();
			hint.setValue(value);
			if (description!=null) {
				int dotPos = description.indexOf('.');
				if (dotPos>=0) {
					hint.setShortDescription( description.substring(0, dotPos));
				}
				hint.setDescription(description);
			}
			item.getHints().getValueHints().add(hint);
			return this;
		}
	}

	Map<String, ConfigurationMetadataProperty> datas = new LinkedHashMap<>();
	private SpringPropertyIndex index = null;

	protected ValueProviderRegistry valueProviders = ValueProviderRegistry.getDefault();

	protected SpringPropertyIndexProvider indexProvider = new SpringPropertyIndexProvider() {

		public FuzzyMap<PropertyInfo> getIndex(IDocument doc) {
			if (index==null) {
				index =	new SpringPropertyIndex(valueProviders, javaProject);
				for (ConfigurationMetadataProperty propertyInfo : datas.values()) {
					index.add(propertyInfo);
				}
			}
			return index;
		}
	};


	protected static final Comparator<? super ICompletionProposal> COMPARATOR = new Comparator<ICompletionProposal>() {
		public int compare(ICompletionProposal p1, ICompletionProposal p2) {
			return CompletionFactory.SORTER.compare(p1, p2);
		}
	};

	private static final Comparator<ReconcileProblem> PROBLEM_COMPARATOR = new Comparator<ReconcileProblem>() {
		@Override
		public int compare(ReconcileProblem o1, ReconcileProblem o2) {
			return o1.getOffset() - o2.getOffset();
		}
	};

	protected IJavaProject javaProject = null;
	protected DocumentContextFinder documentContextFinder = new DocumentContextFinder() {
		public IJavaProject getJavaProject(IDocument doc) {
			return javaProject;
		}

		@Override
		public SeverityProvider getSeverityProvider(IDocument doc) {
			return new DefaultSeverityProvider();
		}
	};

	private Set<ProblemType> ignoredTypes = new HashSet<>();

	protected String getBundleName() {
		return "org.springframework.ide.eclipse.boot.properties.editor.test";
	}

	public ItemConfigurer data(String id, String type, Object deflt, String description,
			String... source
	) {
		ConfigurationMetadataProperty item = new ConfigurationMetadataProperty();
		item.setId(id);
		item.setDescription(description);
		item.setType(type);
		item.setDefaultValue(deflt);
		index = null;
		datas.put(item.getId(), item);
		return new ItemConfigurer(item);
	}

	public void keyHints(String id, String... hintValues) {
		index = null;
		List<ValueHint> hints = datas.get(id).getHints().getKeyHints();
		for (String value : hintValues) {
			ValueHint hint = new ValueHint();
			hint.setValue(value);
			hints.add(hint);
		}
	}

	public void valueHints(String id, String... hintValues) {
		index = null;
		List<ValueHint> hints = datas.get(id).getHints().getValueHints();
		for (String value : hintValues) {
			ValueHint hint = new ValueHint();
			hint.setValue(value);
			hints.add(hint);
		}
	}

	public void deprecate(String key, String replacedBy, String reason) {
		index = null;
		ConfigurationMetadataProperty info = datas.get(key);
		Deprecation d = new Deprecation();
		d.setReplacement(replacedBy);
		d.setReason(reason);
		info.setDeprecation(d);
	}

	public void useProject(IJavaProject jp) throws Exception {
		index = null;
		this.javaProject  = jp;
	}

	public void useProject(IProject p) throws Exception {
		useProject(JavaCore.create(p));
	}

	/**
	 * Call this method to add some default test data to the Completion engine's index.
	 * Note that this data is not added automatically, some test may want to use smaller
	 * test data sets.
	 */
	public void defaultTestData() {
		data("banner.charset", "java.nio.charset.Charset", "UTF-8", "Banner file encoding.");
		data("banner.location", "java.lang.String", "classpath:banner.txt", "Banner file location.");
		data("debug", "java.lang.Boolean", "false", "Enable debug logs.");
		data("flyway.check-location", "java.lang.Boolean", "false", "Check that migration scripts location exists.");
		data("flyway.clean-on-validation-error", "java.lang.Boolean", null, null);
		data("flyway.enabled", "java.lang.Boolean", "true", "Enable flyway.");
		data("flyway.encoding", "java.lang.String", null, null);
		data("flyway.ignore-failed-future-migration", "java.lang.Boolean", null, null);
		data("flyway.init-description", "java.lang.String", null, null);
		data("flyway.init-on-migrate", "java.lang.Boolean", null, null);
		data("flyway.init-sqls", "java.util.List<java.lang.String>", null, "SQL statements to execute to initialize a connection immediately after obtaining\n it.");
		data("flyway.init-version", "org.flywaydb.core.api.MigrationVersion", null, null);
		data("flyway.locations", "java.util.List<java.lang.String>", null, "Locations of migrations scripts.");
		data("flyway.out-of-order", "java.lang.Boolean", null, null);
		data("flyway.password", "java.lang.String", null, "Login password of the database to migrate.");
		data("flyway.placeholder-prefix", "java.lang.String", null, null);
		data("flyway.placeholders", "java.util.Map<java.lang.String,java.lang.String>", null, null);
		data("flyway.placeholder-suffix", "java.lang.String", null, null);
		data("flyway.schemas", "java.lang.String[]", null, null);
		data("flyway.sql-migration-prefix", "java.lang.String", null, null);
		data("flyway.sql-migration-separator", "java.lang.String", null, null);
		data("flyway.sql-migration-suffix", "java.lang.String", null, null);
		data("flyway.table", "java.lang.String", null, null);
		data("flyway.target", "org.flywaydb.core.api.MigrationVersion", null, null);
		data("flyway.url", "java.lang.String", null, "JDBC url of the database to migrate. If not set, the primary configured data source\n is used.");
		data("flyway.user", "java.lang.String", null, "Login user of the database to migrate.");
		data("flyway.validate-on-migrate", "java.lang.Boolean", null, null);
		data("http.mappers.json-pretty-print", "java.lang.Boolean", null, "Enable json pretty print.");
		data("http.mappers.json-sort-keys", "java.lang.Boolean", null, "Enable key sorting.");
		data("liquibase.change-log", "java.lang.String", "classpath:/db/changelog/db.changelog-master.yaml", "Change log configuration path.");
		data("liquibase.check-change-log-location", "java.lang.Boolean", "true", "Check the change log location exists.");
		data("liquibase.contexts", "java.lang.String", null, "Comma-separated list of runtime contexts to use.");
		data("liquibase.default-schema", "java.lang.String", null, "Default database schema.");
		data("liquibase.drop-first", "java.lang.Boolean", "false", "Drop the database schema first.");
		data("liquibase.enabled", "java.lang.Boolean", "true", "Enable liquibase support.");
		data("liquibase.password", "java.lang.String", null, "Login password of the database to migrate.");
		data("liquibase.url", "java.lang.String", null, "JDBC url of the database to migrate. If not set, the primary configured data source\n is used.");
		data("liquibase.user", "java.lang.String", null, "Login user of the database to migrate.");
		data("logging.config", "java.lang.String", null, "Location of the logging configuration file.");
		data("logging.file", "java.lang.String", null, "Log file name.");
		data("logging.level", "java.util.Map<java.lang.String,java.lang.Object>", null, "Log levels severity mapping. Use 'root' for the root logger.");
		data("logging.path", "java.lang.String", null, "Location of the log file.");
		data("multipart.file-size-threshold", "java.lang.String", "0", "Threshold after which files will be written to disk. Values can use the suffixed\n \"MB\" or \"KB\" to indicate a Megabyte or Kilobyte size.");
		data("multipart.location", "java.lang.String", null, "Intermediate location of uploaded files.");
		data("multipart.max-file-size", "java.lang.String", "1Mb", "Max file size. Values can use the suffixed \"MB\" or \"KB\" to indicate a Megabyte or\n Kilobyte size.");
		data("multipart.max-request-size", "java.lang.String", "10Mb", "Max request size. Values can use the suffixed \"MB\" or \"KB\" to indicate a Megabyte\n or Kilobyte size.");
		data("security.basic.enabled", "java.lang.Boolean", "true", "Enable basic authentication.");
		data("security.basic.path", "java.lang.String[]", "[Ljava.lang.Object;@7abd0056", "Comma-separated list of paths to secure.");
		data("security.basic.realm", "java.lang.String", "Spring", "HTTP basic realm name.");
		data("security.enable-csrf", "java.lang.Boolean", "false", "Enable Cross Site Request Forgery support.");
		data("security.filter-order", "java.lang.Integer", "0", "Security filter chain order.");
		data("security.headers.cache", "java.lang.Boolean", "false", "Enable cache control HTTP headers.");
		data("security.headers.content-type", "java.lang.Boolean", "false", "Enable \"X-Content-Type-Options\" header.");
		data("security.headers.frame", "java.lang.Boolean", "false", "Enable \"X-Frame-Options\" header.");
		data("security.headers.hsts", "org.springframework.boot.autoconfigure.security.SecurityProperties$Headers$HSTS", null, "HTTP Strict Transport Security (HSTS) mode (none, domain, all).");
		data("security.headers.xss", "java.lang.Boolean", "false", "Enable cross site scripting (XSS) protection.");
		data("security.ignored", "java.util.List<java.lang.String>", null, "Comma-separated list of paths to exclude from the default secured paths.");
		data("security.require-ssl", "java.lang.Boolean", "false", "Enable secure channel for all requests.");
		data("security.sessions", "org.springframework.security.config.http.SessionCreationPolicy", null, "Session creation policy (always, never, if_required, stateless).");
		data("security.user.name", "java.lang.String", "user", "Default user name.");
		data("security.user.password", "java.lang.String", null, "Password for the default user name.");
		data("security.user.role", "java.util.List<java.lang.String>", null, "Granted roles for the default user name.");
		data("server.address", "java.net.InetAddress", null, "Network address to which the server should bind to.");
		data("server.context-parameters", "java.util.Map<java.lang.String,java.lang.String>", null, "ServletContext parameters.");
		data("server.context-path", "java.lang.String", null, "Context path of the application.");
		data("server.port", "java.lang.Integer", null, "Server HTTP port.");
		data("server.servlet-path", "java.lang.String", "/", "Path of the main dispatcher servlet.");
		data("server.session-timeout", "java.lang.Integer", null, "Session timeout in seconds.");
		data("server.ssl.ciphers", "java.lang.String[]", null, null);
		data("server.ssl.client-auth", "org.springframework.boot.context.embedded.Ssl$ClientAuth", null, null);
		data("server.ssl.key-alias", "java.lang.String", null, null);
		data("server.ssl.key-password", "java.lang.String", null, null);
		data("server.ssl.key-store", "java.lang.String", null, null);
		data("server.ssl.key-store-password", "java.lang.String", null, null);
		data("server.ssl.key-store-provider", "java.lang.String", null, null);
		data("server.ssl.key-store-type", "java.lang.String", null, null);
		data("server.ssl.protocol", "java.lang.String", null, null);
		data("server.ssl.trust-store", "java.lang.String", null, null);
		data("server.ssl.trust-store-password", "java.lang.String", null, null);
		data("server.ssl.trust-store-provider", "java.lang.String", null, null);
		data("server.ssl.trust-store-type", "java.lang.String", null, null);
		data("server.tomcat.access-log-enabled", "java.lang.Boolean", "false", "Enable access log.");
		data("server.tomcat.access-log-pattern", "java.lang.String", null, "Format pattern for access logs.");
		data("server.tomcat.background-processor-delay", "java.lang.Integer", "30", "Delay in seconds between the invocation of backgroundProcess methods.");
		data("server.tomcat.basedir", "java.io.File", null, "Tomcat base directory. If not specified a temporary directory will be used.");
		data("server.tomcat.internal-proxies", "java.lang.String", "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|169\\.254\\.\\d{1,3}\\.\\d{1,3}|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "Regular expression that matches proxies that are to be trusted.");
		data("server.tomcat.max-http-header-size", "java.lang.Integer", "0", "Maximum size in bytes of the HTTP message header.");
		data("server.tomcat.max-threads", "java.lang.Integer", "0", "Maximum amount of worker threads.");
		data("server.tomcat.port-header", "java.lang.String", null, "Name of the HTTP header used to override the original port value.");
		data("server.tomcat.protocol-header", "java.lang.String", null, "Header that holds the incoming protocol, usually named \"X-Forwarded-Proto\".\n Configured as a RemoteIpValve only if remoteIpHeader is also set.");
		data("server.tomcat.remote-ip-header", "java.lang.String", null, "Name of the http header from which the remote ip is extracted. Configured as a\n RemoteIpValve only if remoteIpHeader is also set.");
		data("server.tomcat.uri-encoding", "java.lang.String", null, "Character encoding to use to decode the URI.");
		data("server.undertow.buffer-size", "java.lang.Integer", null, "Size of each buffer in bytes.");
		data("server.undertow.buffers-per-region", "java.lang.Integer", null, "Number of buffer per region.");
		data("server.undertow.direct-buffers", "java.lang.Boolean", null, null);
		data("server.undertow.io-threads", "java.lang.Integer", null, "Number of I/O threads to create for the worker.");
		data("server.undertow.worker-threads", "java.lang.Integer", null, "Number of worker threads.");
		data("spring.activemq.broker-url", "java.lang.String", null, "URL of the ActiveMQ broker. Auto-generated by default.");
		data("spring.activemq.in-memory", "java.lang.Boolean", "true", "Specify if the default broker URL should be in memory. Ignored if an explicit\n broker has been specified.");
		data("spring.activemq.password", "java.lang.String", null, "Login password of the broker.");
		data("spring.activemq.pooled", "java.lang.Boolean", "false", "Specify if a PooledConnectionFactory should be created instead of a regular\n ConnectionFactory.");
		data("spring.activemq.user", "java.lang.String", null, "Login user of the broker.");
		data("spring.aop.auto", "java.lang.Boolean", "true", "Add @EnableAspectJAutoProxy.");
		data("spring.aop.proxy-target-class", "java.lang.Boolean", "false", "Whether subclass-based (CGLIB) proxies are to be created (true) as opposed to standard Java interface-based proxies (false).");
		data("spring.application.index", "java.lang.Integer", null, "Application index.");
		data("spring.application.name", "java.lang.String", null, "Application name.");
		data("spring.batch.initializer.enabled", "java.lang.Boolean", "true", "Create the required batch tables on startup if necessary.");
		data("spring.batch.job.enabled", "java.lang.Boolean", "true", "Execute all Spring Batch jobs in the context on startup.");
		data("spring.batch.job.names", "java.lang.String", "", "Comma-separated list of job names to execute on startup. By default, all Jobs\n found in the context are executed.");
		data("spring.batch.schema", "java.lang.String", "classpath:org/springframework/batch/core/schema-@@platform@@.sql", "Path to the SQL file to use to initialize the database schema.");
		data("spring.config.location", "java.lang.String", null, "Config file locations.");
		data("spring.config.name", "java.lang.String", "application", "Config file name.");
		data("spring.dao.exceptiontranslation.enabled", "java.lang.Boolean", "true", "Enable the PersistenceExceptionTranslationPostProcessor.");
		data("spring.data.elasticsearch.cluster-name", "java.lang.String", "elasticsearch", "Elasticsearch cluster name.");
		data("spring.data.elasticsearch.cluster-nodes", "java.lang.String", null, "Comma-separated list of cluster node addresses. If not specified, starts a client\n node.");
		data("spring.data.elasticsearch.repositories.enabled", "java.lang.Boolean", "true", "Enable Elasticsearch repositories.");
		data("spring.data.jpa.repositories.enabled", "java.lang.Boolean", "true", "Enable JPA repositories.");
		data("spring.data.mongodb.authentication-database", "java.lang.String", null, "Authentication database name.");
		data("spring.data.mongodb.database", "java.lang.String", null, "Database name.");
		data("spring.data.mongodb.grid-fs-database", "java.lang.String", null, "GridFS database name.");
		data("spring.data.mongodb.host", "java.lang.String", null, "Mongo server host.");
		data("spring.data.mongodb.password", "char[]", null, "Login password of the mongo server.");
		data("spring.data.mongodb.port", "java.lang.Integer", null, "Mongo server port.");
		data("spring.data.mongodb.repositories.enabled", "java.lang.Boolean", "true", "Enable Mongo repositories.");
		data("spring.data.mongodb.uri", "java.lang.String", "mongodb://localhost/test", "Mmongo database URI. When set, host and port are ignored.");
		data("spring.data.mongodb.username", "java.lang.String", null, "Login user of the mongo server.");
		data("spring.data.rest.base-uri", "java.net.URI", null, null);
		data("spring.data.rest.default-page-size", "java.lang.Integer", null, null);
		data("spring.data.rest.limit-param-name", "java.lang.String", null, null);
		data("spring.data.rest.max-page-size", "java.lang.Integer", null, null);
		data("spring.data.rest.page-param-name", "java.lang.String", null, null);
		data("spring.data.rest.return-body-on-create", "java.lang.Boolean", null, null);
		data("spring.data.rest.return-body-on-update", "java.lang.Boolean", null, null);
		data("spring.data.rest.sort-param-name", "java.lang.String", null, null);
		data("spring.data.solr.host", "java.lang.String", "http://127.0.0.1:8983/solr", "Solr host. Ignored if \"zk-host\" is set.");
		data("spring.data.solr.repositories.enabled", "java.lang.Boolean", "true", "Enable Solr repositories.");
		data("spring.data.solr.zk-host", "java.lang.String", null, "ZooKeeper host address in the form HOST:PORT.");
		data("spring.datasource.abandon-when-percentage-full", "java.lang.Integer", null, null);
		data("spring.datasource.access-to-underlying-connection-allowed", "java.lang.Boolean", null, null);
		data("spring.datasource.alternate-username-allowed", "java.lang.Boolean", null, null);
		data("spring.datasource.auto-commit", "java.lang.Boolean", null, null);
		data("spring.datasource.catalog", "java.lang.String", null, null);
		data("spring.datasource.commit-on-return", "java.lang.Boolean", null, null);
		data("spring.datasource.connection-customizer-class-name", "java.lang.String", null, null);
		data("spring.datasource.connection-init-sql", "java.lang.String", null, null);
		data("spring.datasource.connection-init-sqls", "java.util.Collection", null, null);
		data("spring.datasource.connection-properties", "java.lang.String", null, null);
		data("spring.datasource.connection-test-query", "java.lang.String", null, null);
		data("spring.datasource.connection-timeout", "java.lang.Long", null, null);
		data("spring.datasource.continue-on-error", "java.lang.Boolean", "false", "Do not stop if an error occurs while initializing the database.");
		data("spring.datasource.data", "java.lang.String", null, "Data (DML) script resource reference.");
		data("spring.datasource.data-source-class-name", "java.lang.String", null, null);
		data("spring.datasource.data-source", "java.lang.Object", null, null);
		data("spring.datasource.data-source-j-n-d-i", "java.lang.String", null, null);
		data("spring.datasource.data-source-properties", "java.util.Properties", null, null);
		data("spring.datasource.db-properties", "java.util.Properties", null, null);
		data("spring.datasource.default-auto-commit", "java.lang.Boolean", null, null);
		data("spring.datasource.default-catalog", "java.lang.String", null, null);
		data("spring.datasource.default-read-only", "java.lang.Boolean", null, null);
		data("spring.datasource.default-transaction-isolation", "java.lang.Integer", null, null);
		data("spring.datasource.driver-class-name", "java.lang.String", null, "Fully qualified name of the JDBC driver. Auto-detected based on the URL by default.");
		data("spring.datasource.fair-queue", "java.lang.Boolean", null, null);
		data("spring.datasource.idle-timeout", "java.lang.Long", null, null);
		data("spring.datasource.ignore-exception-on-pre-load", "java.lang.Boolean", null, null);
		data("spring.datasource.initialization-fail-fast", "java.lang.Boolean", null, null);
		data("spring.datasource.initialize", "java.lang.Boolean", "true", "Populate the database using 'data.sql'.");
		data("spring.datasource.initial-size", "java.lang.Integer", null, null);
		data("spring.datasource.init-s-q-l", "java.lang.String", null, null);
		data("spring.datasource.isolate-internal-queries", "java.lang.Boolean", null, null);
		data("spring.datasource.jdbc4-connection-test", "java.lang.Boolean", null, null);
		data("spring.datasource.jdbc-interceptors", "java.lang.String", null, null);
		data("spring.datasource.jdbc-url", "java.lang.String", null, null);
		data("spring.datasource.jmx-enabled", "java.lang.Boolean", "false", "Enable JMX support (if provided by the underlying pool).");
		data("spring.datasource.jndi-name", "java.lang.String", null, "JNDI location of the datasource. Class, url, username & password are ignored when\n set.");
		data("spring.datasource.leak-detection-threshold", "java.lang.Long", null, null);
		data("spring.datasource.log-abandoned", "java.lang.Boolean", null, null);
		data("spring.datasource.login-timeout", "java.lang.Integer", null, null);
		data("spring.datasource.log-validation-errors", "java.lang.Boolean", null, null);
		data("spring.datasource.max-active", "java.lang.Integer", null, null);
		data("spring.datasource.max-age", "java.lang.Long", null, null);
		data("spring.datasource.max-idle", "java.lang.Integer", null, null);
		data("spring.datasource.maximum-pool-size", "java.lang.Integer", null, null);
		data("spring.datasource.max-lifetime", "java.lang.Long", null, null);
		data("spring.datasource.max-open-prepared-statements", "java.lang.Integer", null, null);
		data("spring.datasource.max-wait", "java.lang.Integer", null, null);
		data("spring.datasource.metric-registry", "java.lang.Object", null, null);
		data("spring.datasource.min-evictable-idle-time-millis", "java.lang.Integer", null, null);
		data("spring.datasource.min-idle", "java.lang.Integer", null, null);
		data("spring.datasource.minimum-idle", "java.lang.Integer", null, null);
		data("spring.datasource.name", "java.lang.String", null, null);
		data("spring.datasource.num-tests-per-eviction-run", "java.lang.Integer", null, null);
		data("spring.datasource.password", "java.lang.String", null, "Login password of the database.");
		data("spring.datasource.platform", "java.lang.String", "all", "Platform to use in the schema resource (schema-${platform}.sql).");
		data("spring.datasource.pool-name", "java.lang.String", null, null);
		data("spring.datasource.pool-prepared-statements", "java.lang.Boolean", null, null);
		data("spring.datasource.propagate-interrupt-state", "java.lang.Boolean", null, null);
		data("spring.datasource.read-only", "java.lang.Boolean", null, null);
		data("spring.datasource.register-mbeans", "java.lang.Boolean", null, null);
		data("spring.datasource.remove-abandoned", "java.lang.Boolean", null, null);
		data("spring.datasource.remove-abandoned-timeout", "java.lang.Integer", null, null);
		data("spring.datasource.rollback-on-return", "java.lang.Boolean", null, null);
		data("spring.datasource.schema", "java.lang.String", null, "Schema (DDL) script resource reference.");
		data("spring.datasource.separator", "java.lang.String", ";", "Statement separator in SQL initialization scripts.");
		data("spring.datasource.sql-script-encoding", "java.lang.String", null, "SQL scripts encoding.");
		data("spring.datasource.suspect-timeout", "java.lang.Integer", null, null);
		data("spring.datasource.test-on-borrow", "java.lang.Boolean", null, null);
		data("spring.datasource.test-on-connect", "java.lang.Boolean", null, null);
		data("spring.datasource.test-on-return", "java.lang.Boolean", null, null);
		data("spring.datasource.test-while-idle", "java.lang.Boolean", null, null);
		data("spring.datasource.time-between-eviction-runs-millis", "java.lang.Integer", null, null);
		data("spring.datasource.transaction-isolation", "java.lang.String", null, null);
		data("spring.datasource.url", "java.lang.String", null, "JDBC url of the database.");
		data("spring.datasource.use-disposable-connection-facade", "java.lang.Boolean", null, null);
		data("spring.datasource.use-equals", "java.lang.Boolean", null, null);
		data("spring.datasource.use-lock", "java.lang.Boolean", null, null);
		data("spring.datasource.username", "java.lang.String", null, "Login user of the database.");
		data("spring.datasource.validation-interval", "java.lang.Long", null, null);
		data("spring.datasource.validation-query", "java.lang.String", null, null);
		data("spring.datasource.validation-query-timeout", "java.lang.Integer", null, null);
		data("spring.datasource.validator-class-name", "java.lang.String", null, null);
		data("spring.datasource.xa.data-source-class-name", "java.lang.String", null, "XA datasource fully qualified name.");
		data("spring.datasource.xa.properties", "java.util.Map<java.lang.String,java.lang.String>", null, "Properties to pass to the XA data source.");
		data("spring.freemarker.allow-request-override", "java.lang.Boolean", null, "Set whether HttpServletRequest attributes are allowed to override (hide) controller\n generated model attributes of the same name.");
		data("spring.freemarker.cache", "java.lang.Boolean", null, "Enable template caching.");
		data("spring.freemarker.char-set", "java.lang.String", null, null);
		data("spring.freemarker.charset", "java.lang.String", null, "Template encoding.");
		data("spring.freemarker.check-template-location", "java.lang.Boolean", null, "Check that the templates location exists.");
		data("spring.freemarker.content-type", "java.lang.String", null, "Content-Type value.");
		data("spring.freemarker.enabled", "java.lang.Boolean", null, "Enable MVC view resolution for this technology.");
		data("spring.freemarker.expose-request-attributes", "java.lang.Boolean", null, "Set whether all request attributes should be added to the model prior to merging\n with the template.");
		data("spring.freemarker.expose-session-attributes", "java.lang.Boolean", null, "Set whether all HttpSession attributes should be added to the model prior to\n merging with the template.");
		data("spring.freemarker.expose-spring-macro-helpers", "java.lang.Boolean", null, "Set whether to expose a RequestContext for use by Spring's macro library, under the\n name \"springMacroRequestContext\".");
		data("spring.freemarker.prefix", "java.lang.String", null, "Prefix that gets prepended to view names when building a URL.");
		data("spring.freemarker.request-context-attribute", "java.lang.String", null, "Name of the RequestContext attribute for all views.");
		data("spring.freemarker.settings", "java.util.Map<java.lang.String,java.lang.String>", null, "Well-known FreeMarker keys which will be passed to FreeMarker's Configuration.");
		data("spring.freemarker.suffix", "java.lang.String", null, "Suffix that gets appended to view names when building a URL.");
		data("spring.freemarker.template-loader-path", "java.lang.String[]", new String[] {"snuzzle" ,"buggles"}, "Comma-separated list of template paths.");
		data("spring.freemarker.view-names", "java.lang.String[]", null, "White list of view names that can be resolved.");
		data("spring.groovy.template.cache", "java.lang.Boolean", null, "Enable template caching.");
		data("spring.groovy.template.char-set", "java.lang.String", null, null);
		data("spring.groovy.template.charset", "java.lang.String", null, "Template encoding.");
		data("spring.groovy.template.check-template-location", "java.lang.Boolean", null, "Check that the templates location exists.");
		data("spring.groovy.template.configuration.auto-escape", "java.lang.Boolean", null, null);
		data("spring.groovy.template.configuration.auto-indent", "java.lang.Boolean", null, null);
		data("spring.groovy.template.configuration.auto-indent-string", "java.lang.String", null, null);
		data("spring.groovy.template.configuration.auto-new-line", "java.lang.Boolean", null, null);
		data("spring.groovy.template.configuration.base-template-class", "java.lang.Class<? extends groovy.text.markup.BaseTemplate>", null, null);
		data("spring.groovy.template.configuration.cache-templates", "java.lang.Boolean", null, null);
		data("spring.groovy.template.configuration.declaration-encoding", "java.lang.String", null, null);
		data("spring.groovy.template.configuration.expand-empty-elements", "java.lang.Boolean", null, null);
		data("spring.groovy.template.configuration", "java.util.Map<java.lang.String,java.lang.Object>", null, "Configuration to pass to TemplateConfiguration.");
		data("spring.groovy.template.configuration.locale", "java.util.Locale", null, null);
		data("spring.groovy.template.configuration.new-line-string", "java.lang.String", null, null);
		data("spring.groovy.template.configuration.resource-loader-path", "java.lang.String", null, null);
		data("spring.groovy.template.configuration.use-double-quotes", "java.lang.Boolean", null, null);
		data("spring.groovy.template.content-type", "java.lang.String", null, "Content-Type value.");
		data("spring.groovy.template.enabled", "java.lang.Boolean", null, "Enable MVC view resolution for this technology.");
		data("spring.groovy.template.prefix", "java.lang.String", "classpath:/templates/", "Prefix that gets prepended to view names when building a URL.");
		data("spring.groovy.template.suffix", "java.lang.String", ".tpl", "Suffix that gets appended to view names when building a URL.");
		data("spring.groovy.template.view-names", "java.lang.String[]", null, "White list of view names that can be resolved.");
		data("spring.hornetq.embedded.cluster-password", "java.lang.String", null, "Cluster password. Randomly generated on startup by default");
		data("spring.hornetq.embedded.data-directory", "java.lang.String", null, "Journal file directory. Not necessary if persistence is turned off.");
		data("spring.hornetq.embedded.enabled", "java.lang.Boolean", "true", "Enable embedded mode if the HornetQ server APIs are available.");
		data("spring.hornetq.embedded.persistent", "java.lang.Boolean", "false", "Enable persistent store.");
		data("spring.hornetq.embedded.queues", "java.lang.String[]", "[Ljava.lang.Object;@2f5ce114", "Comma-separate list of queues to create on startup.");
		data("spring.hornetq.embedded.server-id", "java.lang.Integer", "0", "Server id. By default, an auto-incremented counter is used.");
		data("spring.hornetq.embedded.topics", "java.lang.String[]", "[Ljava.lang.Object;@6272137a", "Comma-separate list of topics to create on startup.");
		data("spring.hornetq.host", "java.lang.String", "localhost", "HornetQ broker host.");
		data("spring.hornetq.mode", "org.springframework.boot.autoconfigure.jms.hornetq.HornetQMode", null, "HornetQ deployment mode, auto-detected by default. Can be explicitly set to\n \"native\" or \"embedded\".");
		data("spring.hornetq.port", "java.lang.Integer", "5445", "HornetQ broker port.");
		data("spring.http.encoding.charset", "java.nio.charset.Charset", null, "Charset of HTTP requests and responses. Added to the \"Content-Type\" header if not\n set explicitly.");
		data("spring.http.encoding.enabled", "java.lang.Boolean", "true", "Enable http encoding support.");
		data("spring.http.encoding.force", "java.lang.Boolean", "true", "Force the encoding to the configured charset on HTTP requests and responses.");
		data("spring.jackson.date-format", "java.lang.String", null, "Date format string (yyyy-MM-dd HH:mm:ss), or a fully-qualified date format class\n name.");
		data("spring.jackson.deserialization", "java.util.Map<com.fasterxml.jackson.databind.DeserializationFeature,java.lang.Boolean>", null, "Jackson on/off features that affect the way Java objects are deserialized.");
		data("spring.jackson.generator", "java.util.Map<com.fasterxml.jackson.core.JsonGenerator.Feature,java.lang.Boolean>", null, "Jackson on/off features for generators.");
		data("spring.jackson.mapper", "java.util.Map<com.fasterxml.jackson.databind.MapperFeature,java.lang.Boolean>", null, "Jackson general purpose on/off features.");
		data("spring.jackson.parser", "java.util.Map<com.fasterxml.jackson.core.JsonParser.Feature,java.lang.Boolean>", null, "Jackson on/off features for parsers.");
		data("spring.jackson.property-naming-strategy", "java.lang.String", null, "One of the constants on Jackson's PropertyNamingStrategy\n (CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES). Can also be a fully-qualified class\n name of a PropertyNamingStrategy subclass.");
		data("spring.jackson.serialization", "java.util.Map<com.fasterxml.jackson.databind.SerializationFeature,java.lang.Boolean>", null, "Jackson on/off features that affect the way Java objects are serialized.");
		data("spring.jersey.filter.order", "java.lang.Integer", "0", "Jersey filter chain order.");
		data("spring.jersey.init", "java.util.Map<java.lang.String,java.lang.String>", null, "Init parameters to pass to Jersey.");
		data("spring.jersey.type", "org.springframework.boot.autoconfigure.jersey.JerseyProperties$Type", null, "Jersey integration type. Can be either \"servlet\" or \"filter\".");
		data("spring.jms.jndi-name", "java.lang.String", null, "Connection factory JNDI name. When set, takes precedence to others connection\n factory auto-configurations.");
		data("spring.jms.pub-sub-domain", "java.lang.Boolean", "false", "Specify if the default destination type is topic.");
		data("spring.jmx.enabled", "java.lang.Boolean", "true", "Expose management beans to the JMX domain.");
		data("spring.jpa.database", "org.springframework.orm.jpa.vendor.Database", null, "Target database to operate on, auto-detected by default. Can be alternatively set\n using the \"databasePlatform\" property.");
		data("spring.jpa.database-platform", "java.lang.String", null, "Name of the target database to operate on, auto-detected by default. Can be\n alternatively set using the \"Database\" enum.");
		data("spring.jpa.generate-ddl", "java.lang.Boolean", "false", "Initialize the schema on startup.");
		data("spring.jpa.hibernate.ddl-auto", "java.lang.String", null, "DDL mode (\"none\", \"validate\", \"update\", \"create\", \"create-drop\"). This is\n actually a shortcut for the \"hibernate.hbm2ddl.auto\" property. Default to\n \"create-drop\" when using an embedded database, \"none\" otherwise.");
		data("spring.jpa.hibernate.naming-strategy", "java.lang.Class<?>", null, "Naming strategy fully qualified name.");
		data("spring.jpa.open-in-view", "java.lang.Boolean", "true", "Register OpenEntityManagerInViewInterceptor. Binds a JPA EntityManager to the thread for the entire processing of the request.");
		data("spring.jpa.properties", "java.util.Map<java.lang.String,java.lang.String>", null, "Additional native properties to set on the JPA provider.");
		data("spring.jpa.show-sql", "java.lang.Boolean", "false", "Enable logging of SQL statements.");
		data("spring.jta.allow-multiple-lrc", "java.lang.Boolean", null, null);
		data("spring.jta.asynchronous2-pc", "java.lang.Boolean", null, null);
		data("spring.jta.background-recovery-interval", "java.lang.Integer", null, null);
		data("spring.jta.background-recovery-interval-seconds", "java.lang.Integer", null, null);
		data("spring.jta.current-node-only-recovery", "java.lang.Boolean", null, null);
		data("spring.jta.debug-zero-resource-transaction", "java.lang.Boolean", null, null);
		data("spring.jta.default-transaction-timeout", "java.lang.Integer", null, null);
		data("spring.jta.disable-jmx", "java.lang.Boolean", null, null);
		data("spring.jta.enabled", "java.lang.Boolean", "true", "Enable JTA support.");
		data("spring.jta.exception-analyzer", "java.lang.String", null, null);
		data("spring.jta.filter-log-status", "java.lang.Boolean", null, null);
		data("spring.jta.force-batching-enabled", "java.lang.Boolean", null, null);
		data("spring.jta.forced-write-enabled", "java.lang.Boolean", null, null);
		data("spring.jta.graceful-shutdown-interval", "java.lang.Integer", null, null);
		data("spring.jta.jndi-transaction-synchronization-registry-name", "java.lang.String", null, null);
		data("spring.jta.jndi-user-transaction-name", "java.lang.String", null, null);
		data("spring.jta.journal", "java.lang.String", null, null);
		data("spring.jta.log-dir", "java.lang.String", null, "Transaction logs directory.");
		data("spring.jta.log-part1-filename", "java.lang.String", null, null);
		data("spring.jta.log-part2-filename", "java.lang.String", null, null);
		data("spring.jta.max-log-size-in-mb", "java.lang.Integer", null, null);
		data("spring.jta.resource-configuration-filename", "java.lang.String", null, null);
		data("spring.jta.server-id", "java.lang.String", null, null);
		data("spring.jta.skip-corrupted-logs", "java.lang.Boolean", null, null);
		data("spring.jta.transaction-manager-id", "java.lang.String", null, "Transaction manager unique identifier.");
		data("spring.jta.warn-about-zero-resource-transaction", "java.lang.Boolean", null, null);
		data("spring.mail.default-encoding", "java.lang.String", "UTF-8", "Default MimeMessage encoding.");
		data("spring.mail.host", "java.lang.String", null, "SMTP server host.");
		data("spring.mail.password", "java.lang.String", null, "Login password of the SMTP server.");
		data("spring.mail.port", "java.lang.Integer", null, "SMTP server port.");
		data("spring.mail.properties", "java.util.Map<java.lang.String,java.lang.String>", null, "Additional JavaMail session properties.");
		data("spring.mail.username", "java.lang.String", null, "Login user of the SMTP server.");
		data("spring.main.show-banner", "java.lang.Boolean", "true", "Display the banner when the application runs.");
		data("spring.main.sources", "java.util.Set<java.lang.Object>", null, "Sources (class name, package name or XML resource location) used to create the ApplicationContext.");
		data("spring.main.web-environment", "java.lang.Boolean", null, "Run the application in a web environment (auto-detected by default).");
		data("spring.mandatory-file-encoding", "java.lang.String", null, "Expected character encoding the application must use.");
		data("spring.messages.basename", "java.lang.String", "messages", "Comma-separated list of basenames, each following the ResourceBundle convention.\n Essentially a fully-qualified classpath location. If it doesn't contain a package\n qualifier (such as \"org.mypackage\"), it will be resolved from the classpath root.");
		data("spring.messages.cache-seconds", "java.lang.Integer", "-1", "Loaded resource bundle files cache expiration, in seconds. When set to -1, bundles\n are cached forever.");
		data("spring.messages.encoding", "java.lang.String", "utf-8", "Message bundles encoding.");
		data("spring.mobile.devicedelegatingviewresolver.enabled", "java.lang.Boolean", "false", "Enable device view resolver.");
		data("spring.mobile.devicedelegatingviewresolver.mobile-prefix", "java.lang.String", "mobile/", "Prefix that gets prepended to view names for mobile devices.");
		data("spring.mobile.devicedelegatingviewresolver.mobile-suffix", "java.lang.String", "", "Suffix that gets appended to view names for mobile devices.");
		data("spring.mobile.devicedelegatingviewresolver.normal-prefix", "java.lang.String", "", "Prefix that gets prepended to view names for normal devices.");
		data("spring.mobile.devicedelegatingviewresolver.normal-suffix", "java.lang.String", "", "Suffix that gets appended to view names for normal devices.");
		data("spring.mobile.devicedelegatingviewresolver.tablet-prefix", "java.lang.String", "tablet/", "Prefix that gets prepended to view names for tablet devices.");
		data("spring.mobile.devicedelegatingviewresolver.tablet-suffix", "java.lang.String", "", "Suffix that gets appended to view names for tablet devices.");
		data("spring.mobile.sitepreference.enabled", "java.lang.Boolean", "true", "Enable SitePreferenceHandler.");
		data("spring.mvc.date-format", "java.lang.String", null, "Date format to use (e.g. dd/MM/yyyy)");
		data("spring.mvc.ignore-default-model-on-redirect", "java.lang.Boolean", "true", "If the the content of the \"default\" model should be ignored during redirect\n scenarios.");
		data("spring.mvc.locale", "java.lang.String", null, "Locale to use.");
		data("spring.mvc.message-codes-resolver-format", "org.springframework.validation.DefaultMessageCodesResolver$Format", null, "Formatting strategy for message codes (PREFIX_ERROR_CODE, POSTFIX_ERROR_CODE).");
		data("spring.profiles.active", "java.lang.String", null, "Comma-separated list of active profiles. Can be overridden by a command line switch.");
		data("spring.profiles.include", "java.lang.String", null, "Unconditionally activate the specified comma separated profiles.");
		data("spring.rabbitmq.addresses", "java.lang.String", null, "Comma-separated list of addresses to which the client should connect to.");
		data("spring.rabbitmq.dynamic", "java.lang.Boolean", "true", "Create an AmqpAdmin bean.");
		data("spring.rabbitmq.host", "java.lang.String", "localhost", "RabbitMQ host.");
		data("spring.rabbitmq.password", "java.lang.String", null, "Login to authenticate against the broker.");
		data("spring.rabbitmq.port", "java.lang.Integer", "5672", "RabbitMQ port.");
		data("spring.rabbitmq.username", "java.lang.String", null, "Login user to authenticate to the broker.");
		data("spring.rabbitmq.virtual-host", "java.lang.String", null, "Virtual host to use when connecting to the broker.");
		data("spring.redis.database", "java.lang.Integer", "0", "Database index used by the connection factory.");
		data("spring.redis.host", "java.lang.String", "localhost", "Redis server host.");
		data("spring.redis.password", "java.lang.String", null, "Login password of the redis server.");
		data("spring.redis.pool.max-active", "java.lang.Integer", "8", "Max number of connections that can be allocated by the pool at a given time.\n Use a negative value for no limit.");
		data("spring.redis.pool.max-idle", "java.lang.Integer", "8", "Max number of \"idle\" connections in the pool. Use a negative value to indicate\n an unlimited number of idle connections.");
		data("spring.redis.pool.max-wait", "java.lang.Integer", "-1", "Maximum amount of time (in milliseconds) a connection allocation should block\n before throwing an exception when the pool is exhausted. Use a negative value\n to block indefinitely.");
		data("spring.redis.pool.min-idle", "java.lang.Integer", "0", "Target for the minimum number of idle connections to maintain in the pool. This\n setting only has an effect if it is positive.");
		data("spring.redis.port", "java.lang.Integer", "6379", "Redis server port.");
		data("spring.redis.sentinel.master", "java.lang.String", null, "Name of Redis server.");
		data("spring.redis.sentinel.nodes", "java.lang.String", null, "Comma-separated list of host:port pairs.");
		data("spring.resources.add-mappings", "java.lang.Boolean", "true", "Enable default resource handling.");
		data("spring.resources.cache-period", "java.lang.Integer", null, "Cache period for the resources served by the resource handler, in seconds.");
		data("spring.social.auto-connection-views", "java.lang.Boolean", "false", "Enable the connection status view for supported providers.");
		data("spring.social.facebook.app-id", "java.lang.String", null, "Application id.");
		data("spring.social.facebook.app-secret", "java.lang.String", null, "Application secret.");
		data("spring.social.linkedin.app-id", "java.lang.String", null, "Application id.");
		data("spring.social.linkedin.app-secret", "java.lang.String", null, "Application secret.");
		data("spring.social.twitter.app-id", "java.lang.String", null, "Application id.");
		data("spring.social.twitter.app-secret", "java.lang.String", null, "Application secret.");
		data("spring.thymeleaf.cache", "java.lang.Boolean", "true", "Enable template caching.");
		data("spring.thymeleaf.check-template-location", "java.lang.Boolean", "true", "Check that the templates location exists.");
		data("spring.thymeleaf.content-type", "java.lang.String", "text/html", "Content-Type value.");
		data("spring.thymeleaf.enabled", "java.lang.Boolean", "true", "Enable MVC Thymeleaf view resolution.");
		data("spring.thymeleaf.encoding", "java.lang.String", "UTF-8", "Template encoding.");
		data("spring.thymeleaf.excluded-view-names", "java.lang.String[]", null, "Comma-separated list of view names that should be excluded from resolution.");
		data("spring.thymeleaf.mode", "java.lang.String", "HTML5", "Template mode to be applied to templates. See also StandardTemplateModeHandlers.");
		data("spring.thymeleaf.prefix", "java.lang.String", "classpath:/templates/", "Prefix that gets prepended to view names when building a URL.");
		data("spring.thymeleaf.suffix", "java.lang.String", ".html", "Suffix that gets appended to view names when building a URL.");
		data("spring.thymeleaf.view-names", "java.lang.String[]", null, "Comma-separated list of view names that can be resolved.");
		data("spring.velocity.allow-request-override", "java.lang.Boolean", null, "Set whether HttpServletRequest attributes are allowed to override (hide) controller\n generated model attributes of the same name.");
		data("spring.velocity.cache", "java.lang.Boolean", null, "Enable template caching.");
		data("spring.velocity.char-set", "java.lang.String", null, null);
		data("spring.velocity.charset", "java.lang.String", null, "Template encoding.");
		data("spring.velocity.check-template-location", "java.lang.Boolean", null, "Check that the templates location exists.");
		data("spring.velocity.content-type", "java.lang.String", null, "Content-Type value.");
		data("spring.velocity.date-tool-attribute", "java.lang.String", null, "Name of the DateTool helper object to expose in the Velocity context of the view.");
		data("spring.velocity.enabled", "java.lang.Boolean", null, "Enable MVC view resolution for this technology.");
		data("spring.velocity.expose-request-attributes", "java.lang.Boolean", null, "Set whether all request attributes should be added to the model prior to merging\n with the template.");
		data("spring.velocity.expose-session-attributes", "java.lang.Boolean", null, "Set whether all HttpSession attributes should be added to the model prior to\n merging with the template.");
		data("spring.velocity.expose-spring-macro-helpers", "java.lang.Boolean", null, "Set whether to expose a RequestContext for use by Spring's macro library, under the\n name \"springMacroRequestContext\".");
		data("spring.velocity.number-tool-attribute", "java.lang.String", null, "Name of the NumberTool helper object to expose in the Velocity context of the view.");
		data("spring.velocity.prefer-file-system-access", "java.lang.Boolean", "true", "Prefer file system access for template loading. File system access enables hot\n detection of template changes.");
		data("spring.velocity.prefix", "java.lang.String", null, "Prefix that gets prepended to view names when building a URL.");
		data("spring.velocity.properties", "java.util.Map<java.lang.String,java.lang.String>", null, "Additional velocity properties.");
		data("spring.velocity.request-context-attribute", "java.lang.String", null, "Name of the RequestContext attribute for all views.");
		data("spring.velocity.resource-loader-path", "java.lang.String", "classpath:/templates/", "Template path.");
		data("spring.velocity.suffix", "java.lang.String", null, "Suffix that gets appended to view names when building a URL.");
		data("spring.velocity.toolbox-config-location", "java.lang.String", null, "Velocity Toolbox config location, for example \"/WEB-INF/toolbox.xml\". Automatically\n loads a Velocity Tools toolbox definition file and expose all defined tools in the\n specified scopes.");
		data("spring.velocity.view-names", "java.lang.String[]", null, "White list of view names that can be resolved.");
		data("spring.view.prefix", "java.lang.String", null, "Spring MVC view prefix.");
		data("spring.view.suffix", "java.lang.String", null, "Spring MVC view suffix.");
	}

	protected IProject createPredefinedMavenProject(final String projectName) throws Exception {
		final String bundleName = getBundleName();
		return BootProjectTestHarness.createPredefinedMavenProject(projectName, bundleName);
	}

	public List<ReconcileProblem> reconcile(MockEditor editor) {
		IReconcileEngine reconciler = createReconcileEngine();
		MockProblemCollector problems=new MockProblemCollector(ignoredTypes);
		reconciler.reconcile(editor.document, problems, new NullProgressMonitor());
		return problems.getAllProblems();
	}

	public void ignoreProblem(ProblemType type) {
		ignoredTypes.add(type);
	}

	protected abstract IReconcileEngine createReconcileEngine();

	/**
	 * Get a problem that covers the given text in the editor. Throws exception
	 * if no matching problem is found.
	 */
	public ReconcileProblem assertProblem(MockEditor editor, String coveredText) throws Exception {
		List<ReconcileProblem> problems = reconcile(editor);
		for (ReconcileProblem p : problems) {
			String c = editor.getText(p.getOffset(), p.getLength());
			if (c.equals(coveredText)) {
				return p;
			}
		}
		fail("No problem found covering the text '"+coveredText+"' in: \n"
				+ problemSumary(editor, problems)
		);
		return null; //unreachable but compiler doesn't know
	}

	public ICompletionProposal assertFirstQuickfix(MockEditor editor, ReconcileProblem problem, String expectLabel) {
		assertTrue("The problem is not Fixable", problem instanceof FixableProblem);
		FixableProblem fixable = (FixableProblem) problem;
		List<ICompletionProposal> fixes = fixable.getQuickfixes(getQuickfixContext(editor));
		assertFalse("There are no quickfixes", fixes.isEmpty());
		ICompletionProposal fix = fixes.get(0);
		assertEquals(expectLabel, fix.getDisplayString());
		return fix;
	}

	/**
	 * Check that a 'expectedProblems' are found by the reconciler. Expected problems are
	 * specified by string of the form "${badSnippet}|${messageSnippet}". The badSnippet
	 * is the text expected to be covered by the marker's region and the message snippet must
	 * be found in the error marker's message.
	 * <p>
	 * The expected problems are matched one-to-one in the order given (so markers in the
	 * editor must appear in the expected order for the assert to pass).
	 *
	 * @param editor
	 * @param expectedProblems
	 * @throws BadLocationException
	 */
	public List<ReconcileProblem> assertProblems(MockEditor editor, String... expectedProblems)
			throws BadLocationException {
		List<ReconcileProblem> actualProblems = reconcile(editor);
		Collections.sort(actualProblems, PROBLEM_COMPARATOR);
		String bad = null;
		if (actualProblems.size()!=expectedProblems.length) {
			bad = "Wrong number of problems (expecting "+expectedProblems.length+" but found "+actualProblems.size()+")";
		} else {
			for (int i = 0; i < expectedProblems.length; i++) {
				if (!matchProblem(editor, actualProblems.get(i), expectedProblems[i])) {
					bad = "First mismatch at index "+i+": "+expectedProblems[i]+"\n";
					break;
				}
			}
		}
		if (bad!=null) {
			fail(bad+problemSumary(editor, actualProblems));
		}
		return actualProblems;
	}
	private String problemSumary(MockEditor editor, List<ReconcileProblem> actualProblems) throws BadLocationException {
		StringBuilder buf = new StringBuilder();
		for (ReconcileProblem p : actualProblems) {
			buf.append("\n----------------------\n");

			String snippet = editor.getText(p.getOffset(), p.getLength());
			buf.append("("+p.getOffset()+", "+p.getLength()+")["+snippet+"]:\n");
			buf.append("   "+p.getMessage());
		}
		return buf.toString();
	}

	private boolean matchProblem(MockEditor editor, ReconcileProblem problem, String expect) {
		String[] parts = expect.split("\\|");
		assertEquals(2, parts.length);
		String badSnippet = parts[0];
		String messageSnippet = parts[1];
		try {
			boolean spaceSensitive = badSnippet.trim().length()<badSnippet.length();
			String actualBadSnippet = editor.getText(problem.getOffset(), problem.getLength());
			if (!spaceSensitive) {
				actualBadSnippet = actualBadSnippet.trim();
			}
			return actualBadSnippet.equals(badSnippet)
					&& problem.getMessage().contains(messageSnippet);
		} catch (BadLocationException e) {
			return false;
		}
	}

	public ICompletionProposal getFirstCompletion(MockEditor editor) throws Exception {
		return getCompletions(editor)[0];
	}

	public abstract ICompletionProposal[] getCompletions(MockEditor editor) throws Exception;

	/**
	 * Simulates applying the first completion to a text buffer and checks the result.
	 */
	public void assertCompletion(String textBefore, String expectTextAfter) throws Exception {
		MockEditor editor = newEditor(textBefore);
		ICompletionProposal completion = getFirstCompletion(editor);
		editor.apply(completion);
		assertEquals(StringUtil.trimEnd(expectTextAfter), StringUtil.trimEnd(editor.getText()));
	}


	/**
	 * Checks that completions contains a completion with a given display string (and check that
	 * it applies as expected).
	 */
	public void assertCompletionWithLabel(String textBefore, String expectLabel, String expectTextAfter) throws Exception {
		MockEditor editor = newEditor(textBefore);
		ICompletionProposal[] completions = getCompletions(editor);
		ICompletionProposal completion = assertCompletionWithLabel(expectLabel, completions);
		editor.apply(completion);
		assertEquals(expectTextAfter, editor.getText());
	}

	/**
	 * Checks that completions contains a completion with a given display string and that that completion
	 * has a info hover that contains a given snippet of text.
	 */
	public void assertCompletionWithInfoHover(String editorText, String expectLabel, String expectInfoSnippet) throws Exception {
		MockEditor editor = newEditor(editorText);
		ICompletionProposal[] completions = getCompletions(editor);
		ICompletionProposal completion = assertCompletionWithLabel(expectLabel, completions);

		String infoText = completion.getAdditionalProposalInfo();
		assertNotNull("No hover info", infoText);
		assertContains(expectInfoSnippet, infoText);
	}

	private ICompletionProposal assertCompletionWithLabel(String expectLabel, ICompletionProposal[] completions) {
		StringBuilder found = new StringBuilder();
		for (ICompletionProposal c : completions) {
			String actualLabel = c.getDisplayString();
			found.append(actualLabel+"\n");
			if (actualLabel.equals(expectLabel)) {
				return c;
			}
		}
		fail("No completion found with label '"+expectLabel+"' in:\n"+found);
		return null; //unreachable, but compiler doesn't know that.
	}

	/**
	 * Checks that applying completions to a given 'textBefore' editor content produces the
	 * expected results.
	 */
	public void assertCompletions(String textBefore, String... expectTextAfter) throws Exception {
		MockEditor editor = newEditor(textBefore);
		StringBuilder expect = new StringBuilder();
		StringBuilder actual = new StringBuilder();
		for (String after : expectTextAfter) {
			expect.append(after);
			expect.append("\n-------------------\n");
		}

		ICompletionProposal[] completions = getCompletions(editor);
		for (int i = 0; i < completions.length; i++) {
			editor = newEditor(textBefore);
			editor.apply(completions[i]);
			actual.append(editor.getText());
			actual.append("\n-------------------\n");
		}
		assertEquals(expect.toString(), actual.toString());
	}

	public void assertCompletionsDisplayString(String editorText, String... completionsLabels) throws Exception {
		assertCompletionsDisplayString(editorText, (x) -> true, completionsLabels);
	}

	public void assertCompletionsDisplayString(String editorText, Predicate<ICompletionProposal> filter, String... completionsLabels) throws Exception {
		MockEditor editor = newEditor(editorText);
		ICompletionProposal[] completions = Arrays.stream(getCompletions(editor))
				.filter(filter)
				.toArray((sz) -> new ICompletionProposal[sz]);
		String[] actualLabels = new String[completions.length];
		for (int i = 0; i < actualLabels.length; i++) {
			actualLabels[i] = completions[i].getDisplayString();
		}
		assertElements(actualLabels, completionsLabels);
	}

	public void assertStyledCompletions(String editorText, StyledStringMatcher... expectStyles) throws Exception {
		assertStyledCompletions(editorText, (c) -> true, expectStyles);
	}
	
	public void assertStyledCompletions(String editorText, Predicate<ICompletionProposal> filter, StyledStringMatcher... expectStyles) throws Exception {
		MockEditor editor = newEditor(editorText);
		ICompletionProposal[] completions = Arrays.stream(getCompletions(editor))
				.filter(filter)
				.toArray((sz) -> new ICompletionProposal[sz]);
		assertEquals("Wrong number of elements", expectStyles.length, completions.length);
		for (int i = 0; i < expectStyles.length; i++) {
			ICompletionProposal completion = completions[i];
			StyledString actualLabel = getStyledDisplayString(completion);
			expectStyles[i].match(actualLabel);
		}
	}

	private StyledString getStyledDisplayString(ICompletionProposal completion) {
		if (completion instanceof ICompletionProposalExtension6) {
			return ((ICompletionProposalExtension6)completion).getStyledDisplayString();
		}
		return new StyledString(completion.getDisplayString());
	}

	protected abstract MockEditor newEditor(String editorContents);

	/**
	 * Verifies an expected textSnippet is contained in the hovertext that is
	 * computed when hovering mouse at position at the end of first occurence of
	 * a given string in the editor.
	 * <p>
	 * Deprecated: This method should be removed and the similar api in MockPropertiesEditor used instead.
	 */
	@Deprecated
	public void assertHoverText(MockEditor editor, String afterString, String expectSnippet) {
		String hoverText = getHoverText(editor, afterString);
		assertContains(expectSnippet, hoverText);
	}

	/**
	 * Compute hover text when mouse hovers at the end of the first occurence of
	 * a given String in the editor contents.
	 * <p>
	 * Deprecated: This method should be removed and the similar api in MockPropertiesEditor used instead.
	 */
	@Deprecated
	public String getHoverText(MockEditor editor, String atString) {
		int pos = editor.getText().indexOf(atString);
		if (pos>=0) {
			pos += atString.length();
		}
		IRegion region = getHoverProvider().getHoverRegion(editor.document, pos);
		if (region!=null) {
			return getHoverProvider().getHoverInfo(editor.document, region).getHtml();
		}
		return null;
	}

	protected abstract HoverInfoProvider getHoverProvider();

	protected void tearDown() throws Exception {
		CachingValueProvider.restoreDefaults();
	}

	public boolean hasSources(IType element) throws JavaModelException {
		return StringUtil.hasText(element.getOpenable().getBuffer().getContents());
	}

	/**
	 * Ensures that the source jars for a given {@link IType} are downloaded.
	 * <p>
	 * This assumes the test project is a maven project.
	 * <p>
	 * Will do nothing if source for given type is already available. Will
	 * fail if cannot obtain source code for the type.
	 */
	public String downloadSources(IType element) throws Exception {
		IClasspathManager buildpathManager = MavenJdtPlugin.getDefault().getBuildpathManager();
		IPackageFragment pkg = element.getPackageFragment();
		IPackageFragmentRoot fragment = (IPackageFragmentRoot) pkg.getParent();
		buildpathManager.scheduleDownload(fragment, true, false);
		ACondition.waitFor("source jar download", 20_000, () -> {
			assertTrue("Sources not yet downloaded", hasSources(element));
		});
		return element.getOpenable().getBuffer().getContents();
	}

}
