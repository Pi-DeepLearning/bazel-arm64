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

package com.google.devtools.build.lib.analysis.actions;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.SpawnInfo;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.collect.IterablesChain;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.GeneratedMessage.GeneratedExtension;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/** An Action representing an arbitrary subprocess to be forked and exec'd. */
public class SpawnAction extends AbstractAction implements ExecutionInfoSpecifier, CommandAction {

  /** Sets extensions on ExtraActionInfo **/
  protected static class ExtraActionInfoSupplier<T> {
    private final GeneratedExtension<ExtraActionInfo, T> extension;
    private final T value;

    protected ExtraActionInfoSupplier(GeneratedExtension<ExtraActionInfo, T> extension, T value) {
      this.extension = extension;
      this.value = value;
    }

    void extend(ExtraActionInfo.Builder builder) {
      builder.setExtension(extension, value);
    }
  }

  private static final String GUID = "ebd6fce3-093e-45ee-adb6-bf513b602f0d";

  private final CommandLine argv;

  private final boolean executeUnconditionally;
  private final String progressMessage;
  private final String mnemonic;
  // entries are (directory for remote execution, Artifact)
  private final ImmutableMap<PathFragment, Artifact> inputManifests;

  private final ResourceSet resourceSet;
  private final ImmutableMap<String, String> environment;
  private final ImmutableSet<String> clientEnvironmentVariables;
  private final ImmutableMap<String, String> executionInfo;

  private final ExtraActionInfoSupplier<?> extraActionInfoSupplier;

