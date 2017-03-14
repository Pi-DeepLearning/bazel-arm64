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
package com.google.devtools.build.lib.buildtool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.base.Verify;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.analysis.BuildInfoEvent;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.BuildView.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider.TargetLicense;
import com.google.devtools.build.lib.analysis.MakeEnvironmentEvent;
import com.google.devtools.build.lib.analysis.StaticallyLinkedMarkerProvider;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.DefaultsPackage;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics;
import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection;
import com.google.devtools.build.lib.analysis.constraints.SupportedEnvironmentsProvider;
import com.google.devtools.build.lib.buildtool.BuildRequest.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.OutputFilter;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.License.DistributionType;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.pkgcache.LoadedPackageProvider;
import com.google.devtools.build.lib.pkgcache.LoadingCallback;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner;
import com.google.devtools.build.lib.pkgcache.LoadingResult;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Provides the bulk of the implementation of the 'blaze build' command.
 *
 * <p>The various concrete build command classes handle the command options and request
 * setup, then delegate the handling of the request (the building of targets) to this class.
 *
 * <p>The main entry point is {@link #buildTargets}.
 *
 * <p>This class is always instantiated and managed as a singleton, being constructed and held by
 * {@link BlazeRuntime}. This is so multiple kinds of build commands can share this single
 * instance.
 *
 * <p>Most of analysis is handled in {@link BuildView}, and execution in {@link ExecutionTool}.
 */
public final class BuildTool {

  private static final Logger LOG = Logger.getLogger(BuildTool.class.getName());

  private final CommandEnvironment env;
  private final BlazeRuntime runtime;

  /**
   * Constructs a BuildTool.
   *
   * @param env a reference to the command environment of the currently executing command
   */
  public BuildTool(CommandEnvironment env) {
    this.env = env;
    this.runtime = env.getRuntime();
  }

  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>Callers must ensure that {@link #stopRequest} is called after this method, even if it
   * throws.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets
   * fields of the request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *        options; during this method's execution, the actualTargets and successfulTargets fields
   *        of the request object are populated
   * @param result the build result that is the mutable result of this build
   * @param validator target validator
   */
  public void buildTargets(BuildRequest request, BuildResult result, TargetValidator validator)
      throws BuildFailedException, InterruptedException, ViewCreationFailedException,
          TargetParsingException, LoadingFailedException, AbruptExitException,
          InvalidConfigurationException, TestExecException {
    validateOptions(request);
    BuildOptions buildOptions = runtime.createBuildOptions(request);
    // Sync the package manager before sending the BuildStartingEvent in runLoadingPhase()
    env.setupPackageCache(request, DefaultsPackage.getDefaultsPackageContent(buildOptions));

    ExecutionTool executionTool = null;
    LoadingResult loadingResult = null;
    BuildConfigurationCollection configurations = null;
    boolean catastrophe = false;
    try {
      env.getEventBus().post(new BuildStartingEvent(env, request));
      LOG.info("Build identifier: " + request.getId());
      executionTool = new ExecutionTool(env, request);
      if (needsExecutionPhase(request.getBuildOptions())) {
        // Initialize the execution tool early if we need it. This hides the latency of setting up
        // the execution backends.
        executionTool.init();
      }

      // Error out early if multi_cpus is set, but we're not in build or test command.
      if (!request.getMultiCpus().isEmpty()) {
        getReporter().handle(Event.warn(
            "The --experimental_multi_cpu option is _very_ experimental and only intended for "
            + "internal testing at this time. If you do not work on the build tool, then you "
            + "should stop now!"));
        if (!"build".equals(request.getCommandName()) && !"test".equals(request.getCommandName())) {
          throw new InvalidConfigurationException(
              "The experimental setting to select multiple CPUs is only supported for 'build' and "
              + "'test' right now!");
        }
      }

      // Exit if there are any pending exceptions from modules.
      env.throwPendingException();

      // Target pattern evaluation.
      loadingResult = evaluateTargetPatterns(request, validator);

      // Exit if there are any pending exceptions from modules.
      env.throwPendingException();

      // Configuration creation.
      configurations =
          env.getSkyframeExecutor()
              .createConfigurations(
                  env.getReporter(),
                  runtime.getConfigurationFactory(),
                  buildOptions,
                  request.getMultiCpus(),
                  request.getViewOptions().keepGoing);

      env.throwPendingException();
      if (configurations.getTargetConfigurations().size() == 1) {
        // TODO(bazel-team): This is not optimal - we retain backwards compatibility in the case
        // where there's only a single configuration, but we don't send an event in the multi-config
        // case. Can we do better? [multi-config]
        env.getEventBus().post(new MakeEnvironmentEvent(
            configurations.getTargetConfigurations().get(0).getMakeEnvironment()));
      }
      LOG.info("Configurations created");

      if (request.getBuildOptions().performAnalysisPhase) {
        AnalysisResult analysisResult = runAnalysisPhase(request, loadingResult, configurations);
        result.setBuildConfigurationCollection(configurations);
        result.setActualTargets(analysisResult.getTargetsToBuild());
        result.setTestTargets(analysisResult.getTargetsToTest());

        LoadedPackageProvider bridge =
            new LoadedPackageProvider(env.getPackageManager(), env.getReporter());
        checkTargetEnvironmentRestrictions(analysisResult.getTargetsToBuild(), bridge);
        reportTargets(analysisResult);

        // Execution phase.
        if (needsExecutionPhase(request.getBuildOptions())) {
          executionTool.executeBuild(
              request.getId(),
              analysisResult,
              result,
              configurations,
              analysisResult.getPackageRoots(),
              request.getTopLevelArtifactContext());
        }
        String delayedErrorMsg = analysisResult.getError();
        if (delayedErrorMsg != null) {
          throw new BuildFailedException(delayedErrorMsg);
        }
      } else {
        getReporter().handle(Event.progress("Loading complete."));
        LOG.info("No analysis requested, so finished");
        String errorMessage = BuildView.createErrorMessage(loadingResult, null);
        if (errorMessage != null) {
          throw new BuildFailedException(errorMessage);
        }
        // Return.
      }
    } catch (RuntimeException e) {
      // Print an error message for unchecked runtime exceptions. This does not concern Error
      // subclasses such as OutOfMemoryError.
      request.getOutErr().printErrLn("Unhandled exception thrown during build; message: " +
          e.getMessage());
      catastrophe = true;
      throw e;
    } catch (Error e) {
      catastrophe = true;
      throw e;
    } finally {
      if (!catastrophe) {
        // Delete dirty nodes to ensure that they do not accumulate indefinitely.
        long versionWindow = request.getViewOptions().versionWindowForDirtyNodeGc;
        if (versionWindow != -1) {
          env.getSkyframeExecutor().deleteOldNodes(versionWindow);
        }

        if (executionTool != null) {
          executionTool.shutdown();
        }
        // The workspace status actions will not run with certain flags, or if an error
        // occurs early in the build. Tell a lie so that the event is not missing.
        // If multiple build_info events are sent, only the first is kept, so this does not harm
        // successful runs (which use the workspace status action).
        env.getEventBus()
            .post(
                new BuildInfoEvent(
                    env.getBlazeWorkspace().getWorkspaceStatusActionFactory()
                        .createDummyWorkspaceStatus()));
      }
    }
  }

  /**
   * Checks that if this is an environment-restricted build, all top-level targets support the
   * expected environments.
   *
   * @param topLevelTargets the build's top-level targets
   * @throws ViewCreationFailedException if constraint enforcement is on, the build declares
   *     environment-restricted top level configurations, and any top-level target doesn't support
   *     the expected environments
   */
  private static void checkTargetEnvironmentRestrictions(
      Iterable<ConfiguredTarget> topLevelTargets, LoadedPackageProvider packageManager)
      throws ViewCreationFailedException, InterruptedException {
    for (ConfiguredTarget topLevelTarget : topLevelTargets) {
      BuildConfiguration config = topLevelTarget.getConfiguration();
      if (config == null) {
        // TODO(bazel-team): support file targets (they should apply package-default constraints).
        continue;
      } else if (!config.enforceConstraints() || config.getTargetEnvironments().isEmpty()) {
        continue;
      }

      // Parse and collect this configuration's environments.
      EnvironmentCollection.Builder builder = new EnvironmentCollection.Builder();
      for (Label envLabel : config.getTargetEnvironments()) {
        try {
          Target env = packageManager.getLoadedTarget(envLabel);
          builder.put(ConstraintSemantics.getEnvironmentGroup(env), envLabel);
        } catch (NoSuchPackageException | NoSuchTargetException
            | ConstraintSemantics.EnvironmentLookupException e) {
          throw new ViewCreationFailedException("invalid target environment", e);
        }
      }
      EnvironmentCollection expectedEnvironments = builder.build();

      // Now check the target against those environments.
      SupportedEnvironmentsProvider provider =
          Verify.verifyNotNull(topLevelTarget.getProvider(SupportedEnvironmentsProvider.class));
        Collection<Label> missingEnvironments = ConstraintSemantics.getUnsupportedEnvironments(
            provider.getRefinedEnvironments(), expectedEnvironments);
        if (!missingEnvironments.isEmpty()) {
          throw new ViewCreationFailedException(
              String.format("This is a restricted-environment build. %s does not support"
                  + " required environment%s %s",
                  topLevelTarget.getLabel(),
                  missingEnvironments.size() == 1 ? "" : "s",
                  Joiner.on(", ").join(missingEnvironments)));
        }
    }
  }

  private void reportExceptionError(Exception e) {
    if (e.getMessage() != null) {
      getReporter().handle(Event.error(e.getMessage()));
    }
  }
  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets
   * fields of the request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *        options; during this method's execution, the actualTargets and successfulTargets fields
   *        of the request object are populated
   * @param validator target validator
   * @return the result as a {@link BuildResult} object
   */
  public BuildResult processRequest(BuildRequest request, TargetValidator validator) {
    BuildResult result = new BuildResult(request.getStartTime());
    env.getEventBus().register(result);
    maybeSetStopOnFirstFailure(request, result);
    Throwable catastrophe = null;
    ExitCode exitCode = ExitCode.BLAZE_INTERNAL_ERROR;
    try {
      buildTargets(request, result, validator);
      exitCode = ExitCode.SUCCESS;
    } catch (BuildFailedException e) {
      if (e.isErrorAlreadyShown()) {
        // The actual error has already been reported by the Builder.
      } else {
        reportExceptionError(e);
      }
      if (e.isCatastrophic()) {
        result.setCatastrophe();
      }
      exitCode = e.getExitCode() != null ? e.getExitCode() : ExitCode.BUILD_FAILURE;
    } catch (InterruptedException e) {
      // We may have been interrupted by an error, or the user's interruption may have raced with
      // an error, so check to see if we should report that error code instead.
      exitCode = env.getPendingExitCode();
      if (exitCode == null) {
        exitCode = ExitCode.INTERRUPTED;
        env.getReporter().handle(Event.error("build interrupted"));
        env.getEventBus().post(new BuildInterruptedEvent());
      } else {
        // Report the exception from the environment - the exception we're handling here is just an
        // interruption.
        reportExceptionError(env.getPendingException());
        result.setCatastrophe();
      }
    } catch (TargetParsingException | LoadingFailedException | ViewCreationFailedException e) {
      exitCode = ExitCode.PARSING_FAILURE;
      reportExceptionError(e);
    } catch (TestExecException e) {
      // ExitCode.SUCCESS means that build was successful. Real return code of program
      // is going to be calculated in TestCommand.doTest().
      exitCode = ExitCode.SUCCESS;
      reportExceptionError(e);
    } catch (InvalidConfigurationException e) {
      exitCode = ExitCode.COMMAND_LINE_ERROR;
      reportExceptionError(e);
    } catch (AbruptExitException e) {
      exitCode = e.getExitCode();
      reportExceptionError(e);
      result.setCatastrophe();
    } catch (Throwable throwable) {
      catastrophe = throwable;
      Throwables.propagate(throwable);
    } finally {
      stopRequest(result, catastrophe, exitCode);
    }

    return result;
  }

  private void maybeSetStopOnFirstFailure(BuildRequest request, BuildResult result) {
    if (shouldStopOnFailure(request)) {
      result.setStopOnFirstFailure(true);
    }
  }

  private boolean shouldStopOnFailure(BuildRequest request) {
    return !(request.getViewOptions().keepGoing && request.getExecutionOptions().testKeepGoing);
  }

  private final LoadingResult evaluateTargetPatterns(
      final BuildRequest request, final TargetValidator validator)
      throws LoadingFailedException, TargetParsingException, InterruptedException {
    Profiler.instance().markPhase(ProfilePhase.LOAD);
    initializeOutputFilter(request);

    final boolean keepGoing = request.getViewOptions().keepGoing;

    LoadingCallback callback = new LoadingCallback() {
      @Override
      public void notifyTargets(Collection<Target> targets) throws LoadingFailedException {
        if (validator != null) {
          validator.validateTargets(targets, keepGoing);
        }
      }
    };

    LoadingPhaseRunner loadingPhaseRunner = env.getSkyframeExecutor().getLoadingPhaseRunner(
        runtime.getPackageFactory().getRuleClassNames(),
        request.getLoadingOptions().useSkyframeTargetPatternEvaluator);
    LoadingResult result =
        loadingPhaseRunner.execute(
            getReporter(),
            env.getEventBus(),
            request.getTargets(),
            env.getRelativeWorkingDirectory(),
            request.getLoadingOptions(),
            keepGoing,
            request.shouldRunTests(),
            callback);
    return result;
  }

  /**
   * Initializes the output filter to the value given with {@code --output_filter}.
   */
  private void initializeOutputFilter(BuildRequest request) {
    Pattern outputFilter = request.getBuildOptions().outputFilter;
    if (outputFilter != null) {
      getReporter().setOutputFilter(OutputFilter.RegexOutputFilter.forPattern(outputFilter));
    }
  }

  /**
   * Performs the initial phases 0-2 of the build: Setup, Loading and Analysis.
   * <p>
   * Postcondition: On success, populates the BuildRequest's set of targets to
   * build.
   *
   * @return null if loading / analysis phases were successful; a useful error
   *         message if loading or analysis phase errors were encountered and
   *         request.keepGoing.
   * @throws InterruptedException if the current thread was interrupted.
   * @throws ViewCreationFailedException if analysis failed for any reason.
   */
  private AnalysisResult runAnalysisPhase(BuildRequest request, LoadingResult loadingResult,
      BuildConfigurationCollection configurations)
      throws InterruptedException, ViewCreationFailedException {
    Stopwatch timer = Stopwatch.createStarted();
    getReporter().handle(Event.progress("Loading complete.  Analyzing..."));
    Profiler.instance().markPhase(ProfilePhase.ANALYZE);

    BuildView view = new BuildView(env.getDirectories(), runtime.getRuleClassProvider(),
        env.getSkyframeExecutor(), runtime.getCoverageReportActionFactory(request));
    AnalysisResult analysisResult =
        view.update(
            loadingResult,
            configurations,
            request.getAspects(),
            request.getViewOptions(),
            request.getTopLevelArtifactContext(),
            env.getReporter(),
            env.getEventBus());

    // TODO(bazel-team): Merge these into one event.
    env.getEventBus().post(new AnalysisPhaseCompleteEvent(analysisResult.getTargetsToBuild(),
        view.getTargetsVisited(), timer.stop().elapsed(TimeUnit.MILLISECONDS)));
    env.getEventBus().post(new TestFilteringCompleteEvent(analysisResult.getTargetsToBuild(),
        analysisResult.getTargetsToTest()));

    // Check licenses.
    // We check licenses if the first target configuration has license checking enabled. Right now,
    // it is not possible to have multiple target configurations with different settings for this
    // flag, which allows us to take this short cut.
    boolean checkLicenses = configurations.getTargetConfigurations().get(0).checkLicenses();
    if (checkLicenses) {
      Profiler.instance().markPhase(ProfilePhase.LICENSE);
      validateLicensingForTargets(analysisResult.getTargetsToBuild(),
          request.getViewOptions().keepGoing);
    }

    return analysisResult;
  }

  private static boolean needsExecutionPhase(BuildRequestOptions options) {
    return options.performAnalysisPhase && options.performExecutionPhase;
  }

  /**
   * Stops processing the specified request.
   *
   * <p>This logs the build result, cleans up and stops the clock.
   *
   * @param crash Any unexpected RuntimeException or Error. May be null
   * @param exitCondition A suggested exit condition from either the build logic or
   *        a thrown exception somewhere along the way.
   */
  public void stopRequest(BuildResult result, Throwable crash, ExitCode exitCondition) {
    Preconditions.checkState((crash == null) || !exitCondition.equals(ExitCode.SUCCESS));
    result.setUnhandledThrowable(crash);
    result.setExitCondition(exitCondition);
    // The stop time has to be captured before we send the BuildCompleteEvent.
    result.setStopTime(runtime.getClock().currentTimeMillis());
    env.getEventBus().post(new BuildCompleteEvent(result));
  }

  private void reportTargets(AnalysisResult analysisResult) {
    Collection<ConfiguredTarget> targetsToBuild = analysisResult.getTargetsToBuild();
    Collection<ConfiguredTarget> targetsToTest = analysisResult.getTargetsToTest();
    if (targetsToTest != null) {
      int testCount = targetsToTest.size();
      int targetCount = targetsToBuild.size() - testCount;
      if (targetCount == 0) {
        getReporter().handle(Event.info("Found "
            + testCount + (testCount == 1 ? " test target..." : " test targets...")));
      } else {
        getReporter().handle(Event.info("Found "
            + targetCount + (targetCount == 1 ? " target and " : " targets and ")
            + testCount + (testCount == 1 ? " test target..." : " test targets...")));
      }
    } else {
      int targetCount = targetsToBuild.size();
      getReporter().handle(Event.info("Found "
          + targetCount + (targetCount == 1 ? " target..." : " targets...")));
    }
  }

  /**
   * Validates the options for this BuildRequest.
   *
   * <p>Issues warnings for the use of deprecated options, and warnings or errors for any option
   * settings that conflict.
   */
  @VisibleForTesting
  public void validateOptions(BuildRequest request) throws InvalidConfigurationException {
    for (String issue : request.validateOptions()) {
      getReporter().handle(Event.warn(issue));
    }
  }

  /**
   * Takes a set of configured targets, and checks if the distribution methods
   * declared for the targets are compatible with the constraints imposed by
   * their prerequisites' licenses.
   *
   * @param configuredTargets the targets to check
   * @param keepGoing if false, and a licensing error is encountered, both
   *        generates an error message on the reporter, <em>and</em> throws an
   *        exception. If true, then just generates a message on the reporter.
   * @throws ViewCreationFailedException if the license checking failed (and not
   *         --keep_going)
   */
  private void validateLicensingForTargets(Iterable<ConfiguredTarget> configuredTargets,
      boolean keepGoing) throws ViewCreationFailedException {
    for (ConfiguredTarget configuredTarget : configuredTargets) {
      final Target target = configuredTarget.getTarget();

      if (TargetUtils.isTestRule(target)) {
        continue;  // Tests are exempt from license checking
      }

      final Set<DistributionType> distribs = target.getDistributions();
      StaticallyLinkedMarkerProvider markerProvider =
          configuredTarget.getProvider(StaticallyLinkedMarkerProvider.class);
      boolean staticallyLinked = markerProvider != null && markerProvider.isLinkedStatically();

      LicensesProvider provider = configuredTarget.getProvider(LicensesProvider.class);
      if (provider != null) {
        NestedSet<TargetLicense> licenses = provider.getTransitiveLicenses();
        for (TargetLicense targetLicense : licenses) {
          if (!targetLicense.getLicense().checkCompatibility(
              distribs, target, targetLicense.getLabel(), getReporter(), staticallyLinked)) {
            if (!keepGoing) {
              throw new ViewCreationFailedException("Build aborted due to licensing error");
            }
          }
        }
      } else if (configuredTarget.getTarget() instanceof InputFile) {
        // Input file targets do not provide licenses because they do not
        // depend on the rule where their license is taken from. This is usually
        // not a problem, because the transitive collection of licenses always
        // hits the rule they come from, except when the input file is a
        // top-level target. Thus, we need to handle that case specially here.
        //
        // See FileTarget#getLicense for more information about the handling of
        // license issues with File targets.
        License license = configuredTarget.getTarget().getLicense();
        if (!license.checkCompatibility(distribs, target, configuredTarget.getLabel(),
            getReporter(), staticallyLinked)) {
          if (!keepGoing) {
            throw new ViewCreationFailedException("Build aborted due to licensing error");
          }
        }
      }
    }
  }

  private Reporter getReporter() {
    return env.getReporter();
  }
}
