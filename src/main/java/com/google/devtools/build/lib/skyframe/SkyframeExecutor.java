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

import static com.google.devtools.build.lib.concurrent.Uninterruptibles.callUninterruptibly;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionCacheChecker;
import com.google.devtools.build.lib.actions.ActionExecutionContextFactory;
import com.google.devtools.build.lib.actions.ActionExecutionStatusReporter;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.ActionLogBufferPathGenerator;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.PackageRootResolutionException;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BuildView.Options;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.MergedConfiguredTarget;
import com.google.devtools.build.lib.analysis.MergedConfiguredTarget.DuplicateException;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction.Factory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BinTools;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationFactory;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.OutputService;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.Builder;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Preprocessor;
import com.google.devtools.build.lib.packages.Preprocessor.AstAfterPreprocessing;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingCallback;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner;
import com.google.devtools.build.lib.pkgcache.LoadingResult;
import com.google.devtools.build.lib.pkgcache.PackageCacheOptions;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetParsingCompleteEvent;
import com.google.devtools.build.lib.pkgcache.TestFilter;
import com.google.devtools.build.lib.pkgcache.TransitivePackageLoader;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectValueKey;
import com.google.devtools.build.lib.skyframe.DirtinessCheckerUtils.FileDirtinessChecker;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageFunction.CacheEntryWithGlobDeps;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupValue.BuildFileName;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ActionCompletedReceiver;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.ProgressSupplier;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.ResourceUsage;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.BuildDriver;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.Differencer;
import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.ImmutableDiff;
import com.google.devtools.build.skyframe.Injectable;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator.EvaluatorSupplier;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph.WalkableGraphFactory;
import com.google.devtools.common.options.OptionsClassProvider;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A helper object to support Skyframe-driven execution.
 *
 * <p>This object is mostly used to inject external state, such as the executor engine or
 * some additional artifacts (workspace status and build info artifacts) into SkyFunctions
 * for use during the build.
 */
public abstract class SkyframeExecutor implements WalkableGraphFactory {
  private final EvaluatorSupplier evaluatorSupplier;
  protected MemoizingEvaluator memoizingEvaluator;
  private final MemoizingEvaluator.EmittedEventState emittedEventState =
      new MemoizingEvaluator.EmittedEventState();
  private final PackageFactory pkgFactory;
  private final WorkspaceStatusAction.Factory workspaceStatusActionFactory;
  private final BlazeDirectories directories;
  protected final ExternalFilesHelper externalFilesHelper;
  @Nullable
  private OutputService outputService;

  // TODO(bazel-team): Figure out how to handle value builders that block internally. Blocking
  // operations may need to be handled in another (bigger?) thread pool. Also, we should detect
  // the number of cores and use that as the thread-pool size for CPU-bound operations.
  // I just bumped this to 200 to get reasonable execution phase performance; that may cause
  // significant overhead for CPU-bound processes (i.e. analysis). [skyframe-analysis]
  @VisibleForTesting
  public static final int DEFAULT_THREAD_COUNT =
      // Reduce thread count while running tests of Bazel. Test cases are typically small, and large
      // thread pools vying for a relatively small number of CPU cores may induce non-optimal
      // performance.
      System.getenv("TEST_TMPDIR") == null ? 200 : 5;

  // Cache of partially constructed Package instances, stored between reruns of the PackageFunction
  // (because of missing dependencies, within the same evaluate() run) to avoid loading the same
  // package twice (first time loading to find subincludes and declare value dependencies).
  // TODO(bazel-team): remove this cache once we have skyframe-native package loading
  // [skyframe-loading]
  private final Cache<PackageIdentifier, CacheEntryWithGlobDeps<Package.Builder>>
      packageFunctionCache = newPkgFunctionCache();
  private final Cache<PackageIdentifier, CacheEntryWithGlobDeps<AstAfterPreprocessing>> astCache =
      newAstCache();

  private final AtomicInteger numPackagesLoaded = new AtomicInteger(0);
  private final PackageProgressReceiver packageProgress = new PackageProgressReceiver();

  protected SkyframeBuildView skyframeBuildView;
  private ActionLogBufferPathGenerator actionLogBufferPathGenerator;

  protected BuildDriver buildDriver;

  // AtomicReferences are used here as mutable boxes shared with value builders.
  private final AtomicBoolean showLoadingProgress = new AtomicBoolean();
  protected final AtomicReference<UnixGlob.FilesystemCalls> syscalls =
      new AtomicReference<>(UnixGlob.DEFAULT_SYSCALLS);
  protected final AtomicReference<PathPackageLocator> pkgLocator =
      new AtomicReference<>();
  protected final AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages =
      new AtomicReference<>(ImmutableSet.<PackageIdentifier>of());
  private final AtomicReference<EventBus> eventBus = new AtomicReference<>();
  protected final AtomicReference<TimestampGranularityMonitor> tsgm =
      new AtomicReference<>();

  private final ImmutableList<BuildInfoFactory> buildInfoFactories;
  // Under normal circumstances, the artifact factory persists for the life of a Blaze server, but
  // since it is not yet created when we create the value builders, we have to use a supplier,
  // initialized when the build view is created.
  private final MutableSupplier<ArtifactFactory> artifactFactory = new MutableSupplier<>();
  // Used to give to WriteBuildInfoAction via a supplier. Relying on BuildVariableValue.BUILD_ID
  // would be preferable, but we have no way to have the Action depend on that value directly.
  // Having the BuildInfoFunction own the supplier is currently not possible either, because then
  // it would be invalidated on every build, since it would depend on the build id value.
  private MutableSupplier<UUID> buildId = new MutableSupplier<>();

  protected boolean active = true;
  private final SkyframePackageManager packageManager;

  private final Preprocessor.Factory.Supplier preprocessorFactorySupplier;
  private Preprocessor.Factory preprocessorFactory;

  private final ResourceManager resourceManager;

  /** Used to lock evaluator on legacy calls to get existing values. */
  private final Object valueLookupLock = new Object();
  private final AtomicReference<ActionExecutionStatusReporter> statusReporterRef =
      new AtomicReference<>();
  private final SkyframeActionExecutor skyframeActionExecutor;
  private CompletionReceiver actionExecutionFunction;
  protected SkyframeProgressReceiver progressReceiver;
  private final AtomicReference<CyclesReporter> cyclesReporter = new AtomicReference<>();

  private final BinTools binTools;
  private boolean needToInjectEmbeddedArtifacts = true;
  private boolean needToInjectPrecomputedValuesForAnalysis = true;
  protected int modifiedFiles;
  protected int outputDirtyFiles;
  protected int modifiedFilesDuringPreviousBuild;
  private final Predicate<PathFragment> allowedMissingInputs;
  private final ExternalFileAction externalFileAction;

  private final ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions;
  private final ImmutableList<PrecomputedValue.Injected> extraPrecomputedValues;

  private final String productName;

  protected SkyframeIncrementalBuildMonitor incrementalBuildMonitor =
      new SkyframeIncrementalBuildMonitor();

  private MutableSupplier<ConfigurationFactory> configurationFactory = new MutableSupplier<>();
  private MutableSupplier<ImmutableList<ConfigurationFragmentFactory>> configurationFragments =
      new MutableSupplier<>();

  private final PathFragment blacklistedPackagePrefixesFile;

  private final RuleClassProvider ruleClassProvider;

  private final CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy;
  
  private final List<BuildFileName> buildFilesByPriority;

  private PerBuildSyscallCache perBuildSyscallCache;
  private int lastConcurrencyLevel = -1;

  private static final Logger LOG = Logger.getLogger(SkyframeExecutor.class.getName());

  protected SkyframeExecutor(
      EvaluatorSupplier evaluatorSupplier,
      PackageFactory pkgFactory,
      BlazeDirectories directories,
      BinTools binTools,
      Factory workspaceStatusActionFactory,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      Predicate<PathFragment> allowedMissingInputs,
      Preprocessor.Factory.Supplier preprocessorFactorySupplier,
      ImmutableMap<SkyFunctionName, SkyFunction> extraSkyFunctions,
      ImmutableList<PrecomputedValue.Injected> extraPrecomputedValues,
      ExternalFileAction externalFileAction,
      PathFragment blacklistedPackagePrefixesFile,
      String productName,
      CrossRepositoryLabelViolationStrategy crossRepositoryLabelViolationStrategy,
      List<BuildFileName> buildFilesByPriority) {
    // Strictly speaking, these arguments are not required for initialization, but all current
    // callsites have them at hand, so we might as well set them during construction.
    this.evaluatorSupplier = evaluatorSupplier;
    this.pkgFactory = pkgFactory;
    this.pkgFactory.setSyscalls(syscalls);
    this.workspaceStatusActionFactory = workspaceStatusActionFactory;
    this.packageManager = new SkyframePackageManager(
        new SkyframePackageLoader(), new SkyframeTransitivePackageLoader(),
        syscalls, cyclesReporter, pkgLocator, numPackagesLoaded, this);
    this.resourceManager = ResourceManager.instance();
    this.skyframeActionExecutor = new SkyframeActionExecutor(
        resourceManager, eventBus, statusReporterRef);
    this.directories = Preconditions.checkNotNull(directories);
    this.buildInfoFactories = buildInfoFactories;
    this.allowedMissingInputs = allowedMissingInputs;
    this.preprocessorFactorySupplier = preprocessorFactorySupplier;
    this.extraSkyFunctions = extraSkyFunctions;
    this.extraPrecomputedValues = extraPrecomputedValues;
    this.externalFileAction = externalFileAction;
    this.blacklistedPackagePrefixesFile = blacklistedPackagePrefixesFile;
    this.binTools = binTools;

    this.ruleClassProvider = pkgFactory.getRuleClassProvider();
    this.skyframeBuildView = new SkyframeBuildView(
        directories,
        this,
        binTools,
        (ConfiguredRuleClassProvider) ruleClassProvider);
    this.artifactFactory.set(skyframeBuildView.getArtifactFactory());
    this.externalFilesHelper = new ExternalFilesHelper(
        pkgLocator, this.externalFileAction, directories);
    this.productName = productName;
    this.crossRepositoryLabelViolationStrategy = crossRepositoryLabelViolationStrategy;
    this.buildFilesByPriority = buildFilesByPriority;
  }