  /**
   * Constructs a SpawnAction using direct initialization arguments.
   *
   * <p>All collections provided must not be subsequently modified.
   *
   * @param owner the owner of the Action.
   * @param tools the set of files comprising the tool that does the work (e.g. compiler).
   * @param inputs the set of all files potentially read by this action; must not be subsequently
   *     modified.
   * @param outputs the set of all files written by this action; must not be subsequently modified.
   * @param resourceSet the resources consumed by executing this Action
   * @param environment the map of environment variables.
   * @param clientEnvironmentVariables the set of variables to be inherited from the client
   *     environment.
   * @param argv the command line to execute. This is merely a list of options to the executable,
   *     and is uninterpreted by the build tool for the purposes of dependency checking; typically
   *     it may include the names of input and output files, but this is not necessary.
   * @param progressMessage the message printed during the progression of the build
   * @param mnemonic the mnemonic that is reported in the master log.
   */
  public SpawnAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs,
      ResourceSet resourceSet,
      CommandLine argv,
      Map<String, String> environment,
      Set<String> clientEnvironmentVariables,
      String progressMessage,
      String mnemonic) {
    this(
        owner,
        tools,
        inputs,
        outputs,
        resourceSet,
        argv,
        ImmutableMap.copyOf(environment),
        ImmutableSet.copyOf(clientEnvironmentVariables),
        ImmutableMap.<String, String>of(),
        progressMessage,
        ImmutableMap.<PathFragment, Artifact>of(),
        mnemonic,
        false,
        null);
  }

  /**
   * Constructs a SpawnAction using direct initialization arguments.
   *
   * <p>All collections provided must not be subsequently modified.
   *
   * @param owner the owner of the Action.
   * @param tools the set of files comprising the tool that does the work (e.g. compiler). This is a
   *     subset of "inputs" and is only used by the WorkerSpawnStrategy.
   * @param inputs the set of all files potentially read by this action; must not be subsequently
   *     modified.
   * @param outputs the set of all files written by this action; must not be subsequently modified.
   * @param resourceSet the resources consumed by executing this Action
   * @param environment the map of environment variables.
   * @param clientEnvironmentVariables the set of variables to be inherited from the client
   *     environment.
   * @param executionInfo out-of-band information for scheduling the spawn.
   * @param argv the argv array (including argv[0]) of arguments to pass. This is merely a list of
   *     options to the executable, and is uninterpreted by the build tool for the purposes of
   *     dependency checking; typically it may include the names of input and output files, but this
   *     is not necessary.
   * @param progressMessage the message printed during the progression of the build
   * @param inputManifests entries in inputs that are symlink manifest files. These are passed to
   *     remote execution in the environment rather than as inputs.
   * @param mnemonic the mnemonic that is reported in the master log.
   */
  public SpawnAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs,
      ResourceSet resourceSet,
      CommandLine argv,
      ImmutableMap<String, String> environment,
      ImmutableSet<String> clientEnvironmentVariables,
      ImmutableMap<String, String> executionInfo,
      String progressMessage,
      ImmutableMap<PathFragment, Artifact> inputManifests,
      String mnemonic,
      boolean executeUnconditionally,
      ExtraActionInfoSupplier<?> extraActionInfoSupplier) {
    super(owner, tools, inputs, outputs);
    this.resourceSet = resourceSet;
    this.executionInfo = executionInfo;
    this.environment = environment;
    this.clientEnvironmentVariables = clientEnvironmentVariables;
    this.argv = argv;
    this.progressMessage = progressMessage;
    this.inputManifests = inputManifests;
    this.mnemonic = mnemonic;
    this.executeUnconditionally = executeUnconditionally;
    this.extraActionInfoSupplier = extraActionInfoSupplier;
  }

  @Override
  @VisibleForTesting
  public List<String> getArguments() {
    return ImmutableList.copyOf(argv.arguments());
  }

  @Override
  public SkylarkList<String> getSkylarkArgv() {
    return SkylarkList.createImmutable(getArguments());
  }

  /**
   * Returns the list of options written to the parameter file. Don't use this method outside tests.
   * The list is often huge, resulting in significant garbage collection overhead.
   */
  @VisibleForTesting
  public List<String> getArgumentsFromParamFile() {
    if (argv.parameterFileWriteAction() != null) {
      return ImmutableList.copyOf(argv.parameterFileWriteAction().getContents());
    }
    return ImmutableList.of();
  }

  /** Returns command argument, argv[0]. */
  @VisibleForTesting
  public String getCommandFilename() {
    return Iterables.getFirst(argv.arguments(), null);
  }

  /**
   * Returns the (immutable) list of arguments, excluding the command name,
   * argv[0].
   */
  @VisibleForTesting
  public List<String> getRemainingArguments() {
    return ImmutableList.copyOf(Iterables.skip(argv.arguments(), 1));
  }

  @VisibleForTesting
  public boolean isShellCommand() {
    return argv.isShellCommand();
  }

  @Override
  public boolean isVolatile() {
    return executeUnconditionally;
  }

  @Override
  public boolean executeUnconditionally() {
    return executeUnconditionally;
  }

  /**
   * Executes the action without handling ExecException errors.
   *
   * <p>Called by {@link #execute}.
   */
  protected void internalExecute(
      ActionExecutionContext actionExecutionContext) throws ExecException, InterruptedException {
    getContext(actionExecutionContext.getExecutor())
        .exec(getSpawn(actionExecutionContext.getClientEnv()), actionExecutionContext);
  }

  @Override
  public void execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    Executor executor = actionExecutionContext.getExecutor();
    try {
      internalExecute(actionExecutionContext);
    } catch (ExecException e) {
      String failMessage = progressMessage;
      if (isShellCommand()) {
        // The possible reasons it could fail are: shell executable not found, shell
        // exited non-zero, or shell died from signal.  The first is impossible
        // and the second two aren't very interesting, so in the interests of
        // keeping the noise-level down, we don't print a reason why, just the
        // command that failed.
        //
        // 0=shell executable, 1=shell command switch, 2=command
        failMessage = "error executing shell command: " + "'"
            + truncate(Iterables.get(argv.arguments(), 2), 200) + "'";
      }
      throw e.toActionExecutionException(failMessage, executor.getVerboseFailures(), this);
    }
  }

  public ImmutableMap<PathFragment, Artifact> getInputManifests() {
    return inputManifests;
  }

  /**
   * Returns s, truncated to no more than maxLen characters, appending an
   * ellipsis if truncation occurred.
   */
  private static String truncate(String s, int maxLen) {
    return s.length() > maxLen
        ? s.substring(0, maxLen - "...".length()) + "..."
        : s;
  }

  /**
   * Returns a Spawn that is representative of the command that this Action
   * will execute. This function must not modify any state.
   *
   * This method is final, as it is merely a shorthand use of the generic way to obtain a spawn,
   * which also depends on the client environment. Subclasses that which to override the way to get
   * a spawn should override {@link getSpawn(Map<String, String>)} instead.
   */
  public final Spawn getSpawn() {
    return getSpawn(null);
  }

  /**
   * Return a spawn that is representative of the command that this Action will execute in the given
   * client environment.
   */
  public Spawn getSpawn(Map<String, String> clientEnv) {
    return new ActionSpawn(clientEnv);
  }

  @Override
  protected String computeKey() {
    Fingerprint f = new Fingerprint();
    f.addString(GUID);
    f.addStrings(argv.arguments());
    f.addString(getMnemonic());
    // We don't need the toolManifests here, because they are a subset of the inputManifests by
    // definition and the output of an action shouldn't change whether something is considered a
    // tool or not.
    f.addInt(inputManifests.size());
    for (Map.Entry<PathFragment, Artifact> input : inputManifests.entrySet()) {
      f.addString(input.getKey().getPathString() + "/");
      f.addPath(input.getValue().getExecPath());
    }
    f.addStringMap(getEnvironment());
    f.addStrings(getClientEnvironmentVariables());
    f.addStringMap(getExecutionInfo());
    return f.hexDigestAndReset();
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    for (Map.Entry<String, String> entry : getEnvironment().entrySet()) {
      message.append("  Environment variable: ");
      message.append(ShellEscaper.escapeString(entry.getKey()));
      message.append('=');
      message.append(ShellEscaper.escapeString(entry.getValue()));
      message.append('\n');
    }
    for (String var : getClientEnvironmentVariables()) {
      message.append("  Environment variables taken from the client environment: ");
      message.append(ShellEscaper.escapeString(var));
      message.append('\n');
    }
    for (String argument : ShellEscaper.escapeAll(argv.arguments())) {
      message.append("  Argument: ");
      message.append(argument);
      message.append('\n');
    }
    return message.toString();
  }

  @Override
  public final String getMnemonic() {
    return mnemonic;
  }

  @Override
  protected String getRawProgressMessage() {
    if (progressMessage != null) {
      return progressMessage;
    }
    return super.getRawProgressMessage();
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo() {
    ExtraActionInfo.Builder builder = super.getExtraActionInfo();
    if (extraActionInfoSupplier == null) {
      Spawn spawn = getSpawn();
      SpawnInfo spawnInfo = spawn.getExtraActionInfo();

      return builder
          .setExtension(SpawnInfo.spawnInfo, spawnInfo);
    } else {
      extraActionInfoSupplier.extend(builder);
      return builder;
    }
  }

  @Override
  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  @Override
  public Iterable<String> getClientEnvironmentVariables() {
    return clientEnvironmentVariables;
  }

  /**
   * Returns the out-of-band execution data for this action.
   */
  @Override
  public Map<String, String> getExecutionInfo() {
    return executionInfo;
  }

  protected SpawnActionContext getContext(Executor executor) {
    return executor.getSpawnActionContext(getMnemonic());
  }

  @Override
  public ResourceSet estimateResourceConsumption(Executor executor) {
    SpawnActionContext context = getContext(executor);
    if (context.willExecuteRemotely(!executionInfo.containsKey("local"))) {
      return ResourceSet.ZERO;
    }
    return resourceSet;
  }

  /**
   * A spawn instance that is tied to a specific SpawnAction.
   */
  public class ActionSpawn extends BaseSpawn {

    private final List<Artifact> filesets = new ArrayList<>();

    private final ImmutableMap<String, String> effectiveEnvironment;

    /**
     * Creates an ActionSpawn with the given environment variables.
     *
     * <p>Subclasses of ActionSpawn may subclass in order to provide action-specific values for
     * environment variables or action inputs.
     */
    protected ActionSpawn(Map<String, String> clientEnv) {
      super(ImmutableList.copyOf(argv.arguments()),
          ImmutableMap.<String, String>of(),
          executionInfo,
          inputManifests,
          SpawnAction.this,
          resourceSet);
      for (Artifact input : getInputs()) {
        if (input.isFileset()) {
          filesets.add(input);
        }
      }
      LinkedHashMap<String, String> env = new LinkedHashMap<>(SpawnAction.this.getEnvironment());
      if (clientEnv != null) {
        for (String var : SpawnAction.this.getClientEnvironmentVariables()) {
          String value = clientEnv.get(var);
          if (value == null) {
            env.remove(var);
          } else {
            env.put(var, value);
          }
        }
      }
      effectiveEnvironment = ImmutableMap.copyOf(env);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment() {
      return effectiveEnvironment;
    }

    @Override
    public ImmutableList<Artifact> getFilesetManifests() {
      return ImmutableList.copyOf(filesets);
    }

    @Override
    public Iterable<? extends ActionInput> getInputFiles() {
      // Remove Fileset directories in inputs list. Instead, these are
      // included as manifests in getEnvironment().
      List<Artifact> inputs = Lists.newArrayList(getInputs());
      inputs.removeAll(filesets);
      inputs.removeAll(inputManifests.values());
      return inputs;
    }
  }

  /**
   * Builder class to construct {@link SpawnAction} instances.
   */
  public static class Builder {

    private final NestedSetBuilder<Artifact> toolsBuilder = NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    private final List<Artifact> outputs = new ArrayList<>();
    private final Map<PathFragment, Artifact> toolManifests = new LinkedHashMap<>();
    private final Map<PathFragment, Artifact> inputManifests = new LinkedHashMap<>();
    private ResourceSet resourceSet = AbstractAction.DEFAULT_RESOURCE_SET;
    private ImmutableMap<String, String> environment = ImmutableMap.of();
    private ImmutableSet<String> clientEnvironmentVariables = ImmutableSet.of();
    private ImmutableMap<String, String> executionInfo = ImmutableMap.of();
    private boolean isShellCommand = false;
    private boolean useDefaultShellEnvironment = false;
    protected boolean executeUnconditionally;
    private PathFragment executable;
    // executableArgs does not include the executable itself.
    private List<String> executableArgs;
    private final IterablesChain.Builder<String> argumentsBuilder = IterablesChain.builder();
    private CommandLine commandLine;

    private String progressMessage;
    private ParamFileInfo paramFileInfo = null;
    private String mnemonic = "Unknown";
    protected ExtraActionInfoSupplier<?> extraActionInfoSupplier = null;
    private boolean disableSandboxing = false;

    /**
     * Creates a SpawnAction builder.
     */
    public Builder() {}

    /**
     * Creates a builder that is a copy of another builder.
     */
    public Builder(Builder other) {
      this.toolsBuilder.addTransitive(other.toolsBuilder.build());
      this.inputsBuilder.addTransitive(other.inputsBuilder.build());
      this.outputs.addAll(other.outputs);
      this.toolManifests.putAll(other.toolManifests);
      this.inputManifests.putAll(other.inputManifests);
      this.resourceSet = other.resourceSet;
      this.environment = other.environment;
      this.clientEnvironmentVariables = other.clientEnvironmentVariables;
      this.executionInfo = other.executionInfo;
      this.isShellCommand = other.isShellCommand;
      this.useDefaultShellEnvironment = other.useDefaultShellEnvironment;
      this.executable = other.executable;
      this.executableArgs = (other.executableArgs != null)
          ? Lists.newArrayList(other.executableArgs)
          : null;
      this.argumentsBuilder.add(other.argumentsBuilder.build());
      this.commandLine = other.commandLine;
      this.progressMessage = other.progressMessage;
      this.paramFileInfo = other.paramFileInfo;
      this.mnemonic = other.mnemonic;
    }

    /**
     * Builds the SpawnAction and ParameterFileWriteAction (if param file is used) using the passed-
     * in action configuration. The first item of the returned array is always the SpawnAction
     * itself.
     *
     * <p>This method makes a copy of all the collections, so it is safe to reuse the builder after
     * this method returns.
     *
     * <p>This is annotated with @CheckReturnValue, which causes a compiler error when you call this
     * method and ignore its return value. This is because some time ago, calling .build() had the
     * side-effect of registering it with the RuleContext that was passed in to the constructor.
     * This logic was removed, but if people don't notice and still rely on the side-effect, things
     * may break.
     *
     * @return the SpawnAction and any actions required by it, with the first item always being the
     *      SpawnAction itself.
     */
    @CheckReturnValue
    public Action[] build(ActionConstructionContext context) {
      return build(context.getActionOwner(), context.getAnalysisEnvironment(),
          context.getConfiguration());
    }

    @VisibleForTesting @CheckReturnValue
    public Action[] build(ActionOwner owner, AnalysisEnvironment analysisEnvironment,
        BuildConfiguration configuration) {
      Iterable<String> arguments = argumentsBuilder.build();
      // Check to see if we need to use param file.
      Artifact paramsFile = ParamFileHelper.getParamsFileMaybe(
          buildExecutableArgs(configuration.getShellExecutable()),
          arguments,
          commandLine,
          paramFileInfo,
          configuration,
          analysisEnvironment,
          outputs);

      // If param file is to be used, set up the param file write action as well.
      ParameterFileWriteAction paramFileWriteAction = null;
      if (paramsFile != null) {
        paramFileWriteAction =
            ParamFileHelper.createParameterFileWriteAction(
                arguments, commandLine, owner, paramsFile, paramFileInfo);
      }

      List<Action> actions = new ArrayList<>(2);
      actions.add(
          buildSpawnAction(
              owner,
              configuration.getLocalShellEnvironment(),
              configuration.getVariableShellEnvironment(),
              configuration.getShellExecutable(),
              paramsFile,
              paramFileWriteAction));
      if (paramFileWriteAction != null) {
        actions.add(paramFileWriteAction);
      }

      return actions.toArray(new Action[actions.size()]);
    }

    /**
     * Builds the SpawnAction using the passed-in action configuration.
     *
     * <p>This method makes a copy of all the collections, so it is safe to reuse the builder after
     * this method returns.
     *
     * <p>This method is invoked by {@link SpawnActionTemplate} in the execution phase. It is
     * important that analysis-phase objects (RuleContext, Configuration, etc.) are not directly
     * referenced in this function to prevent them from bleeding into the execution phase.
     *
     * @param owner the {@link ActionOwner} for the SpawnAction
     * @param defaultShellEnvironment the default shell environment to use. May be null if not used.
     * @param defaultShellExecutable the default shell executable path. May be null if not used.
     * @param paramsFile the parameter file for the SpawnAction. May be null if not used.
     * @param paramFileWriteAction the action generating the parameter file. May be null if not
     *     used.
     * @return the SpawnAction and any actions required by it, with the first item always being the
     *     SpawnAction itself.
     */
    SpawnAction buildSpawnAction(
        ActionOwner owner,
        @Nullable Map<String, String> defaultShellEnvironment,
        @Nullable Set<String> variableShellEnvironment,
        @Nullable PathFragment defaultShellExecutable,
        @Nullable Artifact paramsFile,
        @Nullable ParameterFileWriteAction paramFileWriteAction) {
      List<String> argv = buildExecutableArgs(defaultShellExecutable);
      Iterable<String> arguments = argumentsBuilder.build();
      CommandLine actualCommandLine;
      if (paramsFile != null) {
        inputsBuilder.add(paramsFile);
        actualCommandLine =
            ParamFileHelper.createWithParamsFile(
                argv, isShellCommand, paramFileInfo, paramsFile, paramFileWriteAction);
      } else {
        actualCommandLine = ParamFileHelper.createWithoutParamsFile(argv, arguments, commandLine,
            isShellCommand);
      }

      NestedSet<Artifact> tools = toolsBuilder.build();

      // Tools are by definition a subset of the inputs, so make sure they're present there, too.
      NestedSet<Artifact> inputsAndTools =
          NestedSetBuilder.<Artifact>stableOrder()
              .addTransitive(inputsBuilder.build())
              .addTransitive(tools)
              .build();

      LinkedHashMap<PathFragment, Artifact> inputAndToolManifests =
          new LinkedHashMap<>(inputManifests);
      inputAndToolManifests.putAll(toolManifests);

      Map<String, String> env;
      Set<String> clientEnv;
      if (useDefaultShellEnvironment) {
        env = Preconditions.checkNotNull(defaultShellEnvironment);
        clientEnv = Preconditions.checkNotNull(variableShellEnvironment);
      } else {
        env = this.environment;
        clientEnv = this.clientEnvironmentVariables;
      }

      if (disableSandboxing) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        builder.putAll(executionInfo);
        builder.put("nosandbox", "1");
        executionInfo = builder.build();
      }

      return createSpawnAction(
          owner,
          tools,
          inputsAndTools,
          ImmutableList.copyOf(outputs),
          resourceSet,
          actualCommandLine,
          ImmutableMap.copyOf(env),
          ImmutableSet.copyOf(clientEnv),
          ImmutableMap.copyOf(executionInfo),
          progressMessage,
          ImmutableMap.copyOf(inputAndToolManifests),
          mnemonic);
    }

    /** Creates a SpawnAction. */
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableList<Artifact> outputs,
        ResourceSet resourceSet,
        CommandLine actualCommandLine,
        ImmutableMap<String, String> env,
        ImmutableSet<String> clientEnvironmentVariables,
        ImmutableMap<String, String> executionInfo,
        String progressMessage,
        ImmutableMap<PathFragment, Artifact> inputAndToolManifests,
        String mnemonic) {
      return new SpawnAction(
          owner,
          tools,
          inputsAndTools,
          outputs,
          resourceSet,
          actualCommandLine,
          env,
          clientEnvironmentVariables,
          executionInfo,
          progressMessage,
          inputAndToolManifests,
          mnemonic,
          executeUnconditionally,
          extraActionInfoSupplier);
    }

    private List<String> buildExecutableArgs(@Nullable PathFragment defaultShellExecutable) {
      if (isShellCommand && executable == null) {
        Preconditions.checkNotNull(defaultShellExecutable);
        executable = defaultShellExecutable;
      }
      Preconditions.checkNotNull(executable);
      Preconditions.checkNotNull(executableArgs);

      return ImmutableList.<String>builder()
          .add(executable.getPathString())
          .addAll(executableArgs)
          .build();
    }

    /**
     * Adds an artifact that is necessary for executing the spawn itself (e.g. a compiler), in
     * contrast to an artifact that is necessary for the spawn to do its work (e.g. source code).
     *
     * <p>The artifact is implicitly added to the inputs of the action as well.
     */
    public Builder addTool(Artifact tool) {
      toolsBuilder.add(tool);
      return this;
    }

    /**
     * Adds an input to this action.
     */
    public Builder addInput(Artifact artifact) {
      inputsBuilder.add(artifact);
      return this;
    }

    /**
     * Adds tools to this action.
     */
    public Builder addTools(Iterable<Artifact> artifacts) {
      toolsBuilder.addAll(artifacts);
      return this;
    }

    /**
     * Adds inputs to this action.
     */
    public Builder addInputs(Iterable<Artifact> artifacts) {
      inputsBuilder.addAll(artifacts);
      return this;
    }

    /** @deprecated Use {@link #addTransitiveInputs} to avoid excessive memory use. */
    @Deprecated
    public Builder addInputs(NestedSet<Artifact> artifacts) {
      // Do not delete this method, or else addInputs(Iterable) calls with a NestedSet argument
      // will not be flagged.
      inputsBuilder.addAll((Iterable<Artifact>) artifacts);
      return this;
    }

    /**
     * Adds transitive inputs to this action.
     */
    public Builder addTransitiveInputs(NestedSet<Artifact> artifacts) {
      inputsBuilder.addTransitive(artifacts);
      return this;
    }

    private Builder addToolManifest(Artifact artifact, PathFragment remote) {
      toolManifests.put(remote, artifact);
      return this;
    }

    public Builder addInputManifest(Artifact artifact, PathFragment remote) {
      inputManifests.put(remote, artifact);
      return this;
    }

    public Builder addOutput(Artifact artifact) {
      outputs.add(artifact);
      return this;
    }

    public Builder addOutputs(Iterable<Artifact> artifacts) {
      Iterables.addAll(outputs, artifacts);
      return this;
    }

    /**
     * Checks whether the action produces any outputs
     */
    public boolean hasOutputs() {
      return !outputs.isEmpty();
    }

    public Builder setResources(ResourceSet resourceSet) {
      this.resourceSet = resourceSet;
      return this;
    }

    /**
     * Sets the map of environment variables.
     */
    public Builder setEnvironment(Map<String, String> environment) {
      this.environment = ImmutableMap.copyOf(environment);
      this.useDefaultShellEnvironment = false;
      return this;
    }

    /** Sets the environment variables to be inherited from the client environment. */
    public Builder setClientEnvironmentVariables(Set<String> clientEnvironmentVariables) {
      this.clientEnvironmentVariables = ImmutableSet.copyOf(clientEnvironmentVariables);
      this.useDefaultShellEnvironment = false;
      return this;
    }

    /**
     * Sets the map of execution info.
     */
    public Builder setExecutionInfo(Map<String, String> info) {
      this.executionInfo = ImmutableMap.copyOf(info);
      return this;
    }

    /**
     * Sets the environment to the configurations default shell environment,
     * see {@link BuildConfiguration#getLocalShellEnvironment}.
     */
    public Builder useDefaultShellEnvironment() {
      this.environment = null;
      this.clientEnvironmentVariables = null;
      this.useDefaultShellEnvironment  = true;
      return this;
    }

    /**
     * Makes the action always execute, even if none of its inputs have changed.
     *
     * <p>Only use this when absolutely necessary, since this is a performance hit and we'd like to
     * get rid of this mechanism eventually. You'll eventually be able to declare a Skyframe
     * dependency on the build ID, which would accomplish the same thing.
     */
    public Builder executeUnconditionally() {
      // This should really be implemented by declaring a Skyframe dependency on the build ID
      // instead, however, we can't just do that yet from within actions, so we need to go through
      // Action.executeUnconditionally() which in turn is called by ActionCacheChecker.
      this.executeUnconditionally = true;
      return this;
    }

    /**
     * Sets the executable path; the path is interpreted relative to the
     * execution root.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(PathFragment executable) {
      this.executable = executable;
      this.executableArgs = Lists.newArrayList();
      this.isShellCommand = false;
      return this;
    }

    /**
     * Sets the executable as an artifact.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(Artifact executable) {
      addTool(executable);
      return setExecutable(executable.getExecPath());
    }

    /**
     * Sets the executable as a configured target. Automatically adds the files to run to the tools
     * and inputs and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(TransitiveInfoCollection executable) {
      FilesToRunProvider provider = executable.getProvider(FilesToRunProvider.class);
      Preconditions.checkArgument(provider != null);
      return setExecutable(provider);
    }

    /**
     * Sets the executable as a configured target. Automatically adds the files to run to the tools
     * and inputs and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable},
     * {@link #setJavaExecutable}, or {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(FilesToRunProvider executableProvider) {
      Preconditions.checkArgument(executableProvider.getExecutable() != null,
          "The target does not have an executable");
      setExecutable(executableProvider.getExecutable().getExecPath());
      return addTool(executableProvider);
    }

    private Builder setJavaExecutable(PathFragment javaExecutable, Artifact deployJar,
        List<String> jvmArgs, String... launchArgs) {
      this.executable = javaExecutable;
      this.executableArgs = Lists.newArrayList();
      executableArgs.add("-Xverify:none");
      executableArgs.addAll(jvmArgs);
      Collections.addAll(executableArgs, launchArgs);
      toolsBuilder.add(deployJar);
      this.isShellCommand = false;
      return this;
    }

    /**
     * Sets the executable to be a java class executed from the given deploy
     * jar. The deploy jar is automatically added to the action inputs.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setJavaExecutable(PathFragment javaExecutable,
        Artifact deployJar, String javaMainClass, List<String> jvmArgs) {
      return setJavaExecutable(javaExecutable, deployJar, jvmArgs, "-cp",
          deployJar.getExecPathString(), javaMainClass);
    }

    /**
     * Sets the executable to be a jar executed from the given deploy jar. The deploy jar is
     * automatically added to the action inputs.
     *
     * <p>This method is similar to {@link #setJavaExecutable} but it assumes that the Jar artifact
     * declares a main class.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable},
     * {@link #setJavaExecutable}, or {@link #setShellCommand(String)}.
     */
    public Builder setJarExecutable(PathFragment javaExecutable,
        Artifact deployJar, List<String> jvmArgs) {
      return setJavaExecutable(javaExecutable, deployJar, jvmArgs, "-jar",
          deployJar.getExecPathString());
    }

    /**
     * Sets the executable to be the shell and adds the given command as the
     * command to be executed.
     *
     * <p>Note that this will not clear the arguments, so any arguments will
     * be passed in addition to the command given here.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setShellCommand(String command) {
      this.executable = null;
      // 0=shell command switch, 1=command
      this.executableArgs = Lists.newArrayList("-c", command);
      this.isShellCommand = true;
      return this;
    }

    /**
     * Sets the executable to be the shell and adds the given interned commands as the
     * commands to be executed.
     */
    public Builder setShellCommand(Iterable<String> command) {
      this.executable = new PathFragment(Iterables.getFirst(command, null));
      // The first item of the commands is the shell executable that should be used.
      this.executableArgs = ImmutableList.copyOf(Iterables.skip(command, 1));
      this.isShellCommand = true;
      return this;
    }

    /**
     * Adds an executable and its runfiles, which is necessary for executing the spawn itself (e.g.
     * a compiler), in contrast to artifacts that are necessary for the spawn to do its work (e.g.
     * source code).
     */
    public Builder addTool(FilesToRunProvider tool) {
      addTools(tool.getFilesToRun());
      if (tool.getRunfilesManifest() != null) {
        addToolManifest(
            tool.getRunfilesManifest(),
            BaseSpawn.runfilesForFragment(tool.getExecutable().getExecPath()));
      }
      return this;
    }

    /**
     * Appends the arguments to the list of executable arguments.
     */
    public Builder addExecutableArguments(String... arguments) {
      Preconditions.checkState(executableArgs != null);
      Collections.addAll(executableArgs, arguments);
      return this;
    }

    /**
     * Add multiple arguments in the order they are returned by the collection
     * to the list of executable arguments.
     */
    public Builder addExecutableArguments(Iterable<String> arguments) {
      Preconditions.checkState(executableArgs != null);
      Iterables.addAll(executableArgs, arguments);
      return this;
    }

    /**
     * Appends the argument to the list of command-line arguments.
     */
    public Builder addArgument(String argument) {
      Preconditions.checkState(commandLine == null);
      argumentsBuilder.addElement(argument);
      return this;
    }

    /**
     * Appends the arguments to the list of command-line arguments.
     */
    public Builder addArguments(String... arguments) {
      Preconditions.checkState(commandLine == null);
      argumentsBuilder.add(ImmutableList.copyOf(arguments));
      return this;
    }

    /**
     * Add multiple arguments in the order they are returned by the collection.
     */
    public Builder addArguments(Iterable<String> arguments) {
      Preconditions.checkState(commandLine == null);
      argumentsBuilder.add(CollectionUtils.makeImmutable(arguments));
      return this;
    }

    /**
     * Appends the argument both to the inputs and to the list of command-line
     * arguments.
     */
    public Builder addInputArgument(Artifact argument) {
      Preconditions.checkState(commandLine == null);
      addInput(argument);
      addArgument(argument.getExecPathString());
      return this;
    }

    /**
     * Appends the arguments both to the inputs and to the list of command-line
     * arguments.
     */
    public Builder addInputArguments(Iterable<Artifact> arguments) {
      for (Artifact argument : arguments) {
        addInputArgument(argument);
      }
      return this;
    }

    /**
     * Appends the argument both to the outputs and to the list of command-line
     * arguments.
     */
    public Builder addOutputArgument(Artifact argument) {
      Preconditions.checkState(commandLine == null);
      outputs.add(argument);
      argumentsBuilder.addElement(argument.getExecPathString());
      return this;
    }

    /**
     * Sets a delegate to compute the command line at a later time. This method
     * cannot be used in conjunction with the {@link #addArgument} or {@link
     * #addArguments} methods.
     *
     * <p>The main intention of this method is to save memory by allowing
     * client-controlled sharing between actions and configured targets.
     * Objects passed to this method MUST be immutable.
     */
    public Builder setCommandLine(CommandLine commandLine) {
      Preconditions.checkState(argumentsBuilder.isEmpty());
      this.commandLine = commandLine;
      return this;
    }

    public Builder setProgressMessage(String progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    public Builder setMnemonic(String mnemonic) {
      Preconditions.checkArgument(
          !mnemonic.isEmpty() && CharMatcher.javaLetterOrDigit().matchesAllOf(mnemonic),
          "mnemonic must only contain letters and/or digits, and have non-zero length, was: \"%s\"",
          mnemonic);
      this.mnemonic = mnemonic;
      return this;
    }

    public <T> Builder setExtraActionInfo(
        GeneratedExtension<ExtraActionInfo, T> extension, T value) {
      this.extraActionInfoSupplier = new ExtraActionInfoSupplier<>(extension, value);
      return this;
    }

    /**
     * Enable use of a parameter file and set the encoding to ISO-8859-1 (latin1).
     *
     * <p>In order to use parameter files, at least one output artifact must be specified.
     */
    public Builder useParameterFile(ParameterFileType parameterFileType) {
      return useParameterFile(parameterFileType, ISO_8859_1, "@");
    }

    /**
     * Force the use of a parameter file and set the encoding to ISO-8859-1 (latin1).
     *
     * <p>In order to use parameter files, at least one output artifact must be specified.
     */
    public Builder alwaysUseParameterFile(ParameterFileType parameterFileType) {
      return useParameterFile(parameterFileType, ISO_8859_1, "@", /*always=*/ true);
    }

    /**
     * Enable or disable the use of a parameter file, set the encoding to the given value, and
     * specify the argument prefix to use in passing the parameter file name to the tool.
     *
     * <p>The default argument prefix is "@". In order to use parameter files, at least one output
     * artifact must be specified.
     */
    public Builder useParameterFile(
        ParameterFileType parameterFileType, Charset charset, String flagPrefix) {
      return useParameterFile(parameterFileType, charset, flagPrefix, /*always=*/ false);
    }

    private Builder useParameterFile(
        ParameterFileType parameterFileType, Charset charset, String flagPrefix, boolean always) {
      paramFileInfo = new ParamFileInfo(parameterFileType, charset, flagPrefix, always);
      return this;
    }

    public Builder disableSandboxing() {
      this.disableSandboxing = true;
      return this;
    }
  }
}
