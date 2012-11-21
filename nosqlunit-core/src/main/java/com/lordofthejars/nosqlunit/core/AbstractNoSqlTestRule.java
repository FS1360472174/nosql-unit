package com.lordofthejars.nosqlunit.core;

import static ch.lambdaj.Lambda.having;
import static ch.lambdaj.Lambda.on;
import static ch.lambdaj.Lambda.selectFirst;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import com.lordofthejars.nosqlunit.annotation.Selective;
import com.lordofthejars.nosqlunit.annotation.SelectiveMatcher;
import com.lordofthejars.nosqlunit.annotation.ShouldMatchDataSet;
import com.lordofthejars.nosqlunit.annotation.UsingDataSet;

public abstract class AbstractNoSqlTestRule implements MethodRule {

	private static final String EXPECTED_RESERVED_WORD = "-expected";

	/**
	 * With JUnit 4.10 is impossible to get target from a Rule, it seems that
	 * future versions will support it. For now constructor is apporach is the
	 * only way. But JUnit 4.11 undeprecated the TestMethod so for maintaining back compatibility target is maintained. 
	 */
	private Object target;

	private String identifier;

	private DefaultDataSetLocationResolver defaultDataSetLocationResolver;

	private LoadStrategyFactory loadStrategyFactory = new ReflectionLoadStrategyFactory();

	private InjectAnnotationProcessor injectAnnotationProcessor;

	public AbstractNoSqlTestRule(String identifier) {
		this.identifier = identifier;
		this.injectAnnotationProcessor = new InjectAnnotationProcessor(
				this.identifier);
	}

	public abstract DatabaseOperation getDatabaseOperation();

	public abstract String getWorkingExtension();

