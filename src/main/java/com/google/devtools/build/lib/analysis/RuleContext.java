// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionRegistry;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.PrerequisiteValidator;
import com.google.devtools.build.lib.analysis.LocationExpander.Options;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.FragmentCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.packages.Attribute.SplitTransition;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy;
import com.google.devtools.build.lib.packages.FileTarget;
import com.google.devtools.build.lib.packages.FilesetEntry;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageSpecification;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.packages.SkylarkClassObject;
import com.google.devtools.build.lib.packages.SkylarkClassObjectConstructor;
import com.google.devtools.build.lib.packages.SkylarkProviderIdentifier;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.rules.AliasProvider;
import com.google.devtools.build.lib.rules.fileset.FilesetProvider;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The totality of data available during the analysis of a rule.
 *
 * <p>These objects should not outlast the analysis phase. Do not pass them to {@link Action}
 * objects or other persistent objects. There are internal tests to ensure that RuleContext objects
 * are not persisted that check the name of this class, so update those tests if you change this
 * class's name.
 *
 * @see com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory
 */
public final class RuleContext extends TargetContext
    implements ActionConstructionContext, ActionRegistry, RuleErrorConsumer {

  /**
   * The configured version of FilesetEntry.
   */
  @Immutable
  public static final class ConfiguredFilesetEntry {
    private final FilesetEntry entry;
    private final TransitiveInfoCollection src;
    private final ImmutableList<TransitiveInfoCollection> files;

    ConfiguredFilesetEntry(FilesetEntry entry, TransitiveInfoCollection src) {
      this.entry = entry;
      this.src = src;
      this.files = null;
    }

    ConfiguredFilesetEntry(FilesetEntry entry, ImmutableList<TransitiveInfoCollection> files) {
      this.entry = entry;
      this.src = null;
      this.files = files;
    }

    public FilesetEntry getEntry() {
      return entry;
    }

    public TransitiveInfoCollection getSrc() {
      return src;
    }

    /**
     * Targets from FilesetEntry.files, or null if the user omitted it.
     */
    @Nullable
    public ImmutableList<TransitiveInfoCollection> getFiles() {
      return files;
    }
  }

  private static final String HOST_CONFIGURATION_PROGRESS_TAG = "for host";

  private final Rule rule;
  private final ImmutableList<AspectDescriptor> aspectDescriptors;
  private final ListMultimap<String, ConfiguredTarget> targetMap;
  private final ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap;
  private final ImmutableMap<Label, ConfigMatchingProvider> configConditions;
  private final AspectAwareAttributeMapper attributes;
  private final ImmutableSet<String> features;
  private final String ruleClassNameForLogging;
  private final BuildConfiguration hostConfiguration;
  private final ConfigurationFragmentPolicy configurationFragmentPolicy;
  private final Class<? extends BuildConfiguration.Fragment> universalFragment;
  private final ErrorReporter reporter;
  private final ImmutableBiMap<String, Class<? extends TransitiveInfoProvider>>
      skylarkProviderRegistry;

  private ActionOwner actionOwner;

  /* lazily computed cache for Make variables, computed from the above. See get... method */
  private transient ConfigurationMakeVariableContext configurationMakeVariableContext = null;

  private RuleContext(
      Builder builder,
      AttributeMap attributes,
      ListMultimap<String, ConfiguredTarget> targetMap,
      ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      Class<? extends BuildConfiguration.Fragment> universalFragment,
      String ruleClassNameForLogging,
      ImmutableMap<String, Attribute> aspectAttributes) {
    super(builder.env, builder.rule, builder.configuration, builder.prerequisiteMap.get(null),
        builder.visibility);
    this.rule = builder.rule;
    this.aspectDescriptors = builder.aspectDescriptors;
    this.configurationFragmentPolicy = builder.configurationFragmentPolicy;
    this.universalFragment = universalFragment;
    this.targetMap = targetMap;
    this.filesetEntryMap = filesetEntryMap;
    this.configConditions = configConditions;
    this.attributes = new AspectAwareAttributeMapper(attributes, aspectAttributes);
    this.features = getEnabledFeatures();
    this.ruleClassNameForLogging = ruleClassNameForLogging;
    this.skylarkProviderRegistry = builder.skylarkProviderRegistry;
    this.hostConfiguration = builder.hostConfiguration;
    reporter = builder.reporter;
  }

  private ImmutableSet<String> getEnabledFeatures() {
    Set<String> globallyEnabled = new HashSet<>();
    Set<String> globallyDisabled = new HashSet<>();
    parseFeatures(getConfiguration().getDefaultFeatures(), globallyEnabled, globallyDisabled);
    for (ImmutableMap.Entry<Class<? extends Fragment>, Fragment> entry :
        getConfiguration().getAllFragments().entrySet()) {
      if (isLegalFragment(entry.getKey())) {
        globallyEnabled.addAll(entry.getValue().configurationEnabledFeatures(this));
      }
    }
    Set<String> packageEnabled = new HashSet<>();
    Set<String> packageDisabled = new HashSet<>();
    parseFeatures(getRule().getPackage().getFeatures(), packageEnabled, packageDisabled);
    Set<String> packageFeatures =
        Sets.difference(Sets.union(globallyEnabled, packageEnabled), packageDisabled);
    Set<String> ruleFeatures = packageFeatures;
    if (attributes().has("features", Type.STRING_LIST)) {
      Set<String> ruleEnabled = new HashSet<>();
      Set<String> ruleDisabled = new HashSet<>();
      parseFeatures(attributes().get("features", Type.STRING_LIST), ruleEnabled, ruleDisabled);
      ruleFeatures = Sets.difference(Sets.union(packageFeatures, ruleEnabled), ruleDisabled);
    }
    return ImmutableSortedSet.copyOf(Sets.difference(ruleFeatures, globallyDisabled));
  }

  private void parseFeatures(Iterable<String> features, Set<String> enabled, Set<String> disabled) {
    for (String feature : features) {
      if (feature.startsWith("-")) {
        disabled.add(feature.substring(1));
      } else if (feature.equals("no_layering_check")) {
        // TODO(bazel-team): Remove once we do not have BUILD files left that contain
        // 'no_layering_check'.
        disabled.add(feature.substring(3));
      } else {
        enabled.add(feature);
      }
    }
  }

  @Override
  public Rule getRule() {
    return rule;
  }

  /**
   * Returns a rule class name suitable for log messages, including an aspect name if applicable.
   */
  public String getRuleClassNameForLogging() {
    return ruleClassNameForLogging;
  }

  /**
   * Returns the workspace name for the rule.
   */
  public String getWorkspaceName() {
    return rule.getPackage().getWorkspaceName();
  }

  /**
   * The configuration conditions that trigger this rule's configurable attributes.
   */
  ImmutableMap<Label, ConfigMatchingProvider> getConfigConditions() {
    return configConditions;
  }

  /**
   * Returns the host configuration for this rule.
   */
  public BuildConfiguration getHostConfiguration() {
    return hostConfiguration;
  }

  /**
   * Attributes from aspects.
   */
  public ImmutableMap<String, Attribute> getAspectAttributes() {
    return attributes.getAspectAttributes();
  }

  /**
   * Accessor for the attributes of the rule and its aspects.
   *
   * <p>The rule's native attributes can be queried both on their structure / existence and values
   * Aspect attributes can only be queried on their structure.
   *
   * <p>This should be the sole interface for reading rule/aspect attributes in {@link RuleContext}.
   * Don't expose other access points through new public methods.
   */
  public AttributeMap attributes() {
    return attributes;
  }

  /**
   * Returns a map that indicates which providers should be exported to skylark under the key
   * (map key).  These provider types will also be exportable by skylark rules under (map key).
   */
  public ImmutableBiMap<String, Class<? extends TransitiveInfoProvider>>
      getSkylarkProviderRegistry() {
    return skylarkProviderRegistry;
  }

  /**
   * Returns whether this instance is known to have errors at this point during analysis. Do not
   * call this method after the initializationHook has returned.
   */
  public boolean hasErrors() {
    return getAnalysisEnvironment().hasErrors();
  }

  /**
   * No-op if {@link #hasErrors} is false, throws {@link RuleErrorException} if it is true.
   * This provides a convenience to early-exit of configured target creation if there are errors.
   */
  public void assertNoErrors() throws RuleErrorException {
    if (hasErrors()) {
      throw new RuleErrorException();
    }
  }

  /**
   * Returns an immutable map from attribute name to list of configured targets for that attribute.
   */
  public ListMultimap<String, ? extends TransitiveInfoCollection> getConfiguredTargetMap() {
    return targetMap;
  }

  /**
   * Returns an immutable map from attribute name to list of fileset entries.
   */
  public ListMultimap<String, ConfiguredFilesetEntry> getFilesetEntryMap() {
    return filesetEntryMap;
  }

  @Override
  public ActionOwner getActionOwner() {
    if (actionOwner == null) {
      actionOwner = createActionOwner(rule, aspectDescriptors, getConfiguration());
    }
    return actionOwner;
  }

  /**
   * Returns a configuration fragment for this this target.
   */
  @Nullable
  public <T extends Fragment> T getFragment(Class<T> fragment, ConfigurationTransition config) {
    return getFragment(fragment, fragment.getSimpleName(), "", config);
  }

  @Nullable
  protected <T extends Fragment> T getFragment(Class<T> fragment, String name,
      String additionalErrorMessage, ConfigurationTransition config) {
    // TODO(bazel-team): The fragments can also be accessed directly through BuildConfiguration.
    // Can we lock that down somehow?
    Preconditions.checkArgument(isLegalFragment(fragment, config),
        "%s has to declare '%s' as a required fragment "
        + "in %s configuration in order to access it.%s",
        getRuleClassNameForLogging(), name, FragmentCollection.getConfigurationName(config),
        additionalErrorMessage);
    return getConfiguration(config).getFragment(fragment);
  }

  @Nullable
  public <T extends Fragment> T getFragment(Class<T> fragment) {
    // NONE means target configuration.
    return getFragment(fragment, ConfigurationTransition.NONE);
  }

  @Nullable
  public Fragment getSkylarkFragment(String name, ConfigurationTransition config) {
    Class<? extends Fragment> fragmentClass =
        getConfiguration(config).getSkylarkFragmentByName(name);
    if (fragmentClass == null) {
      return null;
    }
    return getFragment(fragmentClass, name,
        String.format(
            " Please update the '%1$sfragments' argument of the rule definition "
            + "(for example: %1$sfragments = [\"%2$s\"])",
            (config == ConfigurationTransition.HOST) ? "host_" : "", name),
        config);
  }

  public ImmutableCollection<String> getSkylarkFragmentNames(ConfigurationTransition config) {
    return getConfiguration(config).getSkylarkFragmentNames();
  }

  public <T extends Fragment> boolean isLegalFragment(
      Class<T> fragment, ConfigurationTransition config) {
    return fragment == universalFragment
        || configurationFragmentPolicy.isLegalConfigurationFragment(fragment, config);
  }

  public <T extends Fragment> boolean isLegalFragment(Class<T> fragment) {
    // NONE means target configuration.
    return isLegalFragment(fragment, ConfigurationTransition.NONE);
  }

  protected BuildConfiguration getConfiguration(ConfigurationTransition config) {
    return config.equals(ConfigurationTransition.HOST) ? hostConfiguration : getConfiguration();
  }

  @Override
  public ArtifactOwner getOwner() {
    return getAnalysisEnvironment().getOwner();
  }

  public ImmutableList<Artifact> getBuildInfo(BuildInfoKey key) throws InterruptedException {
    return getAnalysisEnvironment().getBuildInfo(this, key, getConfiguration());
  }

  @VisibleForTesting
  public static ActionOwner createActionOwner(
      Rule rule,
      ImmutableList<AspectDescriptor> aspectDescriptors,
      BuildConfiguration configuration) {
    return ActionOwner.create(
        rule.getLabel(),
        aspectDescriptors,
        rule.getLocation(),
        configuration.getMnemonic(),
        rule.getTargetKind(),
        configuration.checksum(),
        configuration.isHostConfiguration() ? HOST_CONFIGURATION_PROGRESS_TAG : null);
  }

  @Override
  public void registerAction(ActionAnalysisMetadata... action) {
    getAnalysisEnvironment().registerAction(action);
  }

  /**
   * Convenience function for subclasses to report non-attribute-specific
   * errors in the current rule.
   */
  @Override
  public void ruleError(String message) {
    reporter.ruleError(message);
  }

  /**
   * Convenience function to report non-attribute-specific errors in the current rule and then
   * throw a {@link RuleErrorException}, immediately exiting the build invocation. Alternatively,
   * invoke {@link #ruleError} instead to collect additional error information before ending the
   * invocation.
   */
  public void throwWithRuleError(String message) throws RuleErrorException {
    reporter.ruleError(message);
    throw new RuleErrorException();
  }

  /**
   * Convenience function for subclasses to report non-attribute-specific
   * warnings in the current rule.
   */
  @Override
  public void ruleWarning(String message) {
    reporter.ruleWarning(message);
  }

  /**
   * Convenience function for subclasses to report attribute-specific errors in
   * the current rule.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  @Override
  public void attributeError(String attrName, String message) {
    reporter.attributeError(attrName, message);
  }

  /**
   * Convenience function to report attribute-specific errors in the current rule, and then throw a
   * {@link RuleErrorException}, immediately exiting the build invocation. Alternatively, invoke
   * {@link #attributeError} instead to collect additional error information before ending the
   * invocation.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  public RuleErrorException throwWithAttributeError(String attrName, String message)
      throws RuleErrorException {
    reporter.attributeError(attrName, message);
    throw new RuleErrorException();
  }

  /**
   * Like attributeError, but does not mark the configured target as errored.
   *
   * <p>If the name of the attribute starts with <code>$</code>
   * it is replaced with a string <code>(an implicit dependency)</code>.
   */
  @Override
  public void attributeWarning(String attrName, String message) {
    reporter.attributeWarning(attrName, message);
  }

  /**
   * Returns an artifact beneath the root of either the "bin" or "genfiles"
   * tree, whose path is based on the name of this target and the current
   * configuration.  The choice of which tree to use is based on the rule with
   * which this target (which must be an OutputFile or a Rule) is associated.
   */
  public Artifact createOutputArtifact() {
    return internalCreateOutputArtifact(getTarget());
  }

  /**
   * Returns the output artifact of an {@link OutputFile} of this target.
   *
   * @see #createOutputArtifact()
   */
  public Artifact createOutputArtifact(OutputFile out) {
    return internalCreateOutputArtifact(out);
  }

  /**
   * Implementation for {@link #createOutputArtifact()} and
   * {@link #createOutputArtifact(OutputFile)}. This is private so that
   * {@link #createOutputArtifact(OutputFile)} can have a more specific
   * signature.
   */
  private Artifact internalCreateOutputArtifact(Target target) {
    Preconditions.checkState(
        target.getLabel().getPackageIdentifier().equals(getLabel().getPackageIdentifier()),
        "Creating output artifact for target '%s' in different package than the rule '%s' "
            + "being analyzed", target.getLabel(), getLabel());
    Root root = getBinOrGenfilesDirectory();
    return getPackageRelativeArtifact(target.getName(), root);
  }

  /**
   * Returns the root of either the "bin" or "genfiles"
   * tree, based on this target and the current configuration.
   * The choice of which tree to use is based on the rule with
   * which this target (which must be an OutputFile or a Rule) is associated.
   */
  public Root getBinOrGenfilesDirectory() {
    return rule.hasBinaryOutput()
        ? getConfiguration().getBinDirectory(rule.getRepository())
        : getConfiguration().getGenfilesDirectory(rule.getRepository());
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule,
   * thus guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getPackageRelativeArtifact(String relative, Root root) {
    return getPackageRelativeArtifact(new PathFragment(relative), root);
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getBinArtifact(String relative) {
    return getBinArtifact(new PathFragment(relative));
  }

  public Artifact getBinArtifact(PathFragment relative) {
    return getPackageRelativeArtifact(
        relative, getConfiguration().getBinDirectory(rule.getRepository()));
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getGenfilesArtifact(String relative) {
    return getGenfilesArtifact(new PathFragment(relative));
  }

  public Artifact getGenfilesArtifact(PathFragment relative) {
    return getPackageRelativeArtifact(
        relative, getConfiguration().getGenfilesDirectory(rule.getRepository()));
  }

  /**
   * Returns an artifact that can be an output of shared actions. Only use when there is no other
   * option.
   *
   * <p>This artifact can be created anywhere in the output tree, which, in addition to making
   * sharing possible, opens up the possibility of action conflicts and makes it impossible to
   * infer the label of the rule creating the artifact from the path of the artifact.
   */
  public Artifact getShareableArtifact(PathFragment rootRelativePath, Root root) {
    return getAnalysisEnvironment().getDerivedArtifact(rootRelativePath, root);
  }

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule,
   * thus guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  public Artifact getPackageRelativeArtifact(PathFragment relative, Root root) {
    return getDerivedArtifact(getPackageDirectory().getRelative(relative), root);
  }

  /**
   * Returns the root-relative path fragment under which output artifacts of this rule should go.
   *
   * <p>Note that:
   * <ul>
   *   <li>This doesn't guarantee that there are no clashes with rules in the same package.
   *   <li>If possible, {@link #getPackageRelativeArtifact(PathFragment, Root)} should be used
   *   instead of this method.
   * </ul>
   *
   * Ideally, user-visible artifacts should all have corresponding output file targets, all others
   * should go into a rule-specific directory.
   * {@link #getUniqueDirectoryArtifact(String, PathFragment, Root)}) ensures that this is the case.
   */
  public PathFragment getPackageDirectory() {
    return getLabel().getPackageIdentifier().getSourceRoot();
  }

  /**
   * Creates an artifact under a given root with the given root-relative path.
   *
   * <p>Verifies that it is in the root-relative directory corresponding to the package of the rule,
   * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
   * method.
   */
  @Override
  public Artifact getDerivedArtifact(PathFragment rootRelativePath, Root root) {
    Preconditions.checkState(rootRelativePath.startsWith(getPackageDirectory()),
        "Output artifact '%s' not under package directory '%s' for target '%s'",
        rootRelativePath, getPackageDirectory(), getLabel());
    return getAnalysisEnvironment().getDerivedArtifact(rootRelativePath, root);
  }

  /**
   * Creates a TreeArtifact under a given root with the given root-relative path.
   *
   * <p>Verifies that it is in the root-relative directory corresponding to the package of the rule,
   * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
   * method.
   */
  public Artifact getTreeArtifact(PathFragment rootRelativePath, Root root) {
    Preconditions.checkState(rootRelativePath.startsWith(getPackageDirectory()),
        "Output artifact '%s' not under package directory '%s' for target '%s'",
        rootRelativePath, getPackageDirectory(), getLabel());
    return getAnalysisEnvironment().getTreeArtifact(rootRelativePath, root);
  }

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   */
  public Artifact getUniqueDirectoryArtifact(
      String uniqueDirectory, String relative, Root root) {
    return getUniqueDirectoryArtifact(uniqueDirectory, new PathFragment(relative), root);
  }

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   */
  public Artifact getUniqueDirectoryArtifact(
      String uniqueDirectory, PathFragment relative, Root root) {
    return getDerivedArtifact(getUniqueDirectory(uniqueDirectory).getRelative(relative), root);
  }

  /**
   * Returns true iff the rule, or any attached aspect, has an attribute with the given name and
   * type.
   */
  public boolean isAttrDefined(String attrName, Type<?> type) {
    return attributes().has(attrName, type);
  }

  /**
   * Returns the dependencies through a {@code LABEL_DICT_UNARY} attribute as a map from
   * a string to a {@link TransitiveInfoCollection}.
   */
  public Map<String, TransitiveInfoCollection> getPrerequisiteMap(String attributeName) {
    Preconditions.checkState(attributes().has(attributeName, BuildType.LABEL_DICT_UNARY));

    ImmutableMap.Builder<String, TransitiveInfoCollection> result = ImmutableMap.builder();
    Map<String, Label> dict = attributes().get(attributeName, BuildType.LABEL_DICT_UNARY);
    Map<Label, ConfiguredTarget> labelToDep = new HashMap<>();
    for (ConfiguredTarget dep : targetMap.get(attributeName)) {
      labelToDep.put(dep.getLabel(), dep);
    }

    for (Map.Entry<String, Label> entry : dict.entrySet()) {
      result.put(entry.getKey(), Preconditions.checkNotNull(labelToDep.get(entry.getValue())));
    }

    return result.build();
  }

  /**
   * Returns the list of transitive info collections that feed into this target through the
   * specified attribute. Note that you need to specify the correct mode for the attribute,
   * otherwise an assertion will be raised.
   */
  public List<? extends TransitiveInfoCollection> getPrerequisites(String attributeName,
      Mode mode) {
    Attribute attributeDefinition = attributes().getAttributeDefinition(attributeName);
    if ((mode == Mode.TARGET) && (attributeDefinition.hasSplitConfigurationTransition())) {
      // TODO(bazel-team): If you request a split-configured attribute in the target configuration,
      // we return only the list of configured targets for the first architecture; this is for
      // backwards compatibility with existing code in cases where the call to getPrerequisites is
      // deeply nested and we can't easily inject the behavior we want. However, we should fix all
      // such call sites.
      checkAttribute(attributeName, Mode.SPLIT);
      Map<Optional<String>, ? extends List<? extends TransitiveInfoCollection>> map =
          getSplitPrerequisites(attributeName);
      return map.isEmpty()
          ? ImmutableList.<TransitiveInfoCollection>of()
          : map.entrySet().iterator().next().getValue();
    }

    checkAttribute(attributeName, mode);
    return targetMap.get(attributeName);
  }

  /**
   * Returns the a prerequisites keyed by the CPU of their configurations.
   * If the split transition is not active (e.g. split() returned an empty
   * list), the key is an empty Optional.
   */
  public Map<Optional<String>, ? extends List<? extends TransitiveInfoCollection>>
      getSplitPrerequisites(String attributeName) {
    checkAttribute(attributeName, Mode.SPLIT);

    Attribute attributeDefinition = attributes().getAttributeDefinition(attributeName);
    @SuppressWarnings("unchecked") // Attribute.java doesn't have the BuildOptions symbol.
    SplitTransition<BuildOptions> transition =
        (SplitTransition<BuildOptions>) attributeDefinition.getSplitTransition(rule);
    List<ConfiguredTarget> deps = targetMap.get(attributeName);

    List<BuildOptions> splitOptions = transition.split(getConfiguration().getOptions());
    if (splitOptions.isEmpty()) {
      // The split transition is not active. Defer the decision on which CPU to use.
      return ImmutableMap.of(Optional.<String>absent(), deps);
    }

    Set<String> cpus = new HashSet<>();
    for (BuildOptions options : splitOptions) {
      // This method should only be called when the split config is enabled on the command line, in
      // which case this cpu can't be null.
      cpus.add(options.get(BuildConfiguration.Options.class).cpu);
    }

    // Use an ImmutableListMultimap.Builder here to preserve ordering.
    ImmutableListMultimap.Builder<Optional<String>, TransitiveInfoCollection> result =
        ImmutableListMultimap.builder();
    for (TransitiveInfoCollection t : deps) {
      if (t.getConfiguration() != null) {
        result.put(Optional.of(t.getConfiguration().getCpu()), t);
      } else {
        // Source files don't have a configuration, so we add them to all architecture entries.
        for (String cpu : cpus) {
          result.put(Optional.of(cpu), t);
        }
      }
    }
    return Multimaps.asMap(result.build());
  }

  /**
   * Returns the specified provider of the prerequisite referenced by the attribute in the
   * argument. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. If the attribute is empty of it does not support the specified
   * provider, returns null.
   */
  public <C extends TransitiveInfoProvider> C getPrerequisite(
      String attributeName, Mode mode, Class<C> provider) {
    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    return prerequisite == null ? null : prerequisite.getProvider(provider);
  }

  /**
   * Returns the transitive info collection that feeds into this target through the specified
   * attribute. Note that you need to specify the correct mode for the attribute, otherwise an
   * assertion will be raised. Returns null if the attribute is empty.
   */
  public TransitiveInfoCollection getPrerequisite(String attributeName, Mode mode) {
    checkAttribute(attributeName, mode);
    List<? extends TransitiveInfoCollection> elements = targetMap.get(attributeName);
    if (elements.size() > 1) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
          + " produces more than one prerequisite");
    }
    return elements.isEmpty() ? null : elements.get(0);
  }

  /**
   * For a given attribute, returns all {@link TransitiveInfoProvider}s provided by targets
   * of that attribute. Each {@link TransitiveInfoProvider} is keyed by the
   * {@link BuildConfiguration} under which the provider was created.
   */
  public <C extends TransitiveInfoProvider> ImmutableListMultimap<BuildConfiguration, C>
      getPrerequisitesByConfiguration(String attributeName, Mode mode, final Class<C> classType) {
    AnalysisUtils.checkProvider(classType);
    List<? extends TransitiveInfoCollection> transitiveInfoCollections =
        getPrerequisites(attributeName, mode);

    ImmutableListMultimap.Builder<BuildConfiguration, C> result =
        ImmutableListMultimap.builder();
    for (TransitiveInfoCollection prerequisite : transitiveInfoCollections) {
      C prerequisiteProvider = prerequisite.getProvider(classType);
      if (prerequisiteProvider != null) {
        result.put(prerequisite.getConfiguration(), prerequisiteProvider);
      }
    }
    return result.build();
  }

  /**
   * For a given attribute, returns all {@link TransitiveInfoCollection}s provided by targets
   * of that attribute. Each {@link TransitiveInfoCollection} is keyed by the
   * {@link BuildConfiguration} under which the collection was created.
   */
  public ImmutableListMultimap<BuildConfiguration, TransitiveInfoCollection>
      getPrerequisitesByConfiguration(String attributeName, Mode mode) {
    List<? extends TransitiveInfoCollection> transitiveInfoCollections =
        getPrerequisites(attributeName, mode);

    ImmutableListMultimap.Builder<BuildConfiguration, TransitiveInfoCollection> result =
        ImmutableListMultimap.builder();
    for (TransitiveInfoCollection prerequisite : transitiveInfoCollections) {
      result.put(prerequisite.getConfiguration(), prerequisite);
    }
    return result.build();
  }

  /**
   * Returns all the providers of the specified type that are listed under the specified attribute
   * of this target in the BUILD file.
   */
  public <C extends TransitiveInfoProvider> Iterable<C> getPrerequisites(String attributeName,
      Mode mode, final Class<C> classType) {
    AnalysisUtils.checkProvider(classType);
    return AnalysisUtils.getProviders(getPrerequisites(attributeName, mode), classType);
  }

  /**
   * Returns all the declared providers (native and Skylark) for the specified constructor under the
   * specified attribute of this target in the BUILD file.
   */
  public Iterable<SkylarkClassObject> getPrerequisites(
      String attributeName, Mode mode, final SkylarkClassObjectConstructor.Key skylarkKey) {
    return AnalysisUtils.getProviders(getPrerequisites(attributeName, mode), skylarkKey);
  }

  /**
   * Returns the declared provider (native and Skylark) for the specified constructor under the
   * specified attribute of this target in the BUILD file. May return null if there is no
   * TransitiveInfoCollection under the specified attribute.
   */
  @Nullable
  public SkylarkClassObject getPrerequisite(
      String attributeName, Mode mode, final SkylarkClassObjectConstructor.Key skylarkKey) {
    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    return prerequisite == null ? null : prerequisite.get(skylarkKey);
  }

  /**
   * Returns all the providers of the specified type that are listed under the specified attribute
   * of this target in the BUILD file, and that contain the specified provider.
   */
  public <C extends TransitiveInfoProvider> Iterable<? extends TransitiveInfoCollection>
      getPrerequisitesIf(String attributeName, Mode mode, final Class<C> classType) {
    AnalysisUtils.checkProvider(classType);
    return AnalysisUtils.filterByProvider(getPrerequisites(attributeName, mode), classType);
  }

  /**
   * Returns the prerequisite referred to by the specified attribute. Also checks whether
   * the attribute is marked as executable and that the target referred to can actually be
   * executed.
   *
   * <p>The {@code mode} argument must match the configuration transition specified in the
   * definition of the attribute.
   *
   * @param attributeName the name of the attribute
   * @param mode the configuration transition of the attribute
   *
   * @return the {@link FilesToRunProvider} interface of the prerequisite.
   */
  @Nullable
  public FilesToRunProvider getExecutablePrerequisite(String attributeName, Mode mode) {
    Attribute ruleDefinition = attributes().getAttributeDefinition(attributeName);

    if (ruleDefinition == null) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
          + " is not defined");
    }
    if (!ruleDefinition.isExecutable()) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
          + " is not configured to be executable");
    }

    TransitiveInfoCollection prerequisite = getPrerequisite(attributeName, mode);
    if (prerequisite == null) {
      return null;
    }

    FilesToRunProvider result = prerequisite.getProvider(FilesToRunProvider.class);
    if (result == null || result.getExecutable() == null) {
      attributeError(
          attributeName, prerequisite.getLabel() + " does not refer to a valid executable target");
    }
    return result;
  }

  /** Indicates whether a string list attribute should be tokenized. */
  public enum Tokenize {
    YES,
    NO
  }

  /**
   * Gets an attribute of type STRING_LIST expanding Make variables, $(location) tags into the
   * dependency location (see {@link LocationExpander} for details) and tokenizes the result.
   *
   * @param attributeName the name of the attribute to process
   * @return a list of strings containing the expanded and tokenized values for the attribute
   */
  public ImmutableList<String> getTokenizedStringListAttr(String attributeName) {
    return getExpandedStringListAttr(attributeName, Tokenize.YES);
  }

  /**
   * Gets an attribute of type STRING_LIST expanding Make variables and $(location) tags,
   * and optionally tokenizes the result.
   *
   * @param attributeName the name of the attribute to process
   * @return a list of strings containing the processed values for the attribute
   */
  public ImmutableList<String> getExpandedStringListAttr(String attributeName, Tokenize tokenize) {
    if (!getRule().isAttrDefined(attributeName, Type.STRING_LIST)) {
      // TODO(bazel-team): This should be an error.
      return ImmutableList.of();
    }
    List<String> original = attributes().get(attributeName, Type.STRING_LIST);
    if (original.isEmpty()) {
      return ImmutableList.of();
    }
    List<String> tokens = new ArrayList<>();
    LocationExpander locationExpander =
        new LocationExpander(this, LocationExpander.Options.ALLOW_DATA);

    for (String token : original) {
      expandValue(tokens, attributeName, token, locationExpander, tokenize);
    }
    return ImmutableList.copyOf(tokens);
  }

  /**
   * Expands make variables in value and tokenizes the result into tokens.
   *
   * <p>This methods should be called only during initialization.
   */
  public void tokenizeAndExpandMakeVars(List<String> tokens, String attributeName, String value) {
    LocationExpander locationExpander =
        new LocationExpander(this, Options.ALLOW_DATA, Options.EXEC_PATHS);
    tokenizeAndExpandMakeVars(tokens, attributeName, value, locationExpander);
  }

  /**
   * Expands make variables and $(location) tags in value and tokenizes the result into tokens.
   *
   * <p>This methods should be called only during initialization.
   */
  public void tokenizeAndExpandMakeVars(
      List<String> tokens,
      String attributeName,
      String value,
      @Nullable LocationExpander locationExpander) {
    expandValue(tokens, attributeName, value, locationExpander, Tokenize.YES);
  }

  /**
   * Expands make variables and $(location) tags in value, and optionally tokenizes the result.
   *
   * <p>This methods should be called only during initialization.
   */
  public void expandValue(
      List<String> tokens,
      String attributeName,
      String value,
      @Nullable LocationExpander locationExpander,
      Tokenize tokenize) {
    if (locationExpander != null) {
      value = locationExpander.expandAttribute(attributeName, value);
    }
    value = expandMakeVariables(attributeName, value);
    if (tokenize == Tokenize.YES) {
      try {
        ShellUtils.tokenize(tokens, value);
      } catch (ShellUtils.TokenizationException e) {
        attributeError(attributeName, e.getMessage());
      }
    } else {
      tokens.add(value);
    }
  }

  /**
   * Return a context that maps Make variable names (string) to values (string).
   *
   * @return a ConfigurationMakeVariableContext.
   **/
  public ConfigurationMakeVariableContext getConfigurationMakeVariableContext() {
    if (configurationMakeVariableContext == null) {
      configurationMakeVariableContext = new ConfigurationMakeVariableContext(
          getRule().getPackage(), getConfiguration());
    }
    return configurationMakeVariableContext;
  }

  /**
   * Returns the string "expression" after expanding all embedded references to
   * "Make" variables.  If any errors are encountered, they are reported, and
   * "expression" is returned unchanged.
   *
   * @param attributeName the name of the attribute from which "expression" comes;
   *     used for error reporting.
   * @param expression the string to expand.
   * @return the expansion of "expression".
   */
  public String expandMakeVariables(String attributeName, String expression) {
    return expandMakeVariables(attributeName, expression, getConfigurationMakeVariableContext());
  }

  /**
   * Returns the string "expression" after expanding all embedded references to
   * "Make" variables.  If any errors are encountered, they are reported, and
   * "expression" is returned unchanged.
   *
   * @param attributeName the name of the attribute from which "expression" comes;
   *     used for error reporting.
   * @param expression the string to expand.
   * @param context the ConfigurationMakeVariableContext which can have a customized
   *     lookupMakeVariable(String) method.
   * @return the expansion of "expression".
   */
  public String expandMakeVariables(String attributeName, String expression,
      ConfigurationMakeVariableContext context) {
    try {
      return MakeVariableExpander.expand(expression, context);
    } catch (MakeVariableExpander.ExpansionException e) {
      attributeError(attributeName, e.getMessage());
      return expression;
    }
  }

  /**
   * Gets the value of the STRING_LIST attribute expanding all make variables.
   */
  public List<String> expandedMakeVariablesList(String attrName) {
    List<String> variables = new ArrayList<>();
    for (String variable : attributes().get(attrName, Type.STRING_LIST)) {
      variables.add(expandMakeVariables(attrName, variable));
    }
    return variables;
  }

  /**
   * If the string consists of a single variable, returns the expansion of
   * that variable. Otherwise, returns null. Syntax errors are reported.
   *
   * @param attrName the name of the attribute from which "expression" comes;
   *     used for error reporting.
   * @param expression the string to expand.
   * @return the expansion of "expression", or null.
   */
  public String expandSingleMakeVariable(String attrName, String expression) {
    try {
      return MakeVariableExpander.expandSingleVariable(expression,
          new ConfigurationMakeVariableContext(getRule().getPackage(), getConfiguration()));
    } catch (MakeVariableExpander.ExpansionException e) {
      attributeError(attrName, e.getMessage());
      return expression;
    }
  }

  private void checkAttribute(String attributeName, Mode mode) {
    Attribute attributeDefinition = attributes.getAttributeDefinition(attributeName);
    if (attributeDefinition == null) {
      throw new IllegalStateException(getRule().getLocation() + ": " + getRuleClassNameForLogging()
        + " attribute " + attributeName + " is not defined");
    }
    if (!(attributeDefinition.getType() == BuildType.LABEL
        || attributeDefinition.getType() == BuildType.LABEL_LIST)) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
        + " is not a label type attribute");
    }
    if (mode == Mode.HOST) {
      if (attributeDefinition.getConfigurationTransition() != ConfigurationTransition.HOST) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for the host configuration");
      }
    } else if (mode == Mode.TARGET) {
      if (attributeDefinition.getConfigurationTransition() != ConfigurationTransition.NONE) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for the target configuration");
      }
    } else if (mode == Mode.DATA) {
      if (attributeDefinition.getConfigurationTransition() != ConfigurationTransition.DATA) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for the data configuration");
      }
    } else if (mode == Mode.SPLIT) {
      if (!(attributeDefinition.hasSplitConfigurationTransition())) {
        throw new IllegalStateException(getRule().getLocation() + ": "
            + getRuleClassNameForLogging() + " attribute " + attributeName
            + " is not configured for a split transition");
      }
    }
  }

  /**
   * Returns the Mode for which the attribute is configured.
   * This is intended for Skylark, where the Mode is implicitly chosen.
   */
  public Mode getAttributeMode(String attributeName) {
    Attribute attributeDefinition = attributes().getAttributeDefinition(attributeName);
    if (attributeDefinition == null) {
      throw new IllegalStateException(getRule().getLocation() + ": " + getRuleClassNameForLogging()
        + " attribute " + attributeName + " is not defined");
    }
    if (!(attributeDefinition.getType() == BuildType.LABEL
        || attributeDefinition.getType() == BuildType.LABEL_LIST)) {
      throw new IllegalStateException(getRuleClassNameForLogging() + " attribute " + attributeName
        + " is not a label type attribute");
    }
    if (attributeDefinition.getConfigurationTransition() == ConfigurationTransition.HOST) {
      return Mode.HOST;
    } else if (attributeDefinition.getConfigurationTransition() == ConfigurationTransition.NONE) {
      return Mode.TARGET;
    } else if (attributeDefinition.getConfigurationTransition() == ConfigurationTransition.DATA) {
      return Mode.DATA;
    } else if (attributeDefinition.hasSplitConfigurationTransition()) {
      return Mode.SPLIT;
    }
    throw new IllegalStateException(getRule().getLocation() + ": "
        + getRuleClassNameForLogging() + " attribute " + attributeName + " is not configured");
  }

  /**
   * For the specified attribute "attributeName" (which must be of type
   * list(label)), resolve all the labels into ConfiguredTargets (for the
   * configuration appropriate to the attribute) and return their build
   * artifacts as a {@link PrerequisiteArtifacts} instance.
   *
   * @param attributeName the name of the attribute to traverse
   */
  public PrerequisiteArtifacts getPrerequisiteArtifacts(String attributeName, Mode mode) {
    return PrerequisiteArtifacts.get(this, attributeName, mode);
  }

  /**
   * For the specified attribute "attributeName" (which must be of type label),
   * resolves the ConfiguredTarget and returns its single build artifact.
   *
   * <p>If the attribute is optional, has no default and was not specified, then
   * null will be returned. Note also that null is returned (and an attribute
   * error is raised) if there wasn't exactly one build artifact for the target.
   */
  public Artifact getPrerequisiteArtifact(String attributeName, Mode mode) {
    TransitiveInfoCollection target = getPrerequisite(attributeName, mode);
    return transitiveInfoCollectionToArtifact(attributeName, target);
  }

  /**
   * Equivalent to getPrerequisiteArtifact(), but also asserts that
   * host-configuration is appropriate for the specified attribute.
   */
  public Artifact getHostPrerequisiteArtifact(String attributeName) {
    TransitiveInfoCollection target = getPrerequisite(attributeName, Mode.HOST);
    return transitiveInfoCollectionToArtifact(attributeName, target);
  }

  private Artifact transitiveInfoCollectionToArtifact(
      String attributeName, TransitiveInfoCollection target) {
    if (target != null) {
      Iterable<Artifact> artifacts = target.getProvider(FileProvider.class).getFilesToBuild();
      if (Iterables.size(artifacts) == 1) {
        return Iterables.getOnlyElement(artifacts);
      } else {
        attributeError(attributeName, target.getLabel() + " expected a single artifact");
      }
    }
    return null;
  }

  /**
   * Returns the sole file in the "srcs" attribute. Reports an error and
   * (possibly) returns null if "srcs" does not identify a single file of the
   * expected type.
   */
  public Artifact getSingleSource(String fileTypeName) {
    List<Artifact> srcs = PrerequisiteArtifacts.get(this, "srcs", Mode.TARGET).list();
    switch (srcs.size()) {
      case 0 : // error already issued by getSrc()
        return null;
      case 1 : // ok
        return Iterables.getOnlyElement(srcs);
      default :
        attributeError("srcs", "only a single " + fileTypeName + " is allowed here");
        return srcs.get(0);
    }
  }

  public Artifact getSingleSource() {
    return getSingleSource(getRuleClassNameForLogging() + " source file");
  }

  /**
   * Returns a path fragment qualified by the rule name and unique fragment to
   * disambiguate artifacts produced from the source file appearing in
   * multiple rules.
   *
   * <p>For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
   */
  public final PathFragment getUniqueDirectory(String fragment) {
    return AnalysisUtils.getUniqueDirectory(getLabel(), new PathFragment(fragment));
  }

  /**
   * Check that all targets that were specified as sources are from the same
   * package as this rule. Output a warning or an error for every target that is
   * imported from a different package.
   */
  public void checkSrcsSamePackage(boolean onlyWarn) {
    PathFragment packageName = getLabel().getPackageFragment();
    for (Artifact srcItem : PrerequisiteArtifacts.get(this, "srcs", Mode.TARGET).list()) {
      if (!srcItem.isSourceArtifact()) {
        // In theory, we should not do this check. However, in practice, we
        // have a couple of rules that do not obey the "srcs must contain
        // files and only files" rule. Thus, we are stuck with this hack here :(
        continue;
      }
      Label associatedLabel = srcItem.getOwner();
      PathFragment itemPackageName = associatedLabel.getPackageFragment();
      if (!itemPackageName.equals(packageName)) {
        String message = "please do not import '" + associatedLabel + "' directly. "
            + "You should either move the file to this package or depend on "
            + "an appropriate rule there";
        if (onlyWarn) {
          attributeWarning("srcs", message);
        } else {
          attributeError("srcs", message);
        }
      }
    }
  }


  /**
   * Returns the label to which the {@code NODEP_LABEL} attribute
   * {@code attrName} refers, checking that it is a valid label, and that it is
   * referring to a local target. Reports a warning otherwise.
   */
  public Label getLocalNodepLabelAttribute(String attrName) {
    Label label = attributes().get(attrName, BuildType.NODEP_LABEL);
    if (label == null) {
      return null;
    }

    if (!getTarget().getLabel().getPackageFragment().equals(label.getPackageFragment())) {
      attributeWarning(attrName, "does not reference a local rule");
    }

    return label;
  }

  /**
   * Returns the implicit output artifact for a given template function. If multiple or no artifacts
   * can be found as a result of the template, an exception is thrown.
   */
  public Artifact getImplicitOutputArtifact(ImplicitOutputsFunction function)
      throws InterruptedException {
    Iterable<String> result;
    try {
      result = function.getImplicitOutputs(RawAttributeMapper.of(rule));
    } catch (EvalException e) {
      // It's ok as long as we don't use this method from Skylark.
      throw new IllegalStateException(e);
    }
    return getImplicitOutputArtifact(Iterables.getOnlyElement(result));
  }

  /**
   * Only use from Skylark. Returns the implicit output artifact for a given output path.
   */
  public Artifact getImplicitOutputArtifact(String path) {
    return getPackageRelativeArtifact(path, getBinOrGenfilesDirectory());
  }

  /**
   * Convenience method to return a host configured target for the "compiler"
   * attribute. Allows caller to decide whether a warning should be printed if
   * the "compiler" attribute is not set to the default value.
   *
   * @param warnIfNotDefault if true, print a warning if the value for the
   *        "compiler" attribute is set to something other than the default
   * @return a ConfiguredTarget using the host configuration for the "compiler"
   *         attribute
   */
  public final FilesToRunProvider getCompiler(boolean warnIfNotDefault) {
    Label label = attributes().get("compiler", BuildType.LABEL);
    if (warnIfNotDefault && !label.equals(getRule().getAttrDefaultValue("compiler"))) {
      attributeWarning("compiler", "setting the compiler is strongly discouraged");
    }
    return getExecutablePrerequisite("compiler", Mode.HOST);
  }

  /**
   * Returns the (unmodifiable, ordered) list of artifacts which are the outputs
   * of this target.
   *
   * <p>Each element in this list is associated with a single output, either
   * declared implicitly (via setImplicitOutputsFunction()) or explicitly
   * (listed in the 'outs' attribute of our rule).
   */
  public final ImmutableList<Artifact> getOutputArtifacts() {
    ImmutableList.Builder<Artifact> artifacts = ImmutableList.builder();
    for (OutputFile out : getRule().getOutputFiles()) {
      artifacts.add(createOutputArtifact(out));
    }
    return artifacts.build();
  }

  /**
   * Like {@link #getOutputArtifacts()} but for a singular output item.
   * Reports an error if the "out" attribute is not a singleton.
   *
   * @return null if the output list is empty, the artifact for the first item
   *         of the output list otherwise
   */
  public Artifact getOutputArtifact() {
    List<Artifact> outs = getOutputArtifacts();
    if (outs.size() != 1) {
      attributeError("out", "exactly one output file required");
      if (outs.isEmpty()) {
        return null;
      }
    }
    return outs.get(0);
  }

  /**
   * Returns an artifact with a given file extension. All other path components
   * are the same as in {@code pathFragment}.
   */
  public final Artifact getRelatedArtifact(PathFragment pathFragment, String extension) {
    PathFragment file = FileSystemUtils.replaceExtension(pathFragment, extension);
    return getDerivedArtifact(file, getConfiguration().getBinDirectory(rule.getRepository()));
  }

  /**
   * Returns true if the target for this context is a test target.
   */
  public boolean isTestTarget() {
    return TargetUtils.isTestRule(getTarget());
  }

  /**
   * Returns true if runfiles support should create the runfiles tree, or
   * false if it should just create the manifest.
   */
  public boolean shouldCreateRunfilesSymlinks() {
    // TODO(bazel-team): Ideally we wouldn't need such logic, and we'd
    // always use the BuildConfiguration#buildRunfiles() to determine
    // whether to build the runfiles. The problem is that certain build
    // steps actually consume their runfiles. These include:
    //  a. par files consumes the runfiles directory
    //     We should modify autopar to take a list of files instead.
    //     of the runfiles directory.
    //  b. host tools could potentially use data files, but currently don't
    //     (they're run from the execution root, not a runfiles tree).
    //     Currently hostConfiguration.buildRunfiles() returns true.
    if (isTestTarget()) {
      // Tests are only executed during testing (duh),
      // and their runfiles are generated lazily on local
      // execution (see LocalTestStrategy). Therefore, it
      // is safe not to build their runfiles.
      return getConfiguration().buildRunfiles();
    } else {
      return true;
    }
  }

  /**
   * @return true if {@code rule} is visible from {@code prerequisite}.
   *
   * <p>This only computes the logic as implemented by the visibility system. The final decision
   * whether a dependency is allowed is made by
   * {@link ConfiguredRuleClassProvider.PrerequisiteValidator}.
   */
  public static boolean isVisible(Rule rule, TransitiveInfoCollection prerequisite) {
    // Check visibility attribute
    for (PackageSpecification specification :
      prerequisite.getProvider(VisibilityProvider.class).getVisibility()) {
      if (specification.containsPackage(rule.getLabel().getPackageIdentifier())) {
        return true;
      }
    }

    return false;
  }

  /**
   * @return the set of features applicable for the current rule's package.
   */
  public ImmutableSet<String> getFeatures() {
    return features;
  }

  @Override
  public String toString() {
    return "RuleContext(" + getLabel() + ", " + getConfiguration() + ")";
  }

  /**
   * Builder class for a RuleContext.
   */
  public static final class Builder implements RuleErrorConsumer  {
    private final AnalysisEnvironment env;
    private final Rule rule;
    private final ConfigurationFragmentPolicy configurationFragmentPolicy;
    private Class<? extends BuildConfiguration.Fragment> universalFragment;
    private final BuildConfiguration configuration;
    private final BuildConfiguration hostConfiguration;
    private final PrerequisiteValidator prerequisiteValidator;
    private final ErrorReporter reporter;
    private OrderedSetMultimap<Attribute, ConfiguredTarget> prerequisiteMap;
    private ImmutableMap<Label, ConfigMatchingProvider> configConditions;
    private NestedSet<PackageSpecification> visibility;
    private ImmutableMap<String, Attribute> aspectAttributes;
    private ImmutableBiMap<String, Class<? extends TransitiveInfoProvider>> skylarkProviderRegistry;
    private ImmutableList<AspectDescriptor> aspectDescriptors;

    Builder(
        AnalysisEnvironment env,
        Rule rule,
        ImmutableList<AspectDescriptor> aspectDescriptors,
        BuildConfiguration configuration,
        BuildConfiguration hostConfiguration,
        PrerequisiteValidator prerequisiteValidator,
        ConfigurationFragmentPolicy configurationFragmentPolicy) {
      this.env = Preconditions.checkNotNull(env);
      this.rule = Preconditions.checkNotNull(rule);
      this.aspectDescriptors = aspectDescriptors;
      this.configurationFragmentPolicy = Preconditions.checkNotNull(configurationFragmentPolicy);
      this.configuration = Preconditions.checkNotNull(configuration);
      this.hostConfiguration = Preconditions.checkNotNull(hostConfiguration);
      this.prerequisiteValidator = prerequisiteValidator;
      reporter = new ErrorReporter(env, rule, getRuleClassNameForLogging());
    }

    RuleContext build() {
      Preconditions.checkNotNull(prerequisiteMap);
      Preconditions.checkNotNull(configConditions);
      Preconditions.checkNotNull(visibility);
      AttributeMap attributes = ConfiguredAttributeMapper.of(rule, configConditions);
      validateAttributes(attributes);
      ListMultimap<String, ConfiguredTarget> targetMap = createTargetMap();
      ListMultimap<String, ConfiguredFilesetEntry> filesetEntryMap =
          createFilesetEntryMap(rule, configConditions);
      return new RuleContext(
          this,
          attributes,
          targetMap,
          filesetEntryMap,
          configConditions,
          universalFragment,
          getRuleClassNameForLogging(),
          aspectAttributes != null ? aspectAttributes : ImmutableMap.<String, Attribute>of());
    }

    private void validateAttributes(AttributeMap attributes) {
      rule.getRuleClassObject().checkAttributesNonEmpty(rule, reporter, attributes);
    }

    Builder setVisibility(NestedSet<PackageSpecification> visibility) {
      this.visibility = visibility;
      return this;
    }

    /**
     * Sets the prerequisites and checks their visibility. It also generates appropriate error or
     * warning messages and sets the error flag as appropriate.
     */
    Builder setPrerequisites(OrderedSetMultimap<Attribute, ConfiguredTarget> prerequisiteMap) {
      this.prerequisiteMap = Preconditions.checkNotNull(prerequisiteMap);
      return this;
    }

    /**
     * Adds attributes which are defined by an Aspect (and not by RuleClass).
     */
    Builder setAspectAttributes(Map<String, Attribute> aspectAttributes) {
      this.aspectAttributes = ImmutableMap.copyOf(aspectAttributes);
      return this;
    }

    /**
     * Sets the configuration conditions needed to determine which paths to follow for this
     * rule's configurable attributes.
     */
    Builder setConfigConditions(ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
      this.configConditions = Preconditions.checkNotNull(configConditions);
      return this;
    }

    /**
     * Sets the fragment that can be legally accessed even when not explicitly declared.
     */
    Builder setUniversalFragment(Class<? extends BuildConfiguration.Fragment> fragment) {
      // TODO(bazel-team): Add this directly to ConfigurationFragmentPolicy, so we
      // don't need separate logic specifically for checking this fragment. The challenge is
      // that we need RuleClassProvider to figure out what this fragment is, and not every
      // call state that creates ConfigurationFragmentPolicy has access to that.
      this.universalFragment = fragment;
      return this;
    }

    /**
     * Sets a map that indicates which providers should be exported to skylark under the key
     * (map key).  These provider types will also be exportable by skylark rules under (map key).
     */
    Builder setSkylarkProvidersRegistry(
        ImmutableBiMap<String, Class<? extends TransitiveInfoProvider>> skylarkProviderRegistry) {
      this.skylarkProviderRegistry = skylarkProviderRegistry;
      return this;
    }

    private boolean validateFilesetEntry(FilesetEntry filesetEntry, ConfiguredTarget src) {
      if (src.getProvider(FilesetProvider.class) != null) {
        return true;
      }
      if (filesetEntry.isSourceFileset()) {
        return true;
      }

      Target srcTarget = src.getTarget();
      if (!(srcTarget instanceof FileTarget)) {
        attributeError("entries", String.format(
            "Invalid 'srcdir' target '%s'. Must be another Fileset or package",
            srcTarget.getLabel()));
        return false;
      }

      if (srcTarget instanceof OutputFile) {
        attributeWarning("entries", String.format("'srcdir' target '%s' is not an input file. "
            + "This forces the Fileset to be executed unconditionally",
            srcTarget.getLabel()));
      }

      return true;
    }

    /**
     * Determines and returns a map from attribute name to list of configured fileset entries, based
     * on a PrerequisiteMap instance.
     */
    private ListMultimap<String, ConfiguredFilesetEntry> createFilesetEntryMap(
        final Rule rule, ImmutableMap<Label, ConfigMatchingProvider> configConditions) {
      final ImmutableSortedKeyListMultimap.Builder<String, ConfiguredFilesetEntry> mapBuilder =
          ImmutableSortedKeyListMultimap.builder();
      for (Attribute attr : rule.getAttributes()) {
        if (attr.getType() != BuildType.FILESET_ENTRY_LIST) {
          continue;
        }
        String attributeName = attr.getName();
        Map<Label, ConfiguredTarget> ctMap = new HashMap<>();
        for (ConfiguredTarget prerequisite : prerequisiteMap.get(attr)) {
          ctMap.put(AliasProvider.getDependencyLabel(prerequisite), prerequisite);
        }
        List<FilesetEntry> entries = ConfiguredAttributeMapper.of(rule, configConditions)
            .get(attributeName, BuildType.FILESET_ENTRY_LIST);
        for (FilesetEntry entry : entries) {
          if (entry.getFiles() == null) {
            Label label = entry.getSrcLabel();
            ConfiguredTarget src = ctMap.get(label);
            if (!validateFilesetEntry(entry, src)) {
              continue;
            }

            mapBuilder.put(attributeName, new ConfiguredFilesetEntry(entry, src));
          } else {
            ImmutableList.Builder<TransitiveInfoCollection> files = ImmutableList.builder();
            for (Label file : entry.getFiles()) {
              files.add(ctMap.get(file));
            }
            mapBuilder.put(attributeName, new ConfiguredFilesetEntry(entry, files.build()));
          }
        }
      }
      return mapBuilder.build();
    }

    /**
     * Determines and returns a map from attribute name to list of configured targets.
     */
    private ImmutableSortedKeyListMultimap<String, ConfiguredTarget> createTargetMap() {
      ImmutableSortedKeyListMultimap.Builder<String, ConfiguredTarget> mapBuilder =
          ImmutableSortedKeyListMultimap.builder();

      for (Map.Entry<Attribute, Collection<ConfiguredTarget>> entry :
          prerequisiteMap.asMap().entrySet()) {
        Attribute attribute = entry.getKey();
        if (attribute == null) {
          continue;
        }
        if (attribute.isSilentRuleClassFilter()) {
          Predicate<RuleClass> filter = attribute.getAllowedRuleClassesPredicate();
          for (ConfiguredTarget configuredTarget : entry.getValue()) {
            Target prerequisiteTarget = configuredTarget.getTarget();
            if ((prerequisiteTarget instanceof Rule)
                && filter.apply(((Rule) prerequisiteTarget).getRuleClassObject())) {
              validateDirectPrerequisite(attribute, configuredTarget);
              mapBuilder.put(attribute.getName(), configuredTarget);
            }
          }
        } else {
          for (ConfiguredTarget configuredTarget : entry.getValue()) {
            validateDirectPrerequisite(attribute, configuredTarget);
            mapBuilder.put(attribute.getName(), configuredTarget);
          }
        }
      }
      return mapBuilder.build();
    }

    public void reportError(Location location, String message) {
      reporter.reportError(location, message);
    }

    @Override
    public void ruleError(String message) {
      reporter.ruleError(message);
    }

    @Override
    public void attributeError(String attrName, String message) {
      reporter.attributeError(attrName, message);
    }

    public void reportWarning(Location location, String message) {
      reporter.reportWarning(location, message);
    }

    @Override
    public void ruleWarning(String message) {
      reporter.ruleWarning(message);
    }

    @Override
    public void attributeWarning(String attrName, String message) {
      reporter.attributeWarning(attrName, message);
    }

    private String badPrerequisiteMessage(String targetKind, ConfiguredTarget prerequisite,
        String reason, boolean isWarning) {
      String msgPrefix = targetKind != null ? targetKind + " " : "";
      String msgReason = reason != null ? " (" + reason + ")" : "";
      if (isWarning) {
        return String.format(
            "%s%s is unexpected here%s; continuing anyway",
            msgPrefix, AliasProvider.printLabelWithAliasChain(prerequisite),
            msgReason);
      }
      return String.format("%s%s is misplaced here%s",
          msgPrefix, AliasProvider.printLabelWithAliasChain(prerequisite), msgReason);
    }

    private void reportBadPrerequisite(Attribute attribute, String targetKind,
        ConfiguredTarget prerequisite, String reason, boolean isWarning) {
      String message = badPrerequisiteMessage(targetKind, prerequisite, reason, isWarning);
      if (isWarning) {
        attributeWarning(attribute.getName(), message);
      } else {
        attributeError(attribute.getName(), message);
      }
    }

    private void validateDirectPrerequisiteType(ConfiguredTarget prerequisite,
        Attribute attribute) {
      Target prerequisiteTarget = prerequisite.getTarget();
      Label prerequisiteLabel = prerequisiteTarget.getLabel();

      if (prerequisiteTarget instanceof Rule) {
        Rule prerequisiteRule = (Rule) prerequisiteTarget;

        String reason = attribute.getValidityPredicate().checkValid(rule, prerequisiteRule);
        if (reason != null) {
          reportBadPrerequisite(attribute, prerequisiteTarget.getTargetKind(),
              prerequisite, reason, false);
        }
      }

      if (prerequisiteTarget instanceof Rule) {
        validateRuleDependency(prerequisite, attribute);
      } else if (prerequisiteTarget instanceof FileTarget) {
        if (attribute.isStrictLabelCheckingEnabled()) {
          if (!attribute.getAllowedFileTypesPredicate()
              .apply(((FileTarget) prerequisiteTarget).getFilename())) {
            if (prerequisiteTarget instanceof InputFile
                && !((InputFile) prerequisiteTarget).getPath().exists()) {
              // Misplaced labels, no corresponding target exists
              if (attribute.getAllowedFileTypesPredicate().isNone()
                  && !((InputFile) prerequisiteTarget).getFilename().contains(".")) {
                // There are no allowed files in the attribute but it's not a valid rule,
                // and the filename doesn't contain a dot --> probably a misspelled rule
                attributeError(attribute.getName(),
                    "rule '" + prerequisiteLabel + "' does not exist");
              } else {
                attributeError(attribute.getName(),
                    "target '" + prerequisiteLabel + "' does not exist");
              }
            } else {
              // The file exists but has a bad extension
              reportBadPrerequisite(attribute, "file", prerequisite,
                  "expected " + attribute.getAllowedFileTypesPredicate(), false);
            }
          }
        }
      }
    }

    /** Returns whether the context being constructed is for the evaluation of an aspect. */
    public boolean forAspect() {
      return !aspectDescriptors.isEmpty();
    }

    public Rule getRule() {
      return rule;
    }

    /**
     * Returns a rule class name suitable for log messages, including an aspect name if applicable.
     */
    public String getRuleClassNameForLogging() {
      if (aspectDescriptors.isEmpty()) {
        return rule.getRuleClass();
      }

      return Joiner.on(",").join(aspectDescriptors) + " aspect on " + rule.getRuleClass();
    }

    public BuildConfiguration getConfiguration() {
      return configuration;
    }

    /**
     * @return true if {@code rule} is visible from {@code prerequisite}.
     *
     * <p>This only computes the logic as implemented by the visibility system. The final decision
     * whether a dependency is allowed is made by
     * {@link ConfiguredRuleClassProvider.PrerequisiteValidator}, who is supposed to call this
     * method to determine whether a dependency is allowed as per visibility rules.
     */
    public boolean isVisible(TransitiveInfoCollection prerequisite) {
      return RuleContext.isVisible(rule, prerequisite);
    }

    private void validateDirectPrerequisiteFileTypes(ConfiguredTarget prerequisite,
        Attribute attribute) {
      if (attribute.isSkipAnalysisTimeFileTypeCheck()) {
        return;
      }
      FileTypeSet allowedFileTypes = attribute.getAllowedFileTypesPredicate();
      if (allowedFileTypes == null) {
        // It's not a label or label_list attribute.
        return;
      }
      if (allowedFileTypes == FileTypeSet.ANY_FILE && !attribute.isNonEmpty()
          && !attribute.isSingleArtifact()) {
        return;
      }

      // If we allow any file we still need to check if there are actually files generated
      // Note that this check only runs for ANY_FILE predicates if the attribute is NON_EMPTY
      // or SINGLE_ARTIFACT
      // If we performed this check when allowedFileTypes == NO_FILE this would
      // always throw an error in those cases
      if (allowedFileTypes != FileTypeSet.NO_FILE) {
        Iterable<Artifact> artifacts = prerequisite.getProvider(FileProvider.class)
            .getFilesToBuild();
        if (attribute.isSingleArtifact() && Iterables.size(artifacts) != 1) {
          attributeError(attribute.getName(),
              "'" + prerequisite.getLabel() + "' must produce a single file");
          return;
        }
        for (Artifact sourceArtifact : artifacts) {
          if (allowedFileTypes.apply(sourceArtifact.getFilename())) {
            return;
          }
        }
        attributeError(attribute.getName(), "'" + prerequisite.getLabel()
            + "' does not produce any " + getRuleClassNameForLogging() + " " + attribute.getName()
            + " files (expected " + allowedFileTypes + ")");
      }
    }

    private String getMissingMandatoryProviders(ConfiguredTarget prerequisite, Attribute attribute){
      ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> mandatoryProvidersList =
          attribute.getMandatoryProvidersList();
      if (mandatoryProvidersList.isEmpty()) {
        return null;
      }
      List<List<String>> missingProvidersList = new ArrayList<>();
      for (ImmutableSet<SkylarkProviderIdentifier> providers : mandatoryProvidersList) {
        List<String> missing = new ArrayList<>();
        for (SkylarkProviderIdentifier provider : providers) {
          if (prerequisite.get(provider) == null) {
            missing.add(provider.toString());
          }
        }
        if (missing.isEmpty()) {
          return null;
        } else {
          missingProvidersList.add(missing);
        }
      }
      StringBuilder missingProviders = new StringBuilder();
      Joiner joinProvider = Joiner.on("', '");
      for (List<String> providers : missingProvidersList) {
        if (missingProviders.length() > 0) {
          missingProviders.append(" or ");
        }
        missingProviders.append((providers.size() > 1) ? "[" : "")
                        .append("'");
        joinProvider.appendTo(missingProviders, providers);
        missingProviders.append("'")
                        .append((providers.size() > 1) ? "]" : "");
      }
      return missingProviders.toString();
    }

    private String getMissingMandatoryNativeProviders(
        ConfiguredTarget prerequisite, Attribute attribute) {
      List<ImmutableList<Class<? extends TransitiveInfoProvider>>> mandatoryProvidersList =
          attribute.getMandatoryNativeProvidersList();
      if (mandatoryProvidersList.isEmpty()) {
        return null;
      }
      List<List<String>> missingProvidersList = new ArrayList<>();
      for (ImmutableList<Class<? extends TransitiveInfoProvider>> providers
          : mandatoryProvidersList) {
        List<String> missing = new ArrayList<>();
        for (Class<? extends TransitiveInfoProvider> provider : providers) {
          if (prerequisite.getProvider(provider) == null) {
            missing.add(provider.getSimpleName());
          }
        }
        if (missing.isEmpty()) {
          return null;
        } else {
          missingProvidersList.add(missing);
        }
      }
      StringBuilder missingProviders = new StringBuilder();
      Joiner joinProvider = Joiner.on(", ");
      for (List<String> providers : missingProvidersList) {
        if (missingProviders.length() > 0) {
          missingProviders.append(" or ");
        }
        missingProviders.append((providers.size() > 1) ? "[" : "");
        joinProvider.appendTo(missingProviders, providers);
        missingProviders.append((providers.size() > 1) ? "]" : "");
      }
      return missingProviders.toString();
    }

    /**
     * Because some rules still have to use allowedRuleClasses to do rule dependency validation.
     * A dependency is valid if it is from a rule in allowedRuledClasses, OR if all of the providers
     * in mandatoryNativeProviders AND mandatoryProvidersList are provided by the target.
     */
    private void validateRuleDependency(ConfiguredTarget prerequisite, Attribute attribute) {
      Target prerequisiteTarget = prerequisite.getTarget();
      RuleClass ruleClass = ((Rule) prerequisiteTarget).getRuleClassObject();
      String notAllowedRuleClassesMessage = null;

      if (attribute.getAllowedRuleClassesPredicate() != Predicates.<RuleClass>alwaysTrue()) {
        if (attribute.getAllowedRuleClassesPredicate().apply(ruleClass)) {
          // prerequisite has an allowed rule class => accept.
          return;
        }
        // remember that the rule class that was not allowed;
        // but maybe prerequisite provides required providers? do not reject yet.
        notAllowedRuleClassesMessage =
            badPrerequisiteMessage(
                prerequisiteTarget.getTargetKind(),
                prerequisite,
                "expected " + attribute.getAllowedRuleClassesPredicate(),
                false);
      }

      if (attribute.getAllowedRuleClassesWarningPredicate() != Predicates.<RuleClass>alwaysTrue()) {
        Predicate<RuleClass> warningPredicate = attribute.getAllowedRuleClassesWarningPredicate();
        if (warningPredicate.apply(ruleClass)) {
          reportBadPrerequisite(attribute, prerequisiteTarget.getTargetKind(), prerequisite,
              "expected " + attribute.getAllowedRuleClassesPredicate(), true);
          // prerequisite has a rule class allowed with a warning => accept, emitting a warning.
          return;
        }
      }

      // If we got there, we have no allowed rule class.
      // If mandatory providers are specified, try them.
      Set<String> unfulfilledRequirements = new LinkedHashSet<>();
      if (!attribute.getMandatoryNativeProvidersList().isEmpty()
          || !attribute.getMandatoryProvidersList().isEmpty()) {
        boolean hadAllMandatoryProviders = true;

        String missingNativeProviders = getMissingMandatoryNativeProviders(prerequisite, attribute);
        if (missingNativeProviders != null) {
          unfulfilledRequirements.add(
              "'" + prerequisite.getLabel() + "' does not have mandatory providers: "
                  + missingNativeProviders);
          hadAllMandatoryProviders = false;
        }

        String missingProviders = getMissingMandatoryProviders(prerequisite, attribute);
        if (missingProviders != null) {
          unfulfilledRequirements.add(
              "'" + prerequisite.getLabel() + "' does not have mandatory provider "
                  + missingProviders);
          hadAllMandatoryProviders = false;
        }

        if (hadAllMandatoryProviders) {
          // all mandatory providers are present => accept.
          return;
        }
      }

      // not allowed rule class and some mandatory providers missing => reject.
      if (notAllowedRuleClassesMessage != null) {
        unfulfilledRequirements.add(notAllowedRuleClassesMessage);
      }

      if (!unfulfilledRequirements.isEmpty()) {
        attributeError(
            attribute.getName(), StringUtil.joinEnglishList(unfulfilledRequirements, "and"));
      }
    }

    private void validateDirectPrerequisite(Attribute attribute, ConfiguredTarget prerequisite) {
      validateDirectPrerequisiteType(prerequisite, attribute);
      validateDirectPrerequisiteFileTypes(prerequisite, attribute);
      if (attribute.performPrereqValidatorCheck()) {
        prerequisiteValidator.validate(this, prerequisite, attribute);
      }
    }
  }

  /**
   * Helper class for reporting errors and warnings.
   */
  public static final class ErrorReporter implements RuleErrorConsumer {
    private final AnalysisEnvironment env;
    private final Rule rule;
    private final String ruleClassNameForLogging;

    ErrorReporter(AnalysisEnvironment env, Rule rule, String ruleClassNameForLogging) {
      this.env = env;
      this.rule = rule;
      this.ruleClassNameForLogging = ruleClassNameForLogging;
    }

    public void reportError(Location location, String message) {
      env.getEventHandler().handle(Event.error(location, message));
    }

    @Override
    public void ruleError(String message) {
      reportError(rule.getLocation(), prefixRuleMessage(message));
    }

    @Override
    public void attributeError(String attrName, String message) {
      reportError(rule.getAttributeLocation(attrName), completeAttributeMessage(attrName, message));
    }

    public void reportWarning(Location location, String message) {
      env.getEventHandler().handle(Event.warn(location, message));
    }

    @Override
    public void ruleWarning(String message) {
      env.getEventHandler().handle(Event.warn(rule.getLocation(), prefixRuleMessage(message)));
    }

    @Override
    public void attributeWarning(String attrName, String message) {
      reportWarning(
          rule.getAttributeLocation(attrName), completeAttributeMessage(attrName, message));
    }

    private String prefixRuleMessage(String message) {
      return String.format(
          "in %s rule %s: %s", getRuleClassNameForLogging(), rule.getLabel(), message);
    }

    private String maskInternalAttributeNames(String name) {
      return Attribute.isImplicit(name) ? "(an implicit dependency)" : name;
    }

    /**
     * Prefixes the given message with details about the rule and appends details about the macro
     * that created this rule, if applicable.
     */
    private String completeAttributeMessage(String attrName, String message) {
      // Appends a note to the given message if the offending rule was created by a macro.
      String macroMessageAppendix =
          rule.wasCreatedByMacro()
              ? String.format(
                  ". Since this rule was created by the macro '%s', the error might have been "
                  + "caused by the macro implementation in %s",
                  getGeneratorFunction(), rule.getAttributeLocationWithoutMacro(attrName))
              : "";

      return String.format("in %s attribute of %s rule %s: %s%s",
          maskInternalAttributeNames(attrName), getRuleClassNameForLogging(), rule.getLabel(),
          message, macroMessageAppendix);
    }

    /**
     * Returns a rule class name suitable for log messages, including an aspect name if applicable.
     */
    private String getRuleClassNameForLogging() {
      return ruleClassNameForLogging;
    }

    private String getGeneratorFunction() {
      return (String) rule.getAttributeContainer().getAttr("generator_function");
    }
  }
}
