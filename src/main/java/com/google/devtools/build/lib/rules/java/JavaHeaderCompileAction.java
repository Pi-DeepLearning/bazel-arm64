// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.util.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CommandLine;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Action for Java header compilation, to be used if --java_header_compilation is enabled.
 *
 * <p>The header compiler consumes the inputs of a java compilation, and produces an interface jar
 * that can be used as a compile-time jar by upstream targets. The header interface jar is
 * equivalent to the output of ijar, but unlike ijar the header compiler operates directly on Java
 * source files instead post-processing the class outputs of the compilation. Compiling the
 * interface jar from source moves javac off the build's critical path.
 *
 * <p>The implementation of the header compiler tool can be found under {@code
 * //src/java_tools/buildjar/java/com/google/devtools/build/java/turbine}.
 */
public class JavaHeaderCompileAction extends SpawnAction {

  private static final String GUID = "952db158-2654-4ced-87e5-4646d50523cf";

  private static final ResourceSet LOCAL_RESOURCES =
      ResourceSet.createWithRamCpuIo(/*memoryMb=*/ 750.0, /*cpuUsage=*/ 0.5, /*ioUsage=*/ 0.0);

  private final Iterable<Artifact> directInputs;
  @Nullable private final CommandLine directCommandLine;

  /** Returns true if the header compilation will use direct dependencies only. */
  @VisibleForTesting
  public boolean useDirectClasspath() {
    return directCommandLine != null;
  }

  /** The command line for a direct classpath compilation, or {@code null} if disabled. */
  @VisibleForTesting
  @Nullable
  public CommandLine directCommandLine() {
    return directCommandLine;
  }

  /**
   * Constructs an action to compile a set of Java source files to a header interface jar.
   *
   * @param owner the action owner, typically a java_* RuleConfiguredTarget
   * @param tools the set of files comprising the tool that creates the header interface jar
   * @param directInputs the set of direct input artifacts of the compile action
   * @param transitiveInputs the set of transitive input artifacts of the compile action
   * @param outputs the outputs of the action
   * @param directCommandLine the direct command line arguments for the java header compiler, or
   *     {@code null} if direct classpaths are disabled
   * @param transitiveCommandLine the transitive command line arguments for the java header compiler
   * @param progressMessage the message printed during the progression of the build
   */
  protected JavaHeaderCompileAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> directInputs,
      Iterable<Artifact> transitiveInputs,
      Iterable<Artifact> outputs,
      CommandLine directCommandLine,
      CommandLine transitiveCommandLine,
      String progressMessage) {
    super(
        owner,
        tools,
        transitiveInputs,
        outputs,
        LOCAL_RESOURCES,
        transitiveCommandLine,
        JavaCompileAction.UTF8_ENVIRONMENT,
        /*executionInfo=*/ ImmutableSet.<String>of(),
        progressMessage,
        "Turbine");
    this.directInputs = checkNotNull(directInputs);
    this.directCommandLine = directCommandLine;
  }

  @Override
  protected String computeKey() {
    Fingerprint fingerprint =
        new Fingerprint()
            .addString(GUID)
            .addString(super.computeKey())
            .addBoolean(useDirectClasspath());
    if (directCommandLine != null) {
      fingerprint.addStrings(directCommandLine.arguments());
    }
    return fingerprint.hexDigestAndReset();
  }

  @Override
  protected void internalExecute(ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    if (!useDirectClasspath()) {
      super.internalExecute(actionExecutionContext);
      return;
    }
    Executor executor = actionExecutionContext.getExecutor();
    SpawnActionContext context = getContext(executor);
    try {
      context.exec(getDirectSpawn(), actionExecutionContext);
    } catch (ExecException e) {
      // if the direct input spawn failed, try again with transitive inputs to produce better
      // better messages
      context.exec(getSpawn(actionExecutionContext.getClientEnv()), actionExecutionContext);
      // the compilation should never fail with direct deps but succeed with transitive inputs
      // TODO(cushon): make this an error before productionizing, we don't ever expect fallback
      String message =
          "header compilation failed unexpectedly: " + Throwables.getStackTraceAsString(e);
      executor.getEventHandler().handle(Event.warn(getOwner().getLocation(), message));
    }
  }

  private final Spawn getDirectSpawn() {
    return new BaseSpawn(
        ImmutableList.copyOf(directCommandLine.arguments()),
        ImmutableMap.<String, String>of() /*environment*/,
        ImmutableMap.<String, String>of() /*executionInfo*/,
        this,
        LOCAL_RESOURCES) {
      @Override
      public Iterable<? extends ActionInput> getInputFiles() {
        return directInputs;
      }
    };
  }

  /** Builder class to construct Java header compilation actions. */
  public static class Builder {

    private final RuleContext ruleContext;

    private Artifact outputJar;
    @Nullable private Artifact outputDepsProto;
    private final Collection<Artifact> sourceFiles = new ArrayList<>();
    private final Collection<Artifact> sourceJars = new ArrayList<>();
    private NestedSet<Artifact> classpathEntries
        = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
    private final List<Artifact> bootclasspathEntries = new ArrayList<>();
    @Nullable private String ruleKind;
    @Nullable private Label targetLabel;
    private PathFragment tempDirectory;
    private BuildConfiguration.StrictDepsMode strictJavaDeps
        = BuildConfiguration.StrictDepsMode.OFF;
    private NestedSet<Artifact> directJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
    private final List<Artifact> compileTimeDependencyArtifacts = new ArrayList<>();
    private ImmutableList<String> javacOpts;
    private final List<Artifact> processorPath = new ArrayList<>();
    private final List<String> processorNames = new ArrayList<>();
    private NestedSet<Artifact> javabaseInputs;
    private Artifact javacJar;

    public Builder(RuleContext ruleContext) {
      this.ruleContext = ruleContext;
    }

    /** Sets the output jdeps file. */
    public Builder setOutputDepsProto(@Nullable Artifact outputDepsProto) {
      this.outputDepsProto = outputDepsProto;
      return this;
    }

    /** Sets the direct dependency artifacts. */
    public Builder setDirectJars(NestedSet<Artifact> directJars) {
      checkNotNull(directJars, "directJars must not be null");
      this.directJars = directJars;
      return this;
    }

    /** Sets the .jdeps artifacts for direct dependencies. */
    public Builder addCompileTimeDependencyArtifacts(
        Collection<Artifact> dependencyArtifacts) {
      checkNotNull(dependencyArtifacts, "dependencyArtifacts must not be null");
      this.compileTimeDependencyArtifacts.addAll(dependencyArtifacts);
      return this;
    }

    /** Sets Java compiler flags. */
    public Builder setJavacOpts(ImmutableList<String> javacOpts) {
      checkNotNull(javacOpts, "javacOpts must not be null");
      this.javacOpts = javacOpts;
      return this;
    }

    /** Sets the output jar. */
    public Builder setOutputJar(Artifact outputJar) {
      checkNotNull(outputJar, "outputJar must not be null");
      this.outputJar = outputJar;
      return this;
    }

    /** Adds a Java source file to compile. */
    public Builder addSourceFile(Artifact sourceFile) {
      checkNotNull(sourceFile, "sourceFile must not be null");
      sourceFiles.add(sourceFile);
      return this;
    }

    /** Adds Java source files to compile. */
    public Builder addSourceFiles(Collection<Artifact> sourceFiles) {
      checkNotNull(sourceFiles, "sourceFiles must not be null");
      this.sourceFiles.addAll(sourceFiles);
      return this;
    }

    /** Adds a jar archive of Java sources to compile. */
    public Builder addSourceJars(Collection<Artifact> sourceJars) {
      checkNotNull(sourceJars, "sourceJars must not be null");
      this.sourceJars.addAll(sourceJars);
      return this;
    }

    /** Sets the compilation classpath entries. */
    public Builder setClasspathEntries(NestedSet<Artifact> classpathEntries) {
      checkNotNull(classpathEntries, "classpathEntries must not be null");
      this.classpathEntries = classpathEntries;
      return this;
    }

    /** Sets the compilation bootclasspath entries. */
    public Builder addAllBootclasspathEntries(
        Collection<Artifact> bootclasspathEntries) {
      checkNotNull(bootclasspathEntries, "bootclasspathEntries must not be null");
      this.bootclasspathEntries.addAll(bootclasspathEntries);
      return this;
    }

    /** Sets the compilation extclasspath entries. */
    public Builder addAllExtClasspathEntries(
        Collection<Artifact> extclasspathEntries) {
      checkNotNull(extclasspathEntries, "extclasspathEntries must not be null");
      // fold extclasspath entries into the bootclasspath; that's what javac ends up doing
      this.bootclasspathEntries.addAll(extclasspathEntries);
      return this;
    }

    /** Sets the annotation processors classpath entries. */
    public Builder addProcessorPaths(Collection<Artifact> processorPaths) {
      checkNotNull(processorPaths, "processorPaths must not be null");
      this.processorPath.addAll(processorPaths);
      return this;
    }

    /** Sets the fully-qualified class names of annotation processors to run. */
    public Builder addProcessorNames(Collection<String> processorNames) {
      checkNotNull(processorNames, "processorNames must not be null");
      this.processorNames.addAll(processorNames);
      return this;
    }

    /** Sets the kind of the build rule being compiled (e.g. {@code java_library}). */
    public Builder setRuleKind(@Nullable String ruleKind) {
      this.ruleKind = ruleKind;
      return this;
    }

    /** Sets the label of the target being compiled. */
    public Builder setTargetLabel(@Nullable Label targetLabel) {
      this.targetLabel = targetLabel;
      return this;
    }

    /**
     * Sets the path to a temporary directory, e.g. for extracting sourcejar entries to before
     * compilation.
     */
    public Builder setTempDirectory(PathFragment tempDirectory) {
      checkNotNull(tempDirectory, "tempDirectory must not be null");
      this.tempDirectory = tempDirectory;
      return this;
    }

    /** Sets the Strict Java Deps mode. */
    public Builder setStrictJavaDeps(BuildConfiguration.StrictDepsMode strictJavaDeps) {
      checkNotNull(strictJavaDeps, "strictJavaDeps must not be null");
      this.strictJavaDeps = strictJavaDeps;
      return this;
    }

    /** Sets the javabase inputs. */
    public Builder setJavaBaseInputs(NestedSet<Artifact> javabaseInputs) {
      checkNotNull(javabaseInputs, "javabaseInputs must not be null");
      this.javabaseInputs = javabaseInputs;
      return this;
    }

    /** Sets the javac jar. */
    public Builder setJavacJar(Artifact javacJar) {
      checkNotNull(javacJar, "javacJar must not be null");
      this.javacJar = javacJar;
      return this;
    }
    /** Builds and registers the {@link JavaHeaderCompileAction} for a header compilation. */
    public void build(JavaToolchainProvider javaToolchain) {
      checkNotNull(outputDepsProto, "outputDepsProto must not be null");
      checkNotNull(sourceFiles, "sourceFiles must not be null");
      checkNotNull(sourceJars, "sourceJars must not be null");
      checkNotNull(classpathEntries, "classpathEntries must not be null");
      checkNotNull(bootclasspathEntries, "bootclasspathEntries must not be null");
      checkNotNull(tempDirectory, "tempDirectory must not be null");
      checkNotNull(strictJavaDeps, "strictJavaDeps must not be null");
      checkNotNull(directJars, "directJars must not be null");
      checkNotNull(
          compileTimeDependencyArtifacts, "compileTimeDependencyArtifacts must not be null");
      checkNotNull(javacOpts, "javacOpts must not be null");
      checkNotNull(processorPath, "processorPath must not be null");
      checkNotNull(processorNames, "processorNames must not be null");

      // Invariant: if strictJavaDeps is OFF, then directJars and
      // dependencyArtifacts are ignored
      if (strictJavaDeps == BuildConfiguration.StrictDepsMode.OFF) {
        directJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER);
        compileTimeDependencyArtifacts.clear();
      }
      boolean useDirectClasspath = useDirectClasspath();
      CommandLine directCommandLine =
          useDirectClasspath
              ? baseCommandLine(getBaseArgs(javaToolchain))
                  .addExecPaths("--classpath", directJars)
                  .build()
              : null;
      CommandLine transitiveParams = transitiveCommandLine();
      PathFragment paramFilePath = ParameterFile.derivePath(outputJar.getRootRelativePath());
      Artifact paramsFile =
          ruleContext
              .getAnalysisEnvironment()
              .getDerivedArtifact(paramFilePath, outputJar.getRoot());
      Action parameterFileWriteAction =
          new ParameterFileWriteAction(
              ruleContext.getActionOwner(),
              paramsFile,
              transitiveParams,
              ParameterFile.ParameterFileType.UNQUOTED,
              ISO_8859_1);
      CommandLine transitiveCommandLine =
          getBaseArgs(javaToolchain).addPaths("@%s", paramsFile.getExecPath()).build();
      Iterable<Artifact> tools = ImmutableList.of(javacJar, javaToolchain.getHeaderCompiler());
      NestedSet<Artifact> directInputs =
          NestedSetBuilder.<Artifact>stableOrder()
              .addTransitive(javabaseInputs)
              .addAll(bootclasspathEntries)
              .addAll(sourceJars)
              .addAll(sourceFiles)
              .addTransitive(directJars)
              .addAll(tools)
              .build();
      NestedSet<Artifact> transitiveInputs =
          NestedSetBuilder.<Artifact>stableOrder()
              .addTransitive(directInputs)
              .addTransitive(classpathEntries)
              .addAll(processorPath)
              .addAll(compileTimeDependencyArtifacts)
              .add(paramsFile)
              .build();
      JavaHeaderCompileAction javaHeaderCompileAction =
          new JavaHeaderCompileAction(
              ruleContext.getActionOwner(),
              tools,
              directInputs,
              transitiveInputs,
              ImmutableList.of(outputJar, outputDepsProto),
              directCommandLine,
              transitiveCommandLine,
              getProgressMessage());
      ruleContext.registerAction(parameterFileWriteAction, javaHeaderCompileAction);
    }

    private String getProgressMessage() {
      return String.format(
          "Compiling Java headers %s (%d files)",
          outputJar.prettyPrint(), sourceFiles.size() + sourceJars.size());
    }

    private CustomCommandLine.Builder getBaseArgs(JavaToolchainProvider javaToolchain) {
      return CustomCommandLine.builder()
          .addPath(ruleContext.getHostConfiguration().getFragment(Jvm.class).getJavaExecutable())
          .add("-Xverify:none")
          .add(javaToolchain.getJvmOptions())
          .addPaths("-Xbootclasspath/p:%s", javacJar.getExecPath())
          .addExecPath("-jar", javaToolchain.getHeaderCompiler());
    }

    /**
     * Adds the command line arguments shared by direct classpath and transitive classpath
     * invocations.
     */
    private CustomCommandLine.Builder baseCommandLine(CustomCommandLine.Builder result) {
      result.addExecPath("--output", outputJar);

      if (outputDepsProto != null) {
        result.addExecPath("--output_deps", outputDepsProto);
      }

      result.add("--temp_dir").addPath(tempDirectory);

      result.addExecPaths("--bootclasspath", bootclasspathEntries);

      result.addExecPaths("--sources", sourceFiles);

      if (!sourceJars.isEmpty()) {
        result.addExecPaths("--source_jars", sourceJars);
      }

      result.add("--javacopts", javacOpts);

      if (ruleKind != null) {
        result.add("--rule_kind");
        result.add(ruleKind);
      }
      if (targetLabel != null) {
        result.add("--target_label");
        if (targetLabel.getPackageIdentifier().getRepository().isDefault()
            || targetLabel.getPackageIdentifier().getRepository().isMain()) {
          result.add(targetLabel.toString());
        } else {
          // @-prefixed strings will be assumed to be params filenames and expanded,
          // so add an extra @ to escape it.
          result.add("@" + targetLabel);
        }
      }
      return result;
    }

    /** Builds a transitive classpath command line. */
    private CommandLine transitiveCommandLine() {
      CustomCommandLine.Builder result = CustomCommandLine.builder();
      baseCommandLine(result);
      if (!processorNames.isEmpty()) {
        result.add("--processors", processorNames);
      }
      if (!processorPath.isEmpty()) {
        result.addExecPaths("--processorpath", processorPath);
      }
      if (strictJavaDeps != BuildConfiguration.StrictDepsMode.OFF) {
        result.add(new JavaCompileAction.JarsToTargetsArgv(classpathEntries, directJars));
        if (!compileTimeDependencyArtifacts.isEmpty()) {
          result.addExecPaths("--deps_artifacts", compileTimeDependencyArtifacts);
        }
      }
      result.addExecPaths("--classpath", classpathEntries);
      return result.build();
    }

    /** Returns true if the header compilation classpath should only include direct deps. */
    boolean useDirectClasspath() {
      if (directJars.isEmpty()) {
        // the compilation doesn't distinguish direct deps, e.g. because it doesn't support strict
        // java deps
        return false;
      }
      if (!processorNames.isEmpty()) {
        // the compilation uses API-generating annotation processors and has to fall back to
        // javac-turbine, which doesn't support direct classpaths
        return false;
      }
      JavaConfiguration javaConfiguration = ruleContext.getFragment(JavaConfiguration.class);
      if (!javaConfiguration.headerCompilationDirectClasspath()) {
        // the experiment is disabled
        return false;
      }
      return true;
    }
  }
}