	@Override
	public Statement apply(final Statement base, final FrameworkMethod method, final Object testObject) {
		return new Statement() {

			@Override
			public void evaluate() throws Throwable {

				target = testObject;
				
				defaultDataSetLocationResolver = new DefaultDataSetLocationResolver(
						testObject.getClass());

				UsingDataSet usingDataSet = getUsingDataSetAnnotation();

				if (isTestAnnotatedWithDataSet(usingDataSet)) {
					loadDataSet(usingDataSet, method);
				}

				injectAnnotationProcessor.processInjectAnnotation(
						testObject.getClass(), target,
						getDatabaseOperation().connectionManager());

				base.evaluate();

				ShouldMatchDataSet shouldMatchDataSet = getShouldMatchDataSetAnnotation();

				if (isTestAnnotatedWithExpectedDataSet(shouldMatchDataSet)) {
					assertExpectation(shouldMatchDataSet);
				}

			}

			private ShouldMatchDataSet getShouldMatchDataSetAnnotation() {

				ShouldMatchDataSet shouldMatchDataSet = method
						.getAnnotation(ShouldMatchDataSet.class);

				if (!isTestAnnotatedWithExpectedDataSet(shouldMatchDataSet)) {

					Class<?> testClass = target.getClass();
					shouldMatchDataSet = testClass
							.getAnnotation(ShouldMatchDataSet.class);

				}

				return shouldMatchDataSet;
			}

			private UsingDataSet getUsingDataSetAnnotation() {

				UsingDataSet usingDataSet = method
						.getAnnotation(UsingDataSet.class);

				if (!isTestAnnotatedWithDataSet(usingDataSet)) {

					Class<?> testClass = target.getClass();
					usingDataSet = testClass.getAnnotation(UsingDataSet.class);

				}

				return usingDataSet;
			}

			private void assertExpectation(ShouldMatchDataSet shouldMatchDataSet)
					throws IOException {

				InputStream scriptContent = loadExpectedContentScript(method,
						shouldMatchDataSet);

				if (isNotEmptyStream(scriptContent)) {
					getDatabaseOperation().databaseIs(scriptContent);
				} else {
					throw new IllegalArgumentException(
							"File specified in location or selective matcher attribute "
									+ " of ShouldMatchDataSet is not present, or no files matching default location.");
				}

			}

			private InputStream loadExpectedContentScript(
					final FrameworkMethod method,
					ShouldMatchDataSet shouldMatchDataSet) throws IOException {
				String location = shouldMatchDataSet.location();
				InputStream scriptContent = null;

				if (isNotEmptyString(location)) {
					scriptContent = loadExpectedResultFromLocationAttribute(location);
				} else {

					SelectiveMatcher[] selectiveMatchers = shouldMatchDataSet
							.withSelectiveMatcher();

					SelectiveMatcher requiredSelectiveMatcher = findSelectiveMatcherByConnectionIdentifier(selectiveMatchers);
					
					if (isSelectiveMatchersDefined(requiredSelectiveMatcher)) {

						scriptContent = loadExpectedResultFromLocationAttribute(requiredSelectiveMatcher.location());
						
					} else {
						scriptContent = loadExpectedResultFromDefaultLocation(
								method, shouldMatchDataSet);
					}
				}
				return scriptContent;
			}

			private boolean isSelectiveMatchersDefined(
					SelectiveMatcher requiredSelectiveMatcher) {
				return requiredSelectiveMatcher != null;
			}

			private SelectiveMatcher findSelectiveMatcherByConnectionIdentifier(SelectiveMatcher[] selectiveMatchers) {
				return selectFirst(selectiveMatchers, 
						having(on(SelectiveMatcher.class).identifier(), equalTo(identifier)).and(
						having(on(SelectiveMatcher.class).location(), notNullValue())));
			}
			
			private InputStream loadExpectedResultFromDefaultLocation(
					final FrameworkMethod method,
					ShouldMatchDataSet shouldMatchDataSet)
					throws IOException {
				
				InputStream scriptContent = null;
				
				String defaultLocation = defaultDataSetLocationResolver
						.resolveDefaultDataSetLocation(
								shouldMatchDataSet, method,
								EXPECTED_RESERVED_WORD + "."
										+ getWorkingExtension());

				if (defaultLocation != null) {
					scriptContent = loadExpectedResultFromLocationAttribute(defaultLocation);
				}
				return scriptContent;
			}

			private InputStream loadExpectedResultFromLocationAttribute(
					String location) throws IOException {
				InputStream scriptContent;
				scriptContent = IOUtils.getStreamFromClasspathBaseResource(
						defaultDataSetLocationResolver.getResourceBase(),
						location);
				return scriptContent;
			}

			private void loadDataSet(UsingDataSet usingDataSet,
					FrameworkMethod method) throws IOException {

				String[] locations = usingDataSet.locations();

				List<InputStream> scriptContent = new ArrayList<InputStream>();

				scriptContent.addAll(loadGlobalDataSets(usingDataSet,
						method, locations));
				scriptContent.addAll(loadSelectiveDataSets(usingDataSet));

				LoadStrategyEnum loadStrategyEnum = usingDataSet.loadStrategy();
				LoadStrategyOperation loadStrategyOperation = loadStrategyFactory
						.getLoadStrategyInstance(loadStrategyEnum,
								getDatabaseOperation());
				loadStrategyOperation.executeScripts(scriptContent
						.toArray(new InputStream[scriptContent.size()]));

			}

			private List<InputStream> loadSelectiveDataSets(UsingDataSet usingDataSet)
					throws IOException {

				List<InputStream> scriptContent = new ArrayList<InputStream>();

				if (isSelectiveLocationsAttributeSpecified(usingDataSet)) {
					Selective[] selectiveLocations = usingDataSet
							.withSelectiveLocations();
					if (selectiveLocations != null
							&& selectiveLocations.length > 0) {
						for (Selective selective : selectiveLocations) {
							if (identifier
									.equals(selective.identifier().trim())
									&& isLocationsAttributeSpecified(selective
											.locations())) {
								scriptContent
										.addAll(IOUtils
												.getAllStreamsFromClasspathBaseResource(
														defaultDataSetLocationResolver
																.getResourceBase(),
														selective.locations()));
							}
						}
					}
				}

				return scriptContent;
			}

			private List<InputStream> loadGlobalDataSets(UsingDataSet usingDataSet,
					FrameworkMethod method, String[] locations)
					throws IOException {

				List<InputStream> scriptContent = new ArrayList<InputStream>();

				if (isLocationsAttributeSpecified(locations)) {

					scriptContent.addAll(IOUtils
							.getAllStreamsFromClasspathBaseResource(
									defaultDataSetLocationResolver
											.getResourceBase(), locations));

				} else {

					String location = defaultDataSetLocationResolver
							.resolveDefaultDataSetLocation(usingDataSet,
									method, "." + getWorkingExtension());

					if (location != null) {
						scriptContent.add(IOUtils
								.getStreamFromClasspathBaseResource(
										defaultDataSetLocationResolver
												.getResourceBase(), location));
					}

				}

				return scriptContent;
			}

			private boolean isSelectiveLocationsAttributeSpecified(
					UsingDataSet usingDataSet) {
				Selective[] selectiveLocations = usingDataSet
						.withSelectiveLocations();
				if (selectiveLocations != null && selectiveLocations.length > 0) {
					for (Selective selective : selectiveLocations) {
						if (identifier.equals(selective.identifier().trim())
								&& isLocationsAttributeSpecified(selective
										.locations())) {
							return true;
						}
					}
				}

				return false;
			}

			private boolean isNotEmptyStream(InputStream inputStream) {
				return inputStream != null;
			}
			
			private boolean isNotEmptyString(String location) {
				return location != null && !"".equals(location.trim());
			}

			private boolean isLocationsAttributeSpecified(String[] locations) {
				return locations != null && locations.length > 0;
			}

			private boolean isTestAnnotatedWithExpectedDataSet(
					ShouldMatchDataSet shouldMatchDataSet) {
				return shouldMatchDataSet != null;
			}

			private boolean isTestAnnotatedWithDataSet(UsingDataSet usingDataSet) {
				return usingDataSet != null;
			}
		};
	}

	public void setLoadStrategyFactory(LoadStrategyFactory loadStrategyFactory) {
		this.loadStrategyFactory = loadStrategyFactory;
	}

	public void setInjectAnnotationProcessor(
			InjectAnnotationProcessor injectAnnotationProcessor) {
		this.injectAnnotationProcessor = injectAnnotationProcessor;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

	/*
	 * With JUnit 4.10 is impossible to get target from a Rule, it seems that
	 * future versions will support it. For now constructor is approach is the
	 * only way.
	 */
	protected void setTarget(Object target) {
		this.target = target;
	}

}