  private ImmutableMap<SkyFunctionName, SkyFunction> skyFunctions(
      PackageFactory pkgFactory,
      Predicate<PathFragment> allowedMissingInputs) {
    ConfiguredRuleClassProvider ruleClassProvider =
        (ConfiguredRuleClassProvider) pkgFactory.getRuleClassProvider();
    // TODO(janakr): use this semaphore to bound memory usage for SkyFunctions besides
    // ConfiguredTargetFunction that may have a large temporary memory blow-up.
    Semaphore cpuBoundSemaphore = new Semaphore(ResourceUsage.getAvailableProcessors());
    // We use an immutable map builder for the nice side effect that it throws if a duplicate key
    // is inserted.
    ImmutableMap.Builder<SkyFunctionName, SkyFunction> map = ImmutableMap.builder();
    map.put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction());
    map.put(SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE, new ClientEnvironmentFunction());
    map.put(SkyFunctions.FILE_STATE, new FileStateFunction(tsgm, externalFilesHelper));
    map.put(SkyFunctions.DIRECTORY_LISTING_STATE,
        new DirectoryListingStateFunction(externalFilesHelper));
    map.put(SkyFunctions.FILE_SYMLINK_CYCLE_UNIQUENESS,
        new FileSymlinkCycleUniquenessFunction());
    map.put(SkyFunctions.FILE_SYMLINK_INFINITE_EXPANSION_UNIQUENESS,
        new FileSymlinkInfiniteExpansionUniquenessFunction());
    map.put(SkyFunctions.FILE, new FileFunction(pkgLocator));
    map.put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction());
    map.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            deletedPackages, crossRepositoryLabelViolationStrategy, buildFilesByPriority));
    map.put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction());
    map.put(SkyFunctions.AST_FILE_LOOKUP, new ASTFileLookupFunction(ruleClassProvider));
    map.put(
        SkyFunctions.SKYLARK_IMPORTS_LOOKUP,
        newSkylarkImportLookupFunction(ruleClassProvider, pkgFactory));
    map.put(SkyFunctions.GLOB, newGlobFunction());
    map.put(SkyFunctions.TARGET_PATTERN, new TargetPatternFunction());
    map.put(SkyFunctions.PREPARE_DEPS_OF_PATTERNS, new PrepareDepsOfPatternsFunction());
    map.put(SkyFunctions.PREPARE_DEPS_OF_PATTERN, new PrepareDepsOfPatternFunction(pkgLocator));
    map.put(
        SkyFunctions.PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY,
        new PrepareDepsOfTargetsUnderDirectoryFunction(directories));
    map.put(SkyFunctions.COLLECT_TARGETS_IN_PACKAGE, new CollectTargetsInPackageFunction());
    map.put(
        SkyFunctions.COLLECT_PACKAGES_UNDER_DIRECTORY,
        new CollectPackagesUnderDirectoryFunction(directories));
    map.put(SkyFunctions.BLACKLISTED_PACKAGE_PREFIXES, new BlacklistedPackagePrefixesFunction());
    map.put(SkyFunctions.TESTS_IN_SUITE, new TestsInSuiteFunction());
    map.put(SkyFunctions.TEST_SUITE_EXPANSION, new TestSuiteExpansionFunction());
    map.put(SkyFunctions.TARGET_PATTERN_PHASE, new TargetPatternPhaseFunction());
    map.put(SkyFunctions.RECURSIVE_PKG, new RecursivePkgFunction(directories));
    map.put(
        SkyFunctions.PACKAGE,
        newPackageFunction(
            pkgFactory,
            packageManager,
            showLoadingProgress,
            packageFunctionCache,
            astCache,
            numPackagesLoaded,
            ruleClassProvider,
            packageProgress));
    map.put(SkyFunctions.PACKAGE_ERROR, new PackageErrorFunction());
    map.put(SkyFunctions.TARGET_MARKER, new TargetMarkerFunction());
    map.put(SkyFunctions.TRANSITIVE_TARGET, new TransitiveTargetFunction(ruleClassProvider));
    map.put(SkyFunctions.TRANSITIVE_TRAVERSAL, new TransitiveTraversalFunction());
    map.put(
        SkyFunctions.CONFIGURED_TARGET,
        new ConfiguredTargetFunction(
            new BuildViewProvider(), ruleClassProvider, cpuBoundSemaphore));
    map.put(SkyFunctions.ASPECT, new AspectFunction(new BuildViewProvider(), ruleClassProvider));
    map.put(SkyFunctions.LOAD_SKYLARK_ASPECT, new ToplevelSkylarkAspectFunction());
    map.put(SkyFunctions.POST_CONFIGURED_TARGET,
        new PostConfiguredTargetFunction(new BuildViewProvider(), ruleClassProvider));
    map.put(SkyFunctions.BUILD_CONFIGURATION,
        new BuildConfigurationFunction(directories, ruleClassProvider));
    map.put(SkyFunctions.CONFIGURATION_COLLECTION, new ConfigurationCollectionFunction(
        configurationFactory, ruleClassProvider));
    map.put(SkyFunctions.CONFIGURATION_FRAGMENT, new ConfigurationFragmentFunction(
        configurationFragments, ruleClassProvider));
    map.put(SkyFunctions.WORKSPACE_AST, new WorkspaceASTFunction(ruleClassProvider));
    map.put(
        SkyFunctions.WORKSPACE_FILE,
        new WorkspaceFileFunction(ruleClassProvider, pkgFactory, directories));
    map.put(SkyFunctions.EXTERNAL_PACKAGE, new ExternalPackageFunction());
    map.put(SkyFunctions.TARGET_COMPLETION, CompletionFunction.targetCompletionFunction(eventBus));
    map.put(SkyFunctions.ASPECT_COMPLETION, CompletionFunction.aspectCompletionFunction(eventBus));
    map.put(SkyFunctions.TEST_COMPLETION, new TestCompletionFunction());
    map.put(SkyFunctions.ARTIFACT, new ArtifactFunction(allowedMissingInputs));
    map.put(SkyFunctions.BUILD_INFO_COLLECTION, new BuildInfoCollectionFunction(artifactFactory));
    map.put(SkyFunctions.BUILD_INFO, new WorkspaceStatusFunction());
    map.put(SkyFunctions.COVERAGE_REPORT, new CoverageReportFunction());
    ActionExecutionFunction actionExecutionFunction =
        new ActionExecutionFunction(skyframeActionExecutor, tsgm);
    map.put(SkyFunctions.ACTION_EXECUTION, actionExecutionFunction);
    this.actionExecutionFunction = actionExecutionFunction;
    map.put(SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL,
        new RecursiveFilesystemTraversalFunction());
    map.put(SkyFunctions.FILESET_ENTRY, new FilesetEntryFunction());
    map.put(SkyFunctions.ACTION_TEMPLATE_EXPANSION, new ActionTemplateExpansionFunction());
    map.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, new LocalRepositoryLookupFunction());
    map.putAll(extraSkyFunctions);
    return map.build();
  }

  protected SkyFunction newGlobFunction() {
    return new GlobFunction(/*alwaysUseDirListing=*/false);
  }

  protected PackageFunction newPackageFunction(
      PackageFactory pkgFactory,
      PackageManager packageManager,
      AtomicBoolean showLoadingProgress,
      Cache<PackageIdentifier, CacheEntryWithGlobDeps<Builder>> packageFunctionCache,
      Cache<PackageIdentifier, CacheEntryWithGlobDeps<AstAfterPreprocessing>> astCache,
      AtomicInteger numPackagesLoaded,
      RuleClassProvider ruleClassProvider,
      PackageProgressReceiver packageProgress) {
    return new PackageFunction(
        pkgFactory,
        packageManager,
        showLoadingProgress,
        packageFunctionCache,
        astCache,
        numPackagesLoaded,
        null,
        packageProgress);
  }

  protected SkyFunction newSkylarkImportLookupFunction(
      RuleClassProvider ruleClassProvider, PackageFactory pkgFactory) {
    return new SkylarkImportLookupFunction(ruleClassProvider, this.pkgFactory);
  }

  protected PerBuildSyscallCache newPerBuildSyscallCache(int concurrencyLevel) {
    return PerBuildSyscallCache.newBuilder().setConcurrencyLevel(concurrencyLevel).build();
  }

  /**
   * Gets a (possibly cached) syscalls cache, re-initialized each build.
   *
   * <p>We cache the syscalls cache if possible because construction of the cache is surprisingly
   * expensive, and is on the critical path of null builds.
   */
  protected final PerBuildSyscallCache getPerBuildSyscallCache(int concurrencyLevel) {
    if (perBuildSyscallCache != null && lastConcurrencyLevel == concurrencyLevel) {
      perBuildSyscallCache.clear();
      return perBuildSyscallCache;
    }
    lastConcurrencyLevel = concurrencyLevel;
    perBuildSyscallCache = newPerBuildSyscallCache(concurrencyLevel);
    return perBuildSyscallCache;
  }

 @ThreadCompatible
  public void setActive(boolean active) {
    this.active = active;
  }

  protected void checkActive() {
    Preconditions.checkState(active);
  }

  public void setFileCache(ActionInputFileCache fileCache) {
    this.skyframeActionExecutor.setFileCache(fileCache);
  }

  public void dump(boolean summarize, PrintStream out) {
    memoizingEvaluator.dump(summarize, out);
  }

  public abstract void dumpPackages(PrintStream out);

  public void setOutputService(OutputService outputService) {
    this.outputService = outputService;
  }

  /**
   * Notify listeners about changed files, and release any associated memory afterwards.
   */
  public void drainChangedFiles() {
    incrementalBuildMonitor.alertListeners(getEventBus());
    incrementalBuildMonitor = null;
  }

  @VisibleForTesting
  public BuildDriver getDriverForTesting() {
    return buildDriver;
  }

  /**
   * This method exists only to allow a module to make a top-level Skyframe call during the
   * transition to making it fully Skyframe-compatible. Do not add additional callers!
   */
  public SkyValue evaluateSkyKeyForExecutionSetup(final EventHandler eventHandler, final SkyKey key)
      throws EnvironmentalExecException, InterruptedException {
    synchronized (valueLookupLock) {
      // We evaluate in keepGoing mode because in the case that the graph does not store its
      // edges, nokeepGoing builds are not allowed, whereas keepGoing builds are always
      // permitted.
      EvaluationResult<SkyValue> result =
          buildDriver.evaluate(
              ImmutableList.of(key), true, ResourceUsage.getAvailableProcessors(), eventHandler);
      if (!result.hasError()) {
        return Preconditions.checkNotNull(result.get(key), "%s %s", result, key);
      }
      ErrorInfo errorInfo = Preconditions.checkNotNull(result.getError(key), "%s %s", key, result);
      Throwables.propagateIfPossible(errorInfo.getException(), EnvironmentalExecException.class);
      if (errorInfo.getException() != null) {
        throw new IllegalStateException(errorInfo.getException());
      }
      throw new IllegalStateException(errorInfo.toString());
    }
  }

  @VisibleForTesting
  public PathFragment getBlacklistedPackagePrefixesFile() {
    return blacklistedPackagePrefixesFile;
  }

  class BuildViewProvider {
    /**
     * Returns the current {@link SkyframeBuildView} instance.
     */
    SkyframeBuildView getSkyframeBuildView() {
      return skyframeBuildView;
    }
  }

  /**
   * Must be called before the {@link SkyframeExecutor} can be used (should only be called in
   * factory methods and as an implementation detail of {@link #resetEvaluator}).
   */
  protected void init() {
    progressReceiver = newSkyframeProgressReceiver();
    ImmutableMap<SkyFunctionName, SkyFunction> skyFunctions =
        skyFunctions(pkgFactory, allowedMissingInputs);
    memoizingEvaluator = evaluatorSupplier.create(
        skyFunctions, evaluatorDiffer(), progressReceiver, emittedEventState,
        hasIncrementalState());
    buildDriver = getBuildDriver();
  }

  protected SkyframeProgressReceiver newSkyframeProgressReceiver() {
    return new SkyframeProgressReceiver();
  }

  /**
   * Reinitializes the Skyframe evaluator, dropping all previously computed values.
   *
   * <p>Be careful with this method as it also deletes all injected values. You need to make sure
   * that any necessary precomputed values are reinjected before the next build. Constants can be
   * put in {@link #reinjectConstantValuesLazily}.
   */
  public void resetEvaluator() {
    init();
    emittedEventState.clear();
    skyframeBuildView.clearLegacyData();
    reinjectConstantValuesLazily();
  }

  protected abstract Differencer evaluatorDiffer();

  protected abstract BuildDriver getBuildDriver();

  /**
   * Values whose values are known at startup and guaranteed constant are still wiped from the
   * evaluator when we create a new one, so they must be re-injected each time we create a new
   * evaluator.
   */
  private void reinjectConstantValuesLazily() {
    needToInjectEmbeddedArtifacts = true;
    needToInjectPrecomputedValuesForAnalysis = true;
  }

  /**
   * Deletes all ConfiguredTarget values from the Skyframe cache. This is done to save memory (e.g.
   * on a configuration change); since the configuration is part of the key, these key/value pairs
   * will be sitting around doing nothing until the configuration changes back to the previous
   * value.
   *
   * <p>The next evaluation will delete all invalid values.
   */
  public abstract void dropConfiguredTargets();

  /**
   * Removes ConfigurationFragmentValuess and ConfigurationCollectionValues from the cache.
   */
  @VisibleForTesting
  public void invalidateConfigurationCollection() {
    invalidate(SkyFunctionName.functionIsIn(ImmutableSet.of(SkyFunctions.CONFIGURATION_FRAGMENT,
            SkyFunctions.CONFIGURATION_COLLECTION)));
  }

  /**
   * Decides if graph edges should be stored for this build. If not, re-creates the graph to not
   * store graph edges. Necessary conditions to not store graph edges are:
   * (1) batch (since incremental builds are not possible);
   * (2) skyframe build (since otherwise the memory savings are too slight to bother);
   * (3) keep-going (since otherwise bubbling errors up may require edges of done nodes);
   * (4) discard_analysis_cache (since otherwise user isn't concerned about saving memory this way).
   */
  public void decideKeepIncrementalState(boolean batch, Options viewOptions) {
    // Assume incrementality.
  }

  public boolean hasIncrementalState() {
    return true;
  }

  @VisibleForTesting
  protected abstract Injectable injectable();

  /**
   * Saves memory by clearing analysis objects from Skyframe. If using legacy execution, actually
   * deletes the relevant values. If using Skyframe execution, clears their data without deleting
   * them (they will be deleted on the next build).
   */
  public abstract void clearAnalysisCache(Collection<ConfiguredTarget> topLevelTargets);

  /**
   * Injects the contents of the computed tools/defaults package.
   */
  @VisibleForTesting
  public void setupDefaultPackage(String defaultsPackageContents) {
    PrecomputedValue.DEFAULTS_PACKAGE_CONTENTS.set(injectable(), defaultsPackageContents);
  }

  public void injectWorkspaceStatusData(String workspaceName) {
    PrecomputedValue.WORKSPACE_STATUS_KEY.set(injectable(),
        workspaceStatusActionFactory.createWorkspaceStatusAction(
            artifactFactory.get(), WorkspaceStatusValue.ARTIFACT_OWNER, buildId, workspaceName));
  }

  public void injectCoverageReportData(ImmutableList<ActionAnalysisMetadata> actions) {
    PrecomputedValue.COVERAGE_REPORT_KEY.set(injectable(), actions);
  }

  /**
   * Sets the default visibility.
   */
  private void setDefaultVisibility(RuleVisibility defaultVisibility) {
    PrecomputedValue.DEFAULT_VISIBILITY.set(injectable(), defaultVisibility);
  }

  protected void maybeInjectPrecomputedValuesForAnalysis() {
    if (needToInjectPrecomputedValuesForAnalysis) {
      PrecomputedValue.BLAZE_DIRECTORIES.set(injectable(), directories);
      PrecomputedValue.PRODUCT_NAME.set(injectable(), productName);
      injectBuildInfoFactories();
      injectExtraPrecomputedValues();
      needToInjectPrecomputedValuesForAnalysis = false;
    }
  }

  private void injectExtraPrecomputedValues() {
    for (PrecomputedValue.Injected injected : extraPrecomputedValues) {
      injected.inject(injectable());
    }
  }

  protected Cache<PackageIdentifier, CacheEntryWithGlobDeps<Package.Builder>>
      newPkgFunctionCache() {
    return CacheBuilder.newBuilder().build();
  }

  protected Cache<PackageIdentifier, CacheEntryWithGlobDeps<AstAfterPreprocessing>> newAstCache() {
    return CacheBuilder.newBuilder().build();
  }

  /**
   * Injects the build info factory map that will be used when constructing build info
   * actions/artifacts. Unchanged across the life of the Blaze server, although it must be injected
   * each time the evaluator is created.
   */
  private void injectBuildInfoFactories() {
    ImmutableMap.Builder<BuildInfoKey, BuildInfoFactory> factoryMapBuilder =
        ImmutableMap.builder();
    for (BuildInfoFactory factory : buildInfoFactories) {
      factoryMapBuilder.put(factory.getKey(), factory);
    }
    PrecomputedValue.BUILD_INFO_FACTORIES.set(injectable(), factoryMapBuilder.build());
  }

  private void setShowLoadingProgress(boolean showLoadingProgressValue) {
    showLoadingProgress.set(showLoadingProgressValue);
  }

  @VisibleForTesting
  public void setCommandId(UUID commandId) {
    PrecomputedValue.BUILD_ID.set(injectable(), commandId);
    buildId.set(commandId);
  }

  protected void setPrecomputedClientEnv(Map<String, String> clientEnv) {
    PrecomputedValue.CLIENT_ENV.set(injectable(), clientEnv);
  }

  /** Returns the build-info.txt and build-changelist.txt artifacts. */
  public Collection<Artifact> getWorkspaceStatusArtifacts(EventHandler eventHandler)
      throws InterruptedException {
    // Should already be present, unless the user didn't request any targets for analysis.
    EvaluationResult<WorkspaceStatusValue> result = buildDriver.evaluate(
        ImmutableList.of(WorkspaceStatusValue.SKY_KEY), /*keepGoing=*/true, /*numThreads=*/1,
        eventHandler);
    WorkspaceStatusValue value =
        Preconditions.checkNotNull(result.get(WorkspaceStatusValue.SKY_KEY));
    return ImmutableList.of(value.getStableArtifact(), value.getVolatileArtifact());
  }

  // TODO(bazel-team): Make this take a PackageIdentifier.
  public Map<PathFragment, Root> getArtifactRootsForFiles(
      final EventHandler eventHandler, Iterable<PathFragment> execPaths)
      throws PackageRootResolutionException, InterruptedException {
    return getArtifactRoots(eventHandler, execPaths, true);
  }

  public Map<PathFragment, Root> getArtifactRoots(
      final EventHandler eventHandler, Iterable<PathFragment> execPaths)
      throws PackageRootResolutionException, InterruptedException {
    return getArtifactRoots(eventHandler, execPaths, false);
  }

  private Map<PathFragment, Root> getArtifactRoots(
      final EventHandler eventHandler, Iterable<PathFragment> execPaths, boolean forFiles)
      throws PackageRootResolutionException, InterruptedException {

    final List<SkyKey> packageKeys = new ArrayList<>();
    if (forFiles) {
      for (PathFragment execPath : execPaths) {
        PathFragment parent = Preconditions.checkNotNull(
            execPath.getParentDirectory(), "Must pass in files, not root directory");
        Preconditions.checkArgument(!parent.isAbsolute(), execPath);
        packageKeys.add(ContainingPackageLookupValue.key(
            PackageIdentifier.createInMainRepo(parent)));
      }
    } else {
      for (PathFragment execPath : execPaths) {
        Preconditions.checkArgument(!execPath.isAbsolute(), execPath);
        packageKeys.add(ContainingPackageLookupValue.key(
            PackageIdentifier.createInMainRepo(execPath)));
      }
    }

    EvaluationResult<ContainingPackageLookupValue> result;
    synchronized (valueLookupLock) {
      result =
          buildDriver.evaluate(packageKeys, /*keepGoing=*/ true, /*numThreads=*/ 1, eventHandler);
    }

    if (result.hasError()) {
      throw new PackageRootResolutionException("Exception encountered determining package roots",
          result.getError().getException());
    }

    Map<PathFragment, Root> roots = new HashMap<>();
    for (PathFragment execPath : execPaths) {
      ContainingPackageLookupValue value = result.get(ContainingPackageLookupValue.key(
          PackageIdentifier.createInMainRepo(forFiles ? execPath.getParentDirectory() : execPath)));
      if (value.hasContainingPackage()) {
        roots.put(execPath, Root.asSourceRoot(value.getContainingPackageRoot(),
            value.getContainingPackageName().getRepository().isMain()));
      } else {
        roots.put(execPath, null);
      }
    }
    return roots;
  }

  @VisibleForTesting
  public WorkspaceStatusAction getLastWorkspaceStatusActionForTesting() {
    PrecomputedValue value = (PrecomputedValue) buildDriver.getGraphForTesting()
        .getExistingValueForTesting(PrecomputedValue.WORKSPACE_STATUS_KEY.getKeyForTesting());
    return (WorkspaceStatusAction) value.get();
  }

  /**
   * Informs user about number of modified files (source and output files).
   */
  // Note, that number of modified files in some cases can be bigger than actual number of
  // modified files for targets in current request. Skyframe may check for modification all files
  // from previous requests.
  protected void informAboutNumberOfModifiedFiles() {
    LOG.info(String.format("Found %d modified files from last build", modifiedFiles));
  }

  public EventBus getEventBus() {
    return eventBus.get();
  }

  public ActionExecutionContextFactory getActionExecutionContextFactory() {
    return skyframeActionExecutor;
  }

  @VisibleForTesting
  ImmutableList<Path> getPathEntries() {
    return pkgLocator.get().getPathEntries();
  }

  protected abstract void invalidate(Predicate<SkyKey> pred);

  private static boolean compatibleFileTypes(Dirent.Type oldType, FileStateValue.Type newType) {
    return (oldType.equals(Dirent.Type.FILE) && newType.equals(FileStateValue.Type.REGULAR_FILE))
        || (oldType.equals(Dirent.Type.UNKNOWN)
            && newType.equals(FileStateValue.Type.SPECIAL_FILE))
        || (oldType.equals(Dirent.Type.DIRECTORY) && newType.equals(FileStateValue.Type.DIRECTORY))
        || (oldType.equals(Dirent.Type.SYMLINK) && newType.equals(FileStateValue.Type.SYMLINK));
  }

  protected Differencer.Diff getDiff(TimestampGranularityMonitor tsgm,
      Iterable<PathFragment> modifiedSourceFiles, final Path pathEntry)
          throws InterruptedException {
    if (Iterables.isEmpty(modifiedSourceFiles)) {
      return new ImmutableDiff(ImmutableList.<SkyKey>of(), ImmutableMap.<SkyKey, SkyValue>of());
    }
    // TODO(bazel-team): change ModifiedFileSet to work with RootedPaths instead of PathFragments.
    Iterable<SkyKey> dirtyFileStateSkyKeys = Iterables.transform(modifiedSourceFiles,
        new Function<PathFragment, SkyKey>() {
          @Override
          public SkyKey apply(PathFragment pathFragment) {
            Preconditions.checkState(!pathFragment.isAbsolute(),
                "found absolute PathFragment: %s", pathFragment);
            return FileStateValue.key(RootedPath.toRootedPath(pathEntry, pathFragment));
          }
        });
    // We only need to invalidate directory values when a file has been created or deleted or
    // changes type, not when it has merely been modified. Unfortunately we do not have that
    // information here, so we compute it ourselves.
    // TODO(bazel-team): Fancy filesystems could provide it with a hypothetically modified
    // DiffAwareness interface.
    FilesystemValueChecker fsvc = new FilesystemValueChecker(tsgm, null);
    Map<SkyKey, SkyValue> valuesMap = memoizingEvaluator.getValues();
    Differencer.DiffWithDelta diff =
        fsvc.getNewAndOldValues(valuesMap, dirtyFileStateSkyKeys, new FileDirtinessChecker());

    Set<SkyKey> valuesToInvalidate = new HashSet<>();
    Map<SkyKey, SkyValue> valuesToInject = new HashMap<>();
    for (Map.Entry<SkyKey, Delta> entry : diff.changedKeysWithNewAndOldValues().entrySet()) {
      SkyKey key = entry.getKey();
      Preconditions.checkState(key.functionName().equals(SkyFunctions.FILE_STATE), key);
      RootedPath rootedPath = (RootedPath) key.argument();
      Delta delta = entry.getValue();
      FileStateValue oldValue = (FileStateValue) delta.getOldValue();
      FileStateValue newValue = (FileStateValue) delta.getNewValue();
      if (newValue != null) {
        valuesToInject.put(key, newValue);
      } else {
        valuesToInvalidate.add(key);
      }
      SkyKey dirListingStateKey = parentDirectoryListingStateKey(rootedPath);
      // Invalidate the directory listing for the path's parent directory if the change was
      // relevant (e.g. path turned from a symlink into a directory) OR if we don't have enough
      // information to determine it was irrelevant.
      boolean changedType = false;
      if (newValue == null) {
        changedType = true;
      } else if (oldValue != null) {
        changedType = !oldValue.getType().equals(newValue.getType());
      } else {
        DirectoryListingStateValue oldDirListingStateValue =
            (DirectoryListingStateValue) valuesMap.get(dirListingStateKey);
        if (oldDirListingStateValue != null) {
          String baseName = rootedPath.getRelativePath().getBaseName();
          Dirent oldDirent = oldDirListingStateValue.getDirents().maybeGetDirent(baseName);
          changedType = (oldDirent == null)
              || !compatibleFileTypes(oldDirent.getType(), newValue.getType());
        } else {
          changedType = true;
        }
      }
      if (changedType) {
        valuesToInvalidate.add(dirListingStateKey);
      }
    }
    for (SkyKey key : diff.changedKeysWithoutNewValues()) {
      Preconditions.checkState(key.functionName().equals(SkyFunctions.FILE_STATE), key);
      RootedPath rootedPath = (RootedPath) key.argument();
      valuesToInvalidate.add(parentDirectoryListingStateKey(rootedPath));
    }
    return new ImmutableDiff(valuesToInvalidate, valuesToInject);
  }

  private static SkyKey parentDirectoryListingStateKey(RootedPath rootedPath) {
    RootedPath parentDirRootedPath = RootedPath.toRootedPath(
        rootedPath.getRoot(), rootedPath.getRelativePath().getParentDirectory());
    return DirectoryListingStateValue.key(parentDirRootedPath);
  }

  /**
   * Sets the packages that should be treated as deleted and ignored.
   */
  @VisibleForTesting  // productionVisibility = Visibility.PRIVATE
  public abstract void setDeletedPackages(Iterable<PackageIdentifier> pkgs);

  @VisibleForTesting
  public final void setBlacklistedPackagePrefixesFile(PathFragment blacklistedPkgFile) {
    PrecomputedValue.BLACKLISTED_PACKAGE_PREFIXES_FILE.set(injectable(), blacklistedPkgFile);
  }

  /**
   * Prepares the evaluator for loading.
   *
   * <p>MUST be run before every incremental build.
   */
  @VisibleForTesting // productionVisibility = Visibility.PRIVATE
  public void preparePackageLoading(
      PathPackageLocator pkgLocator,
      PackageCacheOptions packageCacheOptions,
      String defaultsPackageContents,
      UUID commandId,
      Map<String, String> clientEnv,
      TimestampGranularityMonitor tsgm) {
    Preconditions.checkNotNull(pkgLocator);
    Preconditions.checkNotNull(tsgm);
    setActive(true);

    this.tsgm.set(tsgm);
    maybeInjectPrecomputedValuesForAnalysis();
    setCommandId(commandId);
    setPrecomputedClientEnv(clientEnv);
    setBlacklistedPackagePrefixesFile(getBlacklistedPackagePrefixesFile());
    setShowLoadingProgress(packageCacheOptions.showLoadingProgress);
    setDefaultVisibility(packageCacheOptions.defaultVisibility);
    setupDefaultPackage(defaultsPackageContents);
    setPackageLocator(pkgLocator);

    syscalls.set(getPerBuildSyscallCache(packageCacheOptions.globbingThreads));
    this.pkgFactory.setGlobbingThreads(packageCacheOptions.globbingThreads);
    this.pkgFactory.setMaxDirectoriesToEagerlyVisitInGlobbing(
        packageCacheOptions.maxDirectoriesToEagerlyVisitInGlobbing);
    checkPreprocessorFactory();
    emittedEventState.clear();

    // If the PackageFunction was interrupted, there may be stale entries here.
    packageFunctionCache.invalidateAll();
    astCache.invalidateAll();
    numPackagesLoaded.set(0);
    packageProgress.reset();

    // Reset the stateful SkyframeCycleReporter, which contains cycles from last run.
    cyclesReporter.set(createCyclesReporter());
  }

  @SuppressWarnings("unchecked")
  private void setPackageLocator(PathPackageLocator pkgLocator) {
    PathPackageLocator oldLocator = this.pkgLocator.getAndSet(pkgLocator);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(injectable(), pkgLocator);

    if (!pkgLocator.equals(oldLocator)) {
      // The package path is read not only by SkyFunctions but also by some other code paths.
      // We need to take additional steps to keep the corresponding data structures in sync.
      // (Some of the additional steps are carried out by ConfiguredTargetValueInvalidationListener,
      // and some by BuildView#buildHasIncompatiblePackageRoots and #updateSkyframe.)
      onNewPackageLocator(oldLocator, pkgLocator);
    }
  }

  protected abstract void onNewPackageLocator(PathPackageLocator oldLocator,
                                              PathPackageLocator pkgLocator);

  private void checkPreprocessorFactory() {
    if (preprocessorFactory == null) {
      Preprocessor.Factory newPreprocessorFactory = preprocessorFactorySupplier.getFactory(
          packageManager, directories.getOutputBase());
      pkgFactory.setPreprocessorFactory(newPreprocessorFactory);
      preprocessorFactory = newPreprocessorFactory;
    } else if (!preprocessorFactory.isStillValid()) {
      Preprocessor.Factory newPreprocessorFactory = preprocessorFactorySupplier.getFactory(
          packageManager, directories.getOutputBase());
      invalidate(SkyFunctionName.functionIs(SkyFunctions.PACKAGE));
      pkgFactory.setPreprocessorFactory(newPreprocessorFactory);
      preprocessorFactory = newPreprocessorFactory;
    }
  }

  public SkyframeBuildView getSkyframeBuildView() {
    return skyframeBuildView;
  }

  /**
   * Sets the eventBus to use for posting events.
   */
  public void setEventBus(EventBus eventBus) {
    this.eventBus.set(eventBus);
  }

  public void setClientEnv(Map<String, String> clientEnv) {
    this.skyframeActionExecutor.setClientEnv(clientEnv);
  }

  /**
   * Sets the path for action log buffers.
   */
  public void setActionOutputRoot(Path actionOutputRoot) {
    Preconditions.checkNotNull(actionOutputRoot);
    this.actionLogBufferPathGenerator = new ActionLogBufferPathGenerator(actionOutputRoot);
    this.skyframeActionExecutor.setActionLogBufferPathGenerator(actionLogBufferPathGenerator);
  }

  /**
   * Asks the Skyframe evaluator to build the value for BuildConfigurationCollection and returns the
   * result. Also invalidates {@link PrecomputedValue#BLAZE_DIRECTORIES} if it has changed.
   */
  public BuildConfigurationCollection createConfigurations(
      EventHandler eventHandler, ConfigurationFactory configurationFactory,
      BuildOptions buildOptions, Set<String> multiCpu,
      boolean keepGoing)
          throws InvalidConfigurationException, InterruptedException {
    this.configurationFactory.set(configurationFactory);
    this.configurationFragments.set(ImmutableList.copyOf(configurationFactory.getFactories()));

    SkyKey skyKey = ConfigurationCollectionValue.key(
        buildOptions, ImmutableSortedSet.copyOf(multiCpu));
    EvaluationResult<ConfigurationCollectionValue> result =
        buildDriver.evaluate(Arrays.asList(skyKey), keepGoing, DEFAULT_THREAD_COUNT, eventHandler);
    if (result.hasError()) {
      Throwable e = result.getError(skyKey).getException();
      // Wrap loading failed exceptions
      if (e instanceof NoSuchThingException) {
        e = new InvalidConfigurationException(e);
      }
      Throwables.propagateIfInstanceOf(e, InvalidConfigurationException.class);
      throw new IllegalStateException(
          "Unknown error during ConfigurationCollectionValue evaluation", e);
    }
    ConfigurationCollectionValue configurationValue = result.get(skyKey);
    return configurationValue.getConfigurationCollection();
  }

  private Iterable<ActionLookupValue> getActionLookupValues() {
    // This filter keeps subclasses of ActionLookupValue.
    return Iterables.filter(memoizingEvaluator.getDoneValues().values(), ActionLookupValue.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  Map<SkyKey, ActionLookupValue> getActionLookupValueMap() {
    return (Map) Maps.filterValues(memoizingEvaluator.getDoneValues(),
        Predicates.instanceOf(ActionLookupValue.class));
  }

  /**
   * Checks the actions in Skyframe for conflicts between their output artifacts. Delegates to
   * {@link SkyframeActionExecutor#findAndStoreArtifactConflicts} to do the work, since any
   * conflicts found will only be reported during execution.
   */
  ImmutableMap<ActionAnalysisMetadata, SkyframeActionExecutor.ConflictException>
      findArtifactConflicts() throws InterruptedException {
    if (skyframeBuildView.isSomeConfiguredTargetEvaluated()
        || skyframeBuildView.isSomeConfiguredTargetInvalidated()) {
      // This operation is somewhat expensive, so we only do it if the graph might have changed in
      // some way -- either we analyzed a new target or we invalidated an old one.
      try (AutoProfiler p = AutoProfiler.logged("discovering artifact conflicts", LOG)) {
        skyframeActionExecutor.findAndStoreArtifactConflicts(getActionLookupValues());
        skyframeBuildView.resetEvaluatedConfiguredTargetFlag();
        // The invalidated configured targets flag will be reset later in the evaluate() call.
      }
    }
    return skyframeActionExecutor.badActions();
  }

  /**
   * Asks the Skyframe evaluator to build the given artifacts and targets, and to test the
   * given test targets.
   */
  public EvaluationResult<?> buildArtifacts(
      Reporter reporter,
      Executor executor,
      Set<Artifact> artifactsToBuild,
      Collection<ConfiguredTarget> targetsToBuild,
      Collection<AspectValue> aspects,
      Collection<ConfiguredTarget> targetsToTest,
      boolean exclusiveTesting,
      boolean keepGoing,
      boolean explain,
      boolean finalizeActionsToOutputService,
      int numJobs,
      ActionCacheChecker actionCacheChecker,
      @Nullable EvaluationProgressReceiver executionProgressReceiver,
      TopLevelArtifactContext topLevelArtifactContext)
      throws InterruptedException {
    checkActive();
    Preconditions.checkState(actionLogBufferPathGenerator != null);

    skyframeActionExecutor.prepareForExecution(
        reporter, executor, keepGoing, explain, actionCacheChecker,
        finalizeActionsToOutputService ? outputService : null);

    resourceManager.resetResourceUsage();
    try {
      progressReceiver.executionProgressReceiver = executionProgressReceiver;
      Iterable<SkyKey> artifactKeys = ArtifactSkyKey.mandatoryKeys(artifactsToBuild);
      Iterable<SkyKey> targetKeys =
          TargetCompletionValue.keys(targetsToBuild, topLevelArtifactContext);
      Iterable<SkyKey> aspectKeys = AspectCompletionValue.keys(aspects, topLevelArtifactContext);
      Iterable<SkyKey> testKeys =
          TestCompletionValue.keys(targetsToTest, topLevelArtifactContext, exclusiveTesting);
      return buildDriver.evaluate(
          Iterables.concat(artifactKeys, targetKeys, aspectKeys, testKeys),
          keepGoing,
          numJobs,
          reporter);
    } finally {
      progressReceiver.executionProgressReceiver = null;
      // Also releases thread locks.
      resourceManager.resetResourceUsage();
      skyframeActionExecutor.executionOver();
      actionExecutionFunction.complete();
    }
  }

  @VisibleForTesting
  public void prepareBuildingForTestingOnly(Reporter reporter, Executor executor, boolean keepGoing,
      boolean explain, ActionCacheChecker checker) {
    skyframeActionExecutor.prepareForExecution(reporter, executor, keepGoing, explain, checker,
        outputService);
  }

  EvaluationResult<TargetPatternValue> targetPatterns(Iterable<SkyKey> patternSkyKeys,
      int numThreads, boolean keepGoing, EventHandler eventHandler) throws InterruptedException {
    checkActive();
    return buildDriver.evaluate(patternSkyKeys, keepGoing, numThreads, eventHandler);
  }

  /**
   * Returns the {@link ConfiguredTarget}s corresponding to the given keys.
   *
   * <p>For use for legacy support and tests calling through {@code BuildView} only.
   *
   * <p>If a requested configured target is in error, the corresponding value is omitted from the
   * returned list.
   */
  @ThreadSafety.ThreadSafe
  // TODO(bazel-team): rename this and below methods to something that discourages general use
  public ImmutableList<ConfiguredTarget> getConfiguredTargets(
      EventHandler eventHandler, BuildConfiguration originalConfig, Iterable<Dependency> keys,
      boolean useOriginalConfig) {
    return getConfiguredTargetMap(
        eventHandler, originalConfig, keys, useOriginalConfig).values().asList();
  }

  /**
   * Returns a map from {@link Dependency} inputs to the {@link ConfiguredTarget}s corresponding
   * to those dependencies.
   *
   * <p>For use for legacy support and tests calling through {@code BuildView} only.
   *
   * <p>If a requested configured target is in error, the corresponding value is omitted from the
   * returned list.
   */
  @ThreadSafety.ThreadSafe
  public ImmutableMultimap<Dependency, ConfiguredTarget> getConfiguredTargetMap(
      EventHandler eventHandler, BuildConfiguration originalConfig, Iterable<Dependency> keys,
      boolean useOriginalConfig) {
    checkActive();

    Multimap<Dependency, BuildConfiguration> configs;
    if (originalConfig != null) {

      if (useOriginalConfig) {
        // This flag is used because of some unfortunate complexity in the configuration machinery:
        // Most callers of this method pass a <Label, Configuration> pair to directly create a
        // ConfiguredTarget from, but happen to use the Dependency data structure to pass that
        // info (even though the data has nothing to do with dependencies). If this configuration
        // includes a split transition, a dynamic configuration created from it will *not*
        // include that transition (because dynamic configurations don't embed transitions to
        // other configurations. In that case, we need to preserve the original configuration.
        // TODO(bazel-team); make this unnecessary once split transition logic is properly ported
        // out of configurations.
        configs = ArrayListMultimap.<Dependency, BuildConfiguration>create();
        configs.put(Iterables.getOnlyElement(keys), originalConfig);
      } else {
        configs = getConfigurations(eventHandler, originalConfig.getOptions(), keys);
      }
    } else {
      configs = ArrayListMultimap.<Dependency, BuildConfiguration>create();
      for (Dependency key : keys) {
        configs.put(key, null);
      }
    }

    final List<SkyKey> skyKeys = new ArrayList<>();
    for (Dependency key : keys) {
      if (!configs.containsKey(key)) {
        // If we couldn't compute a configuration for this target, the target was in error (e.g.
        // it couldn't be loaded). Exclude it from the results.
        continue;
      }
      for (BuildConfiguration depConfig : configs.get(key)) {
        skyKeys.add(ConfiguredTargetValue.key(key.getLabel(), depConfig));
        for (AspectDescriptor aspectDescriptor : key.getAspects()) {
          skyKeys.add(ActionLookupValue.key(AspectValue.createAspectKey(key.getLabel(), depConfig,
              aspectDescriptor, depConfig)));
        }
      }
    }

    EvaluationResult<SkyValue> result = evaluateSkyKeys(eventHandler, skyKeys);
    for (Map.Entry<SkyKey, ErrorInfo> entry : result.errorMap().entrySet()) {
      reportCycles(eventHandler, entry.getValue().getCycleInfo(), entry.getKey());
    }

    ImmutableMultimap.Builder<Dependency, ConfiguredTarget> cts =
        ImmutableMultimap.<Dependency, ConfiguredTarget>builder();

  DependentNodeLoop:
    for (Dependency key : keys) {
      if (!configs.containsKey(key)) {
        // If we couldn't compute a configuration for this target, the target was in error (e.g.
        // it couldn't be loaded). Exclude it from the results.
        continue;
      }
      for (BuildConfiguration depConfig : configs.get(key)) {
        SkyKey configuredTargetKey = ConfiguredTargetValue.key(
            key.getLabel(), depConfig);
        if (result.get(configuredTargetKey) == null) {
          continue;
        }

        ConfiguredTarget configuredTarget =
            ((ConfiguredTargetValue) result.get(configuredTargetKey)).getConfiguredTarget();
        List<ConfiguredAspect> configuredAspects = new ArrayList<>();

        for (AspectDescriptor aspectDescriptor : key.getAspects()) {
          SkyKey aspectKey = ActionLookupValue.key(AspectValue.createAspectKey(key.getLabel(),
              depConfig, aspectDescriptor, depConfig));
          if (result.get(aspectKey) == null) {
            continue DependentNodeLoop;
          }

          configuredAspects.add(((AspectValue) result.get(aspectKey)).getConfiguredAspect());
        }

        try {
          cts.put(key, MergedConfiguredTarget.of(configuredTarget, configuredAspects));
        } catch (DuplicateException e) {
          throw new IllegalStateException(
              String.format("Error creating %s", configuredTarget.getTarget().getLabel()),
              e);
        }
      }
    }

    return cts.build();
  }

  /**
   * Retrieves the configurations needed for the given deps. If
   * {@link BuildConfiguration.Options#trimConfigurations()} is true, trims their fragments to only
   * those needed by their transitive closures. Else unconditionally includes all fragments.
   *
   * <p>Skips targets with loading phase errors.
   */
  public Multimap<Dependency, BuildConfiguration> getConfigurations(EventHandler eventHandler,
      BuildOptions fromOptions, Iterable<Dependency> keys) {
    Multimap<Dependency, BuildConfiguration> builder =
        ArrayListMultimap.<Dependency, BuildConfiguration>create();
    Set<Dependency> depsToEvaluate = new HashSet<>();

    // Check: if !Configuration.useDynamicConfigs then just return the original configs.
    Set<Class<? extends BuildConfiguration.Fragment>> allFragments = null;
    if (useUntrimmedDynamicConfigs(fromOptions)) {
      allFragments = ((ConfiguredRuleClassProvider) ruleClassProvider).getAllFragments();
    }

    // Get the fragments needed for dynamic configuration nodes.
    final List<SkyKey> transitiveFragmentSkyKeys = new ArrayList<>();
    Map<Label, Set<Class<? extends BuildConfiguration.Fragment>>> fragmentsMap = new HashMap<>();
    Set<Label> labelsWithErrors = new HashSet<>();
    for (Dependency key : keys) {
      if (key.hasStaticConfiguration()) {
        builder.put(key, key.getConfiguration());
      } else if (useUntrimmedDynamicConfigs(fromOptions)) {
        fragmentsMap.put(key.getLabel(), allFragments);
      } else {
        depsToEvaluate.add(key);
        transitiveFragmentSkyKeys.add(TransitiveTargetValue.key(key.getLabel()));
      }
    }
    EvaluationResult<SkyValue> fragmentsResult = evaluateSkyKeys(
        eventHandler, transitiveFragmentSkyKeys, /*keepGoing=*/true);
    for (Map.Entry<SkyKey, ErrorInfo> entry : fragmentsResult.errorMap().entrySet()) {
      reportCycles(eventHandler, entry.getValue().getCycleInfo(), entry.getKey());
    }
    for (Dependency key : keys) {
      if (!depsToEvaluate.contains(key)) {
        // No fragments to compute here.
      } else if (fragmentsResult.getError(TransitiveTargetValue.key(key.getLabel())) != null) {
        labelsWithErrors.add(key.getLabel());
      } else {
        TransitiveTargetValue ttv =
            (TransitiveTargetValue) fragmentsResult.get(TransitiveTargetValue.key(key.getLabel()));
        fragmentsMap.put(key.getLabel(), ttv.getTransitiveConfigFragments().toSet());
      }
    }

    // Now get the configurations.
    final List<SkyKey> configSkyKeys = new ArrayList<>();
    for (Dependency key : keys) {
      if (labelsWithErrors.contains(key.getLabel()) || key.hasStaticConfiguration()) {
        continue;
      }
      Set<Class<? extends BuildConfiguration.Fragment>> depFragments =
          fragmentsMap.get(key.getLabel());
      if (depFragments != null) {
        for (BuildOptions toOptions : ConfiguredTargetFunction.getDynamicTransitionOptions(
            fromOptions, key.getTransition(), depFragments, ruleClassProvider, true)) {
          configSkyKeys.add(BuildConfigurationValue.key(depFragments, toOptions));
        }
      }
    }
    EvaluationResult<SkyValue> configsResult =
        evaluateSkyKeys(eventHandler, configSkyKeys, /*keepGoing=*/true);
    for (Dependency key : keys) {
      if (labelsWithErrors.contains(key.getLabel()) || key.hasStaticConfiguration()) {
        continue;
      }
      Set<Class<? extends BuildConfiguration.Fragment>> depFragments =
          fragmentsMap.get(key.getLabel());
      if (depFragments != null) {
        for (BuildOptions toOptions : ConfiguredTargetFunction.getDynamicTransitionOptions(
            fromOptions, key.getTransition(), depFragments, ruleClassProvider, true)) {
          SkyKey configKey = BuildConfigurationValue.key(depFragments, toOptions);
          builder.put(key,
              ((BuildConfigurationValue) configsResult.get(configKey)).getConfiguration());
        }
      }
    }

    return builder;
  }

  /**
   * Returns whether dynamic configurations should trim their fragments to only those needed by
   * targets and their transitive dependencies.
   */
  private static boolean useUntrimmedDynamicConfigs(BuildOptions options) {
    BuildConfiguration.Options.DynamicConfigsMode mode =
        options.get(BuildConfiguration.Options.class).useDynamicConfigurations;
    return mode == BuildConfiguration.Options.DynamicConfigsMode.NOTRIM
        || mode == BuildConfiguration.Options.DynamicConfigsMode.NOTRIM_PARTIAL;
  }

  /**
   * Evaluates the given sky keys, blocks, and returns their evaluation results. Fails fast
   * on the first evaluation error.
   */
  private EvaluationResult<SkyValue> evaluateSkyKeys(
      final EventHandler eventHandler, final Iterable<SkyKey> skyKeys) {
    return evaluateSkyKeys(eventHandler, skyKeys, false);
  }

  /**
   * Evaluates the given sky keys, blocks, and returns their evaluation results. Enables/disables
   * "keep going" on evaluation errors as specified.
   */
  private EvaluationResult<SkyValue> evaluateSkyKeys(
      final EventHandler eventHandler, final Iterable<SkyKey> skyKeys, final boolean keepGoing) {
    EvaluationResult<SkyValue> result;
    try {
      result = callUninterruptibly(new Callable<EvaluationResult<SkyValue>>() {
        @Override
        public EvaluationResult<SkyValue> call() throws Exception {
          synchronized (valueLookupLock) {
            try {
              skyframeBuildView.enableAnalysis(true);
              return buildDriver.evaluate(skyKeys, keepGoing, DEFAULT_THREAD_COUNT, eventHandler);
            } finally {
              skyframeBuildView.enableAnalysis(false);
            }
          }
        }
      });
    } catch (Exception e) {
      throw new IllegalStateException(e);  // Should never happen.
    }
    return result;
  }

  /**
   * Returns a dynamic configuration constructed from the given configuration fragments and build
   * options.
   */
  @VisibleForTesting
  public BuildConfiguration getConfigurationForTesting(
      EventHandler eventHandler,  Set<Class<? extends BuildConfiguration.Fragment>> fragments,
      BuildOptions options)
          throws InterruptedException {
    SkyKey key = BuildConfigurationValue.key(fragments, options);
    BuildConfigurationValue result = (BuildConfigurationValue) buildDriver
        .evaluate(ImmutableList.of(key), false, DEFAULT_THREAD_COUNT, eventHandler).get(key);
    return result.getConfiguration();
  }

  /**
   * Returns a particular configured target.
   *
   * <p>Used only for testing.
   */
  @VisibleForTesting
  @Nullable
  public ConfiguredTarget getConfiguredTargetForTesting(
      EventHandler eventHandler, Label label, BuildConfiguration configuration) {
    if (memoizingEvaluator.getExistingValueForTesting(
        PrecomputedValue.WORKSPACE_STATUS_KEY.getKeyForTesting()) == null) {
      injectWorkspaceStatusData(label.getWorkspaceRoot());
    }

    Dependency dep;
    if (configuration == null) {
      dep = Dependency.withNullConfiguration(label);
    } else if (configuration.useDynamicConfigurations()) {
      dep = Dependency.withTransitionAndAspects(label, Attribute.ConfigurationTransition.NONE,
          ImmutableSet.<AspectDescriptor>of());
    } else {
      dep = Dependency.withConfiguration(label, configuration);
    }

    return Iterables.getFirst(
        getConfiguredTargets(eventHandler, configuration, ImmutableList.of(dep), false),
        null);
  }

  /**
   * Invalidates Skyframe values corresponding to the given set of modified files under the given
   * path entry.
   *
   * <p>May throw an {@link InterruptedException}, which means that no values have been invalidated.
   */
  @VisibleForTesting
  public abstract void invalidateFilesUnderPathForTesting(EventHandler eventHandler,
      ModifiedFileSet modifiedFileSet, Path pathEntry) throws InterruptedException;

  /**
   * Invalidates SkyFrame values that may have failed for transient reasons.
   */
  public abstract void invalidateTransientErrors();

  /** Configures a given set of configured targets. */
  EvaluationResult<ActionLookupValue> configureTargets(
      EventHandler eventHandler,
      List<ConfiguredTargetKey> values,
      List<AspectValueKey> aspectKeys,
      boolean keepGoing,
      int numThreads)
      throws InterruptedException {
    checkActive();

    List<SkyKey> keys = new ArrayList<>(ConfiguredTargetValue.keys(values));
    for (AspectValueKey aspectKey : aspectKeys) {
      keys.add(aspectKey.getSkyKey());
    }
    EvaluationResult<ActionLookupValue> result =
        buildDriver.evaluate(keys, keepGoing, numThreads, eventHandler);
    // Get rid of any memory retained by the cache -- all loading is done.
    perBuildSyscallCache = null;
    return result;
  }

  /**
   * Post-process the targets. Values in the EvaluationResult are known to be transitively
   * error-free from action conflicts.
   */
  public EvaluationResult<PostConfiguredTargetValue> postConfigureTargets(
      EventHandler eventHandler, List<ConfiguredTargetKey> values, boolean keepGoing,
      ImmutableMap<ActionAnalysisMetadata, SkyframeActionExecutor.ConflictException> badActions)
          throws InterruptedException {
    checkActive();
    PrecomputedValue.BAD_ACTIONS.set(injectable(), badActions);
    // Make sure to not run too many analysis threads. This can cause memory thrashing.
    EvaluationResult<PostConfiguredTargetValue> result =
        buildDriver.evaluate(PostConfiguredTargetValue.keys(values), keepGoing,
            ResourceUsage.getAvailableProcessors(), eventHandler);

    // Remove all post-configured target values immediately for memory efficiency. We are OK with
    // this mini-phase being non-incremental as the failure mode of action conflict is rare.
    memoizingEvaluator.delete(SkyFunctionName.functionIs(SkyFunctions.POST_CONFIGURED_TARGET));

    return result;
  }

  /**
   * Returns a Skyframe-based {@link SkyframeTransitivePackageLoader} implementation.
   */
  @VisibleForTesting
  public TransitivePackageLoader pkgLoader() {
    checkActive();
    return new SkyframeLabelVisitor(new SkyframeTransitivePackageLoader(), cyclesReporter);
  }

  class SkyframeTransitivePackageLoader {
    /**
     * Loads the specified {@link TransitiveTargetValue}s.
     */
    EvaluationResult<TransitiveTargetValue> loadTransitiveTargets(EventHandler eventHandler,
            Iterable<Target> targetsToVisit, Iterable<Label> labelsToVisit, boolean keepGoing,
            int parallelThreads)
        throws InterruptedException {
      List<SkyKey> valueNames = new ArrayList<>();
      for (Target target : targetsToVisit) {
        valueNames.add(TransitiveTargetValue.key(target.getLabel()));
      }
      for (Label label : labelsToVisit) {
        valueNames.add(TransitiveTargetValue.key(label));
      }

      return buildDriver.evaluate(valueNames, keepGoing, parallelThreads, eventHandler);
    }
  }

  /**
   * For internal use in queries: performs a graph update to make sure the transitive closure of the
   * specified {@code universeKey} is present in the graph, and returns the {@link
   * EvaluationResult}.
   *
   * <p>The graph update is unconditionally done in keep-going mode, so that the query is guaranteed
   * a complete graph to work on.
   */
  @Override
  public EvaluationResult<SkyValue> prepareAndGet(
      SkyKey universeKey, int numThreads, EventHandler eventHandler) throws InterruptedException {
    EvaluationResult<SkyValue> evaluationResult =
        buildDriver.evaluate(ImmutableList.of(universeKey), true, numThreads, eventHandler);
    Preconditions.checkNotNull(evaluationResult.getWalkableGraph(), universeKey);
    return evaluationResult;
  }

  @Override
  public boolean isUpToDate(SkyKey universeKey) {
    return buildDriver.alreadyEvaluated(ImmutableList.of(universeKey));
  }

  /**
   * Get metadata related to the prepareAndGet() lookup. Resulting data is specific to the
   * underlying evaluation implementation.
   */
   public String prepareAndGetMetadata(Collection<String> patterns, String offset,
      OptionsClassProvider options) throws AbruptExitException, InterruptedException {
    return buildDriver.meta(ImmutableList.of(getUniverseKey(patterns, offset)), options);
  }

  @Override
  public SkyKey getUniverseKey(Collection<String> patterns, String offset) {
    return PrepareDepsOfPatternsValue.key(ImmutableList.copyOf(patterns), offset);
  }

  /**
   * Returns the generating action of a given artifact ({@code null} if it's a source artifact).
   */
  private ActionAnalysisMetadata getGeneratingAction(EventHandler eventHandler, Artifact artifact)
      throws InterruptedException {
    if (artifact.isSourceArtifact()) {
      return null;
    }

    ArtifactOwner artifactOwner = artifact.getArtifactOwner();
    Preconditions.checkState(artifactOwner instanceof ActionLookupValue.ActionLookupKey,
        "%s %s", artifact, artifactOwner);
    SkyKey actionLookupKey =
        ActionLookupValue.key((ActionLookupValue.ActionLookupKey) artifactOwner);

    synchronized (valueLookupLock) {
      // Note that this will crash (attempting to run a configured target value builder after
      // analysis) after a failed --nokeep_going analysis in which the configured target that
      // failed was a (transitive) dependency of the configured target that should generate
      // this action. We don't expect callers to query generating actions in such cases.
      EvaluationResult<ActionLookupValue> result = buildDriver.evaluate(
          ImmutableList.of(actionLookupKey), false, ResourceUsage.getAvailableProcessors(),
          eventHandler);
      return result.hasError()
          ? null
          : result.get(actionLookupKey).getGeneratingAction(artifact);
    }
  }

  /**
   * Returns an action graph.
   *
   * <p>For legacy compatibility only.
   */
  public ActionGraph getActionGraph(final EventHandler eventHandler) {
    return new ActionGraph() {
      @Override
      public ActionAnalysisMetadata getGeneratingAction(final Artifact artifact) {
        try {
          return callUninterruptibly(new Callable<ActionAnalysisMetadata>() {
            @Override
            public ActionAnalysisMetadata call() throws InterruptedException {
              return SkyframeExecutor.this.getGeneratingAction(eventHandler, artifact);
            }
          });
        } catch (Exception e) {
          throw new IllegalStateException("Error getting generating action: "
              + artifact.prettyPrint(), e);
        }
      }
    };
  }

  public PackageManager getPackageManager() {
    return packageManager;
  }

  class SkyframePackageLoader {
    /**
     * Looks up a particular package (mostly used after the loading phase, so packages should
     * already be present, but occasionally used pre-loading phase). Use should be discouraged,
     * since this cannot be used inside a Skyframe evaluation, and concurrent calls are
     * synchronized.
     *
     * <p>Note that this method needs to be synchronized since InMemoryMemoizingEvaluator.evaluate()
     * method does not support concurrent calls.
     */
    Package getPackage(EventHandler eventHandler, PackageIdentifier pkgName)
        throws InterruptedException, NoSuchPackageException {
      synchronized (valueLookupLock) {
        SkyKey key = PackageValue.key(pkgName);
        // Any call to this method post-loading phase should either be error-free or be in a
        // keep_going build, since otherwise the build would have failed during loading. Thus
        // we set keepGoing=true unconditionally.
        EvaluationResult<PackageValue> result =
            buildDriver.evaluate(ImmutableList.of(key), /*keepGoing=*/true,
                DEFAULT_THREAD_COUNT, eventHandler);
        ErrorInfo error = result.getError(key);
        if (error != null) {
          if (!Iterables.isEmpty(error.getCycleInfo())) {
            reportCycles(eventHandler, result.getError().getCycleInfo(), key);
            // This can only happen if a package is freshly loaded outside of the target parsing
            // or loading phase
            throw new BuildFileContainsErrorsException(
                pkgName, "Cycle encountered while loading package " + pkgName);
          }
          Throwable e = error.getException();
          // PackageFunction should be catching, swallowing, and rethrowing all transitive
          // errors as NoSuchPackageExceptions or constructing packages with errors, since we're in
          // keep_going mode.
          Throwables.propagateIfInstanceOf(e, NoSuchPackageException.class);
          throw new IllegalStateException("Unexpected Exception type from PackageValue for '"
              + pkgName + "'' with root causes: " + Iterables.toString(error.getRootCauses()), e);
        }
        return result.get(key).getPackage();
      }
    }

    /**
     * Returns whether the given package should be consider deleted and thus should be ignored.
     */
    public boolean isPackageDeleted(PackageIdentifier packageName) {
      Preconditions.checkState(!packageName.getRepository().isDefault(),
          "package must be absolute: %s", packageName);
      return deletedPackages.get().contains(packageName);
    }

    /** Same as {@link PackageManager#partiallyClear}. */
    void partiallyClear() {
      packageFunctionCache.invalidateAll();
    }
  }

  @VisibleForTesting
  public MemoizingEvaluator getEvaluatorForTesting() {
    return memoizingEvaluator;
  }

  public void sync(
      EventHandler eventHandler,
      PackageCacheOptions packageCacheOptions,
      Path outputBase,
      Path workingDirectory,
      String defaultsPackageContents,
      UUID commandId,
      Map<String, String> clientEnv,
      TimestampGranularityMonitor tsgm,
      OptionsClassProvider options)
      throws InterruptedException, AbruptExitException {
    preparePackageLoading(
        createPackageLocator(
            eventHandler,
            packageCacheOptions,
            outputBase,
            directories.getWorkspace(),
            workingDirectory),
        packageCacheOptions,
        defaultsPackageContents,
        commandId,
        clientEnv,
        tsgm);
    setDeletedPackages(packageCacheOptions.getDeletedPackages());

    incrementalBuildMonitor = new SkyframeIncrementalBuildMonitor();
    invalidateTransientErrors();
  }

  protected PathPackageLocator createPackageLocator(EventHandler eventHandler,
      PackageCacheOptions packageCacheOptions, Path outputBase, Path workspace,
      Path workingDirectory) throws AbruptExitException {
    return PathPackageLocator.create(
        outputBase, packageCacheOptions.packagePath, eventHandler, workspace, workingDirectory);
  }

  private CyclesReporter createCyclesReporter() {
    return new CyclesReporter(
        new TransitiveTargetCycleReporter(packageManager),
        new ActionArtifactCycleReporter(packageManager),
        // TODO(ulfjack): The SkylarkModuleCycleReporter swallows previously reported cycles
        // unconditionally! Is that intentional?
        new ConfiguredTargetCycleReporter(packageManager),
        new SkylarkModuleCycleReporter());
  }

  CyclesReporter getCyclesReporter() {
    return cyclesReporter.get();
  }

  /** Convenience method with same semantics as {@link CyclesReporter#reportCycles}. */
  public void reportCycles(EventHandler eventHandler, Iterable<CycleInfo> cycles,
      SkyKey topLevelKey) {
    getCyclesReporter().reportCycles(cycles, topLevelKey, eventHandler);
  }

  public void setActionExecutionProgressReportingObjects(@Nullable ProgressSupplier supplier,
      @Nullable ActionCompletedReceiver completionReceiver,
      @Nullable ActionExecutionStatusReporter statusReporter) {
    skyframeActionExecutor.setActionExecutionProgressReportingObjects(supplier, completionReceiver);
    this.statusReporterRef.set(statusReporter);
  }

  public void prepareExecution(ModifiedFileSet modifiedOutputFiles,
      @Nullable Range<Long> lastExecutionTimeRange)
          throws AbruptExitException, InterruptedException {
    maybeInjectEmbeddedArtifacts();

    // Detect external modifications in the output tree.
    FilesystemValueChecker fsvc =
        new FilesystemValueChecker(Preconditions.checkNotNull(tsgm.get()), lastExecutionTimeRange);
    BatchStat batchStatter = outputService == null ? null : outputService.getBatchStatter();
    invalidateDirtyActions(fsvc.getDirtyActionValues(memoizingEvaluator.getValues(),
        batchStatter, modifiedOutputFiles));
    modifiedFiles += fsvc.getNumberOfModifiedOutputFiles();
    outputDirtyFiles += fsvc.getNumberOfModifiedOutputFiles();
    modifiedFilesDuringPreviousBuild += fsvc.getNumberOfModifiedOutputFilesDuringPreviousBuild();
    informAboutNumberOfModifiedFiles();
  }

  protected abstract void invalidateDirtyActions(Iterable<SkyKey> dirtyActionValues);

  @VisibleForTesting void maybeInjectEmbeddedArtifacts() throws AbruptExitException {
    // The blaze client already ensures that the contents of the embedded binaries never change,
    // so we just need to make sure that the appropriate artifacts are present in the skyframe
    // graph.

    if (!needToInjectEmbeddedArtifacts) {
      return;
    }

    Preconditions.checkNotNull(artifactFactory.get());
    Preconditions.checkNotNull(binTools);
    Map<SkyKey, SkyValue> values = Maps.newHashMap();
    // Blaze separately handles the symlinks that target these binaries. See BinTools#setupTool.
    for (Artifact artifact : binTools.getAllEmbeddedArtifacts(artifactFactory.get())) {
      FileArtifactValue fileArtifactValue;
      try {
        fileArtifactValue = FileArtifactValue.create(artifact);
      } catch (IOException e) {
        // See ExtractData in blaze.cc.
        String message = "Error: corrupt installation: file " + artifact.getPath() + " missing. "
            + "Please remove '" + directories.getInstallBase() + "' and try again.";
        throw new AbruptExitException(message, ExitCode.LOCAL_ENVIRONMENTAL_ERROR, e);
      }
      values.put(ArtifactSkyKey.key(artifact, /*isMandatory=*/ true), fileArtifactValue);
    }
    injectable().inject(values);
    needToInjectEmbeddedArtifacts = false;
  }

  /**
   * Mark dirty values for deletion if they've been dirty for longer than N versions.
   *
   * <p>Specifying a value N means, if the current version is V and a value was dirtied (and
   * has remained so) in version U, and U + N &lt;= V, then the value will be marked for deletion
   * and purged in version V+1.
   */
  public abstract void deleteOldNodes(long versionWindowForDirtyGc);

  public LoadingPhaseRunner getLoadingPhaseRunner(Set<String> ruleClassNames, boolean useNewImpl) {
    if (!useNewImpl) {
      return new LegacyLoadingPhaseRunner(packageManager, ruleClassNames);
    } else {
      return new SkyframeLoadingPhaseRunner(ruleClassNames);
    }
  }

  /**
   * Skyframe-based implementation of {@link LoadingPhaseRunner} based on {@link
   * TargetPatternPhaseFunction}.
   */
  final class SkyframeLoadingPhaseRunner extends LoadingPhaseRunner {
    private final Set<String> ruleClassNames;

    public SkyframeLoadingPhaseRunner(Set<String> ruleClassNames) {
      this.ruleClassNames = ruleClassNames;
    }

    @Override
    public LoadingResult execute(
        EventHandler eventHandler,
        EventBus eventBus,
        List<String> targetPatterns,
        PathFragment relativeWorkingDirectory,
        LoadingOptions options,
        boolean keepGoing,
        boolean determineTests,
        @Nullable LoadingCallback callback)
        throws TargetParsingException, LoadingFailedException, InterruptedException {
      Stopwatch timer = Stopwatch.createStarted();
      SkyKey key = TargetPatternPhaseValue.key(ImmutableList.copyOf(targetPatterns),
          relativeWorkingDirectory.getPathString(), options.compileOneDependency,
          options.buildTestsOnly, determineTests,
          ImmutableList.copyOf(options.buildTagFilterList),
          TestFilter.forOptions(options, eventHandler, ruleClassNames));
      EvaluationResult<TargetPatternPhaseValue> evalResult;
      eventBus.post(new LoadingPhaseStartedEvent(packageProgress));
      evalResult =
          buildDriver.evaluate(ImmutableList.of(key), keepGoing, /*numThreads=*/ 10, eventHandler);
      if (evalResult.hasError()) {
        ErrorInfo errorInfo = evalResult.getError(key);
        if (!Iterables.isEmpty(errorInfo.getCycleInfo())) {
          String errorMessage = "cycles detected during target parsing";
          getCyclesReporter().reportCycles(errorInfo.getCycleInfo(), key, eventHandler);
          throw new TargetParsingException(errorMessage);
        }
        if (errorInfo.getException() != null) {
          Exception e = errorInfo.getException();
          Throwables.propagateIfInstanceOf(e, TargetParsingException.class);
          if (!keepGoing) {
            // This is the same code as in SkyframeTargetPatternEvaluator; we allow any exception
            // and turn it into a TargetParsingException here.
            throw new TargetParsingException(e.getMessage());
          }
          throw new IllegalStateException("Unexpected Exception type from TargetPatternPhaseValue "
              + "for '" + targetPatterns + "'' with root causes: "
              + Iterables.toString(errorInfo.getRootCauses()), e);
        }
      }
      long time = timer.stop().elapsed(TimeUnit.MILLISECONDS);

      TargetPatternPhaseValue patternParsingValue = evalResult.get(key);
      eventBus.post(
          new TargetParsingCompleteEvent(
              patternParsingValue.getOriginalTargets(),
              patternParsingValue.getFilteredTargets(),
              patternParsingValue.getTestFilteredTargets(),
              time,
              targetPatterns,
              patternParsingValue.getTargets()));
      if (callback != null) {
        callback.notifyTargets(patternParsingValue.getTargets());
      }
      eventBus.post(new LoadingPhaseCompleteEvent(
          patternParsingValue.getTargets(), patternParsingValue.getTestSuiteTargets(),
          packageManager.getStatistics(), /*timeInMs=*/0));
      return patternParsingValue.toLoadingResult();
    }
  }

  /**
   * A progress received to track analysis invalidation and update progress messages.
   */
  protected class SkyframeProgressReceiver implements EvaluationProgressReceiver {
    /**
     * This flag is needed in order to avoid invalidating legacy data when we clear the
     * analysis cache because of --discard_analysis_cache flag. For that case we want to keep
     * the legacy data but get rid of the Skyframe data.
     */
    protected boolean ignoreInvalidations = false;
    /** This receiver is only needed for execution, so it is null otherwise. */
    @Nullable EvaluationProgressReceiver executionProgressReceiver = null;
    /** This receiver is only needed for loading, so it is null otherwise. */

    @Override
    public void invalidated(SkyKey skyKey, InvalidationState state) {
      if (ignoreInvalidations) {
        return;
      }
      skyframeBuildView.getProgressReceiver().invalidated(skyKey, state);
    }

    @Override
    public void enqueueing(SkyKey skyKey) {
      if (ignoreInvalidations) {
        return;
      }
      skyframeBuildView.getProgressReceiver().enqueueing(skyKey);
      if (executionProgressReceiver != null) {
        executionProgressReceiver.enqueueing(skyKey);
      }
    }

    @Override
    public void computed(SkyKey skyKey, long elapsedTimeNanos) {}

    @Override
    public void evaluated(SkyKey skyKey, Supplier<SkyValue> valueSupplier, EvaluationState state) {
      if (ignoreInvalidations) {
        return;
      }
      skyframeBuildView.getProgressReceiver().evaluated(skyKey, valueSupplier, state);
      if (executionProgressReceiver != null) {
        executionProgressReceiver.evaluated(skyKey, valueSupplier, state);
      }
    }
  }

  public int getOutputDirtyFilesAndClear() {
    int result = outputDirtyFiles;
    outputDirtyFiles = 0;
    return result;
  }

  public int getModifiedFilesDuringPreviousBuildAndClear() {
    int result = modifiedFilesDuringPreviousBuild;
    modifiedFilesDuringPreviousBuild = 0;
    return result;
  }
}
