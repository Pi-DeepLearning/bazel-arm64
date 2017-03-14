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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactPrefixConflictException;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.AnalysisFailureEvent;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.LabelAndConfiguration;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BinTools;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingFailureEvent;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner;
import com.google.devtools.build.lib.skyframe.AspectFunction.AspectCreationException;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectValueKey;
import com.google.devtools.build.lib.skyframe.BuildInfoCollectionValue.BuildInfoKeyAndConfig;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ConflictException;
import com.google.devtools.build.lib.skyframe.SkylarkImportLookupFunction.SkylarkImportFailedException;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Skyframe-based driver of analysis.
 *
 * <p>Covers enough functionality to work as a substitute for {@code BuildView#configureTargets}.
 */
public final class SkyframeBuildView {
  private static Logger LOG = Logger.getLogger(BuildView.class.getName());

  private final ConfiguredTargetFactory factory;
  private final ArtifactFactory artifactFactory;
  private final SkyframeExecutor skyframeExecutor;
  private final BinTools binTools;
  private boolean enableAnalysis = false;

  // This hack allows us to see when a configured target has been invalidated, and thus when the set
  // of artifact conflicts needs to be recomputed (whenever a configured target has been invalidated
  // or newly evaluated).
  private final EvaluationProgressReceiver progressReceiver =
      new ConfiguredTargetValueProgressReceiver();
  private final Set<SkyKey> evaluatedConfiguredTargets = Sets.newConcurrentHashSet();
  // Used to see if checks of graph consistency need to be done after analysis.
  private volatile boolean someConfiguredTargetEvaluated = false;

  // We keep the set of invalidated configuration target keys so that we can know if something
  // has been invalidated after graph pruning has been executed.
  private Set<SkyKey> dirtiedConfiguredTargetKeys = Sets.newConcurrentHashSet();
  private volatile boolean anyConfiguredTargetDeleted = false;

  private final RuleClassProvider ruleClassProvider;

  // The host configuration containing all fragments used by this build's transitive closure.
  private BuildConfiguration topLevelHostConfiguration;
  // Fragment-limited versions of the host configuration. It's faster to create/cache these here
  // than to store them in Skyframe.
  private Map<Set<Class<? extends BuildConfiguration.Fragment>>, BuildConfiguration>
      hostConfigurationCache = Maps.newConcurrentMap();

  private BuildConfigurationCollection configurations;

  /**
   * If the last build was executed with {@code Options#discard_analysis_cache} and we are not
   * running Skyframe full, we should clear the legacy data since it is out-of-sync.
   */
  private boolean skyframeAnalysisWasDiscarded;

  public SkyframeBuildView(BlazeDirectories directories,
      SkyframeExecutor skyframeExecutor, BinTools binTools,
      ConfiguredRuleClassProvider ruleClassProvider) {
    this.factory = new ConfiguredTargetFactory(ruleClassProvider);
    this.artifactFactory = new ArtifactFactory(
        directories.getExecRoot().getParentDirectory(), directories.getRelativeOutputPath());
    this.skyframeExecutor = skyframeExecutor;
    this.binTools = binTools;
    this.ruleClassProvider = ruleClassProvider;
  }

  public void resetEvaluatedConfiguredTargetKeysSet() {
    evaluatedConfiguredTargets.clear();
  }

  public Set<SkyKey> getEvaluatedTargetKeys() {
    return ImmutableSet.copyOf(evaluatedConfiguredTargets);
  }

  ConfiguredTargetFactory getConfiguredTargetFactory() {
    return factory;
  }

  /**
   * Sets the configurations. Not thread-safe. DO NOT CALL except from tests!
   */
  @VisibleForTesting
  public void setConfigurations(BuildConfigurationCollection configurations) {
    // Clear all cached ConfiguredTargets on configuration change of if --discard_analysis_cache
    // was set on the previous build. In the former case, it's not required for correctness, but
    // prevents unbounded memory usage.
    if ((this.configurations != null && !configurations.equals(this.configurations))
        || skyframeAnalysisWasDiscarded) {
      LOG.info("Discarding analysis cache: configurations have changed.");
      skyframeExecutor.dropConfiguredTargets();
    }
    skyframeAnalysisWasDiscarded = false;
    this.configurations = configurations;
    setTopLevelHostConfiguration(configurations.getHostConfiguration());
  }

  /**
   * Sets the host configuration consisting of all fragments that will be used by the top level
   * targets' transitive closures.
   *
   * <p>This is used to power {@link #getHostConfiguration} during analysis, which computes
   * fragment-trimmed host configurations from the top-level one.
   */
  private void setTopLevelHostConfiguration(BuildConfiguration topLevelHostConfiguration) {
    if (topLevelHostConfiguration.equals(this.topLevelHostConfiguration)) {
      return;
    }
    hostConfigurationCache.clear();
    this.topLevelHostConfiguration = topLevelHostConfiguration;
  }

  /**
   * Drops the analysis cache. If building with Skyframe, targets in {@code topLevelTargets} may
   * remain in the cache for use during the execution phase.
   *
   * @see com.google.devtools.build.lib.analysis.BuildView.Options#discardAnalysisCache
   */
  public void clearAnalysisCache(Collection<ConfiguredTarget> topLevelTargets) {
    // TODO(bazel-team): Consider clearing packages too to save more memory.
    skyframeAnalysisWasDiscarded = true;
    skyframeExecutor.clearAnalysisCache(topLevelTargets);
  }

  /**
   * Analyzes the specified targets using Skyframe as the driving framework.
   *
   * @return the configured targets that should be built along with a WalkableGraph of the analysis.
   */
  public SkyframeAnalysisResult configureTargets(
      EventHandler eventHandler,
      List<ConfiguredTargetKey> values,
      List<AspectValueKey> aspectKeys,
      EventBus eventBus,
      boolean keepGoing,
      int numThreads)
      throws InterruptedException, ViewCreationFailedException {
    enableAnalysis(true);
    EvaluationResult<ActionLookupValue> result;
    try {
      result =
          skyframeExecutor.configureTargets(
              eventHandler, values, aspectKeys, keepGoing, numThreads);
    } finally {
      enableAnalysis(false);
    }
    ImmutableMap<ActionAnalysisMetadata, ConflictException> badActions =
        skyframeExecutor.findArtifactConflicts();

    Collection<AspectValue> goodAspects = Lists.newArrayListWithCapacity(values.size());
    NestedSetBuilder<Package> packages = NestedSetBuilder.stableOrder();
    for (AspectValueKey aspectKey : aspectKeys) {
      AspectValue value = (AspectValue) result.get(aspectKey.getSkyKey());
      if (value == null) {
        // Skip aspects that couldn't be applied to targets.
        continue;
      }
      goodAspects.add(value);
      packages.addTransitive(value.getTransitivePackages());
    }

    // Filter out all CTs that have a bad action and convert to a list of configured targets. This
    // code ensures that the resulting list of configured targets has the same order as the incoming
    // list of values, i.e., that the order is deterministic.
    Collection<ConfiguredTarget> goodCts = Lists.newArrayListWithCapacity(values.size());
    for (ConfiguredTargetKey value : values) {
      ConfiguredTargetValue ctValue =
          (ConfiguredTargetValue) result.get(ConfiguredTargetValue.key(value));
      if (ctValue == null) {
        continue;
      }
      goodCts.add(ctValue.getConfiguredTarget());
      packages.addTransitive(ctValue.getTransitivePackages());
    }
    ImmutableMap<PackageIdentifier, Path> packageRoots =
        LoadingPhaseRunner.collectPackageRoots(packages.build().toCollection());

    if (!result.hasError() && badActions.isEmpty()) {
      return new SkyframeAnalysisResult(
          /*hasLoadingError=*/false, /*hasAnalysisError=*/false,
          ImmutableList.copyOf(goodCts),
          result.getWalkableGraph(),
          ImmutableList.copyOf(goodAspects),
          packageRoots);
    }

    // --nokeep_going so we fail with an exception for the first error.
    // TODO(bazel-team): We might want to report the other errors through the event bus but
    // for keeping this code in parity with legacy we just report the first error for now.
    if (!keepGoing) {
      for (Map.Entry<ActionAnalysisMetadata, ConflictException> bad : badActions.entrySet()) {
        ConflictException ex = bad.getValue();
        try {
          ex.rethrowTyped();
        } catch (MutableActionGraph.ActionConflictException ace) {
          ace.reportTo(eventHandler);
          String errorMsg = "Analysis of target '" + bad.getKey().getOwner().getLabel()
              + "' failed; build aborted";
          throw new ViewCreationFailedException(errorMsg);
        } catch (ArtifactPrefixConflictException apce) {
          eventHandler.handle(Event.error(apce.getMessage()));
        }
        throw new ViewCreationFailedException(ex.getMessage());
      }

      Map.Entry<SkyKey, ErrorInfo> error = result.errorMap().entrySet().iterator().next();
      SkyKey topLevel = error.getKey();
      ErrorInfo errorInfo = error.getValue();
      assertSaneAnalysisError(errorInfo, topLevel);
      skyframeExecutor.getCyclesReporter().reportCycles(errorInfo.getCycleInfo(), topLevel,
          eventHandler);
      Throwable cause = errorInfo.getException();
      Preconditions.checkState(cause != null || !Iterables.isEmpty(errorInfo.getCycleInfo()),
          errorInfo);
      String errorMsg = null;
      if (topLevel.argument() instanceof ConfiguredTargetKey) {
        errorMsg =
            "Analysis of target '"
                + ConfiguredTargetValue.extractLabel(topLevel)
                + "' failed; build aborted";
      } else if (topLevel.argument() instanceof AspectValueKey) {
        AspectValueKey aspectKey = (AspectValueKey) topLevel.argument();
        errorMsg = "Analysis of aspect '" + aspectKey.getDescription() + "' failed; build aborted";
      } else {
        assert false;
      }
      if (cause instanceof ActionConflictException) {
        ((ActionConflictException) cause).reportTo(eventHandler);
      }
      throw new ViewCreationFailedException(errorMsg);
    }

    boolean hasLoadingError = false;
    // --keep_going : We notify the error and return a ConfiguredTargetValue
    for (Map.Entry<SkyKey, ErrorInfo> errorEntry : result.errorMap().entrySet()) {
      // Only handle errors of configured targets, not errors of top-level aspects.
      // TODO(ulfjack): this is quadratic - if there are a lot of CTs, this could be rather slow.
      if (!values.contains(errorEntry.getKey().argument())) {
        continue;
      }
      SkyKey errorKey = errorEntry.getKey();
      ConfiguredTargetKey label = (ConfiguredTargetKey) errorKey.argument();
      Label topLevelLabel = label.getLabel();
      ErrorInfo errorInfo = errorEntry.getValue();
      assertSaneAnalysisError(errorInfo, errorKey);

      skyframeExecutor.getCyclesReporter().reportCycles(errorInfo.getCycleInfo(), errorKey,
          eventHandler);
      Exception cause = errorInfo.getException();
      Label analysisRootCause = null;
      if (cause instanceof ConfiguredValueCreationException) {
        ConfiguredValueCreationException ctCause = (ConfiguredValueCreationException) cause;
        for (Label rootCause : ctCause.getRootCauses()) {
          hasLoadingError = true;
          eventBus.post(new LoadingFailureEvent(topLevelLabel, rootCause));
        }
        analysisRootCause = ctCause.getAnalysisRootCause();
      } else if (!Iterables.isEmpty(errorInfo.getCycleInfo())) {
        analysisRootCause = maybeGetConfiguredTargetCycleCulprit(
            topLevelLabel, errorInfo.getCycleInfo());
      } else if (cause instanceof ActionConflictException) {
        ((ActionConflictException) cause).reportTo(eventHandler);
      }
      eventHandler.handle(
          Event.warn("errors encountered while analyzing target '"
              + topLevelLabel + "': it will not be built"));
      if (analysisRootCause != null) {
        eventBus.post(new AnalysisFailureEvent(
            LabelAndConfiguration.of(topLevelLabel, label.getConfiguration()), analysisRootCause));
      }
    }

    Collection<Exception> reportedExceptions = Sets.newHashSet();
    for (Map.Entry<ActionAnalysisMetadata, ConflictException> bad : badActions.entrySet()) {
      ConflictException ex = bad.getValue();
      try {
        ex.rethrowTyped();
      } catch (MutableActionGraph.ActionConflictException ace) {
        ace.reportTo(eventHandler);
        eventHandler
            .handle(Event.warn("errors encountered while analyzing target '"
                + bad.getKey().getOwner().getLabel() + "': it will not be built"));
      } catch (ArtifactPrefixConflictException apce) {
        if (reportedExceptions.add(apce)) {
          eventHandler.handle(Event.error(apce.getMessage()));
        }
      }
    }

    if (!badActions.isEmpty()) {
      // In order to determine the set of configured targets transitively error free from action
      // conflict issues, we run a post-processing update() that uses the bad action map.
      EvaluationResult<PostConfiguredTargetValue> actionConflictResult =
          skyframeExecutor.postConfigureTargets(eventHandler, values, keepGoing, badActions);

      goodCts = Lists.newArrayListWithCapacity(values.size());
      for (ConfiguredTargetKey value : values) {
        PostConfiguredTargetValue postCt =
            actionConflictResult.get(PostConfiguredTargetValue.key(value));
        if (postCt != null) {
          goodCts.add(postCt.getCt());
        }
      }
    }

    return new SkyframeAnalysisResult(
        hasLoadingError,
        result.hasError() || !badActions.isEmpty(),
        ImmutableList.copyOf(goodCts),
        result.getWalkableGraph(),
        ImmutableList.copyOf(goodAspects),
        packageRoots);
  }

  @Nullable
  private static Label maybeGetConfiguredTargetCycleCulprit(
      Label labelToLoad, Iterable<CycleInfo> cycleInfos) {
    for (CycleInfo cycleInfo : cycleInfos) {
      SkyKey culprit = Iterables.getFirst(cycleInfo.getCycle(), null);
      if (culprit == null) {
        continue;
      }
      if (culprit.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        return ((ConfiguredTargetKey) culprit.argument()).getLabel();
      } else if (culprit.functionName().equals(SkyFunctions.TRANSITIVE_TARGET)) {
        return (Label) culprit.argument();
      } else {
        return labelToLoad;
      }
    }
    return null;
  }

  private static void assertSaneAnalysisError(ErrorInfo errorInfo, SkyKey key) {
    Throwable cause = errorInfo.getException();
    if (cause != null) {
      // We should only be trying to configure targets when the loading phase succeeds, meaning
      // that the only errors should be analysis errors.
      Preconditions.checkState(
          cause instanceof ConfiguredValueCreationException
              || cause instanceof ActionConflictException
              // For top-level aspects
              || cause instanceof AspectCreationException
              || cause instanceof SkylarkImportFailedException
              // Only if we run the reduced loading phase and then analyze with --nokeep_going.
              || cause instanceof NoSuchTargetException
              || cause instanceof NoSuchPackageException,
          "%s -> %s",
          key,
          errorInfo);
    }
  }

  public ArtifactFactory getArtifactFactory() {
    return artifactFactory;
  }

  /**
   * Because we don't know what build-info artifacts this configured target may request, we
   * conservatively register a dep on all of them.
   */
  // TODO(bazel-team): Allow analysis to return null so the value builder can exit and wait for a
  // restart deps are not present.
  private static boolean getWorkspaceStatusValues(Environment env, BuildConfiguration config)
      throws InterruptedException {
    env.getValue(WorkspaceStatusValue.SKY_KEY);
    Map<BuildInfoKey, BuildInfoFactory> buildInfoFactories =
        PrecomputedValue.BUILD_INFO_FACTORIES.get(env);
    if (buildInfoFactories == null) {
      return false;
    }
    // These factories may each create their own build info artifacts, all depending on the basic
    // build-info.txt and build-changelist.txt.
    List<SkyKey> depKeys = Lists.newArrayList();
    for (BuildInfoKey key : buildInfoFactories.keySet()) {
      if (buildInfoFactories.get(key).isEnabled(config)) {
        depKeys.add(BuildInfoCollectionValue.key(new BuildInfoKeyAndConfig(key, config)));
      }
    }
    env.getValues(depKeys);
    return !env.valuesMissing();
  }

  /** Returns null if any build-info values are not ready. */
  @Nullable
  CachingAnalysisEnvironment createAnalysisEnvironment(
      ArtifactOwner owner,
      boolean isSystemEnv,
      EventHandler eventHandler,
      Environment env,
      BuildConfiguration config)
      throws InterruptedException {
    if (config != null && !getWorkspaceStatusValues(env, config)) {
      return null;
    }
    boolean extendedSanityChecks = config != null && config.extendedSanityChecks();
    boolean allowRegisteringActions = config == null || config.isActionsEnabled();
    return new CachingAnalysisEnvironment(
        artifactFactory, owner, isSystemEnv, extendedSanityChecks, eventHandler, env,
        allowRegisteringActions, binTools);
  }

  /**
   * Invokes the appropriate constructor to create a {@link ConfiguredTarget} instance.
   *
   * <p>For use in {@code ConfiguredTargetFunction}.
   *
   * <p>Returns null if Skyframe deps are missing or upon certain errors.
   */
  @Nullable
  ConfiguredTarget createConfiguredTarget(Target target, BuildConfiguration configuration,
      CachingAnalysisEnvironment analysisEnvironment,
      OrderedSetMultimap<Attribute, ConfiguredTarget> prerequisiteMap,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions) throws InterruptedException {
    Preconditions.checkState(enableAnalysis,
        "Already in execution phase %s %s", target, configuration);
    Preconditions.checkNotNull(analysisEnvironment);
    Preconditions.checkNotNull(target);
    Preconditions.checkNotNull(prerequisiteMap);
    return factory.createConfiguredTarget(analysisEnvironment, artifactFactory, target,
        configuration, getHostConfiguration(configuration), prerequisiteMap,
        configConditions);
  }

  /**
   * Returns the host configuration trimmed to the same fragments as the input configuration. If
   * the input is null, returns the top-level host configuration.
   *
   * <p>For static configurations, this unconditionally returns the (sole) top-level configuration.
   *
   * <p>This may only be called after {@link #setTopLevelHostConfiguration} has set the
   * correct host configuration at the top-level.
   */
  public BuildConfiguration getHostConfiguration(BuildConfiguration config) {
    if (config == null || !config.useDynamicConfigurations()) {
      return topLevelHostConfiguration;
    }
    // TODO(bazel-team): have the fragment classes be those required by the consuming target's
    // transitive closure. This isn't the same as the input configuration's fragment classes -
    // the latter may be a proper subset of the former.
    //
    // ConfigurationFactory.getConfiguration provides the reason why: if a declared required
    // fragment is evaluated and returns null, it never gets added to the configuration. So if we
    // use the configuration's fragments as the source of truth, that excludes required fragments
    // that never made it in.
    //
    // If we're just trimming an existing configuration, this is no big deal (if the original
    // configuration doesn't need the fragment, the trimmed one doesn't either). But this method
    // trims a host configuration to the same scope as a target configuration. Since their options
    // are different, the host instance may actually be able to produce the fragment. So it's
    // wrong and potentially dangerous to unilaterally exclude it.
    Set<Class<? extends BuildConfiguration.Fragment>> fragmentClasses =
        config.trimConfigurations()
            ? config.fragmentClasses()
            : ((ConfiguredRuleClassProvider) ruleClassProvider).getAllFragments();
    BuildConfiguration hostConfig = hostConfigurationCache.get(fragmentClasses);
    if (hostConfig != null) {
      return hostConfig;
    }
    // TODO(bazel-team): investigate getting the trimmed config from Skyframe instead of cloning.
    // This is the only place we instantiate BuildConfigurations outside of Skyframe, This can
    // produce surprising effects, such as requesting a configuration that's in the Skyframe cache
    // but still produces a unique instance because we don't check that cache. It'd be nice to
    // guarantee that *all* instantiations happen through Skyframe. That could, for example,
    // guarantee that config1.equals(config2) implies config1 == config2, which is nice for
    // verifying we don't accidentally create extra configurations. But unfortunately,
    // hostConfigurationCache was specifically created because Skyframe is too slow for this use
    // case. So further optimization is necessary to make that viable (proto_library in particular
    // contributes to much of the difference).
    BuildConfiguration trimmedConfig =
        topLevelHostConfiguration.clone(fragmentClasses, ruleClassProvider);
    hostConfigurationCache.put(fragmentClasses, trimmedConfig);
    return trimmedConfig;
  }

  SkyframeDependencyResolver createDependencyResolver(Environment env) {
    return new SkyframeDependencyResolver(env);
  }

  /**
   * Workaround to clear all legacy data, like the artifact factory. We need
   * to clear them to avoid conflicts.
   * TODO(bazel-team): Remove this workaround. [skyframe-execution]
   */
  void clearLegacyData() {
    artifactFactory.clear();
  }

  /**
   * Hack to invalidate actions in legacy action graph when their values are invalidated in
   * skyframe.
   */
  EvaluationProgressReceiver getProgressReceiver() {
    return progressReceiver;
  }

  /** Clear the invalidated configured targets detected during loading and analysis phases. */
  public void clearInvalidatedConfiguredTargets() {
    dirtiedConfiguredTargetKeys = Sets.newConcurrentHashSet();
    anyConfiguredTargetDeleted = false;
  }

  public boolean isSomeConfiguredTargetInvalidated() {
    return anyConfiguredTargetDeleted || !dirtiedConfiguredTargetKeys.isEmpty();
  }

  /**
   * Called from SkyframeExecutor to see whether the graph needs to be checked for artifact
   * conflicts. Returns true if some configured target has been evaluated since the last time the
   * graph was checked for artifact conflicts (with that last time marked by a call to
   * {@link #resetEvaluatedConfiguredTargetFlag()}).
   */
  boolean isSomeConfiguredTargetEvaluated() {
    Preconditions.checkState(!enableAnalysis);
    return someConfiguredTargetEvaluated;
  }

  /**
   * Called from SkyframeExecutor after the graph is checked for artifact conflicts so that
   * the next time {@link #isSomeConfiguredTargetEvaluated} is called, it will return true only if
   * some configured target has been evaluated since the last check for artifact conflicts.
   */
  void resetEvaluatedConfiguredTargetFlag() {
    someConfiguredTargetEvaluated = false;
  }

  /**
   * {@link #createConfiguredTarget} will only create configured targets if this is set to true. It
   * should be set to true before any Skyframe update call that might call into {@link
   * #createConfiguredTarget}, and false immediately after the call. Use it to fail-fast in the case
   * that a target is requested for analysis not during the analysis phase.
   */
  void enableAnalysis(boolean enable) {
    this.enableAnalysis = enable;
  }

  private class ConfiguredTargetValueProgressReceiver implements EvaluationProgressReceiver {
    @Override
    public void invalidated(SkyKey skyKey, InvalidationState state) {
      if (skyKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        if (state == InvalidationState.DELETED) {
          anyConfiguredTargetDeleted = true;
        } else {
          // If the value was just dirtied and not deleted, then it may not be truly invalid, since
          // it may later get re-validated. Therefore adding the key to dirtiedConfiguredTargetKeys
          // is provisional--if the key is later evaluated and the value found to be clean, then we
          // remove it from the set.
          dirtiedConfiguredTargetKeys.add(skyKey);
        }
      }
    }

    @Override
    public void enqueueing(SkyKey skyKey) {}

    @Override
    public void computed(SkyKey skyKey, long elapsedTimeNanos) {}

    @Override
    public void evaluated(SkyKey skyKey, Supplier<SkyValue> skyValueSupplier,
        EvaluationState state) {
      if (skyKey.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
        switch (state) {
          case BUILT:
            if (skyValueSupplier.get() != null) {
              evaluatedConfiguredTargets.add(skyKey);
              // During multithreaded operation, this is only set to true, so no concurrency issues.
              someConfiguredTargetEvaluated = true;
            }
            break;
          case CLEAN:
            // If the configured target value did not need to be rebuilt, then it wasn't truly
            // invalid.
            dirtiedConfiguredTargetKeys.remove(skyKey);
            break;
        }
      }
    }
  }
}
