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
package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.rules.java.DeployArchiveBuilder.Compression.COMPRESSED;
import static com.google.devtools.build.lib.rules.java.DeployArchiveBuilder.Compression.UNCOMPRESSED;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppHelper;
import com.google.devtools.build.lib.rules.cpp.LinkerInput;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgs.ClasspathType;
import com.google.devtools.build.lib.rules.java.ProguardHelper.ProguardOutput;
import com.google.devtools.build.lib.rules.java.proto.GeneratedExtensionRegistryProvider;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * An implementation of java_binary.
 */
public class JavaBinary implements RuleConfiguredTargetFactory {
  private static final PathFragment CPP_RUNTIMES = new PathFragment("_cpp_runtimes");

  private final JavaSemantics semantics;

  protected JavaBinary(JavaSemantics semantics) {
    this.semantics = semantics;
  }

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException {
    final JavaCommon common = new JavaCommon(ruleContext, semantics);
    DeployArchiveBuilder deployArchiveBuilder =  new DeployArchiveBuilder(semantics, ruleContext);
    Runfiles.Builder runfilesBuilder = new Runfiles.Builder(
        ruleContext.getWorkspaceName(), ruleContext.getConfiguration().legacyExternalRunfiles());
    List<String> jvmFlags = new ArrayList<>();

    JavaTargetAttributes.Builder attributesBuilder = common.initCommon();
    attributesBuilder.addClassPathResources(
        ruleContext.getPrerequisiteArtifacts("classpath_resources", Mode.TARGET).list());

    List<String> userJvmFlags = JavaCommon.getJvmFlags(ruleContext);

    ruleContext.checkSrcsSamePackage(true);
    boolean createExecutable = ruleContext.attributes().get("create_executable", Type.BOOLEAN);

    if (!createExecutable) {
      // TODO(cushon): disallow combining launcher=JDK_LAUNCHER_LABEL with create_executable=0
      // and use isAttributeExplicitlySpecified here
      Label launcherAttribute = ruleContext.attributes().get("launcher", BuildType.LABEL);
      if (launcherAttribute != null && !JavaHelper.isJdkLauncher(ruleContext, launcherAttribute)) {
        ruleContext.ruleError("launcher specified but create_executable is false");
      }
    }

    List<TransitiveInfoCollection> deps =
        // Do not remove <TransitiveInfoCollection>: workaround for Java 7 type inference.
        Lists.<TransitiveInfoCollection>newArrayList(
            common.targetsTreatedAsDeps(ClasspathType.COMPILE_ONLY));
    semantics.checkRule(ruleContext, common);
    semantics.checkForProtoLibraryAndJavaProtoLibraryOnSameProto(ruleContext, common);
    String mainClass = semantics.getMainClass(ruleContext, common.getSrcsArtifacts());
    String originalMainClass = mainClass;
    if (ruleContext.hasErrors()) {
      return null;
    }

    // Collect the transitive dependencies.
    JavaCompilationHelper helper = new JavaCompilationHelper(
        ruleContext, semantics, common.getJavacOpts(), attributesBuilder);
    helper.addLibrariesToAttributes(deps);
    attributesBuilder.addNativeLibraries(
        collectNativeLibraries(common.targetsTreatedAsDeps(ClasspathType.BOTH)));

    // deploy_env is valid for java_binary, but not for java_test.
    if (ruleContext.getRule().isAttrDefined("deploy_env", BuildType.LABEL_LIST)) {
      for (JavaRuntimeClasspathProvider envTarget : ruleContext.getPrerequisites(
               "deploy_env", Mode.TARGET, JavaRuntimeClasspathProvider.class)) {
        attributesBuilder.addExcludedArtifacts(envTarget.getRuntimeClasspath());
      }
    }

    Artifact srcJar = ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_SOURCE_JAR);
    JavaSourceJarsProvider.Builder javaSourceJarsProviderBuilder = JavaSourceJarsProvider.builder()
        .addSourceJar(srcJar)
        .addAllTransitiveSourceJars(common.collectTransitiveSourceJars(srcJar));
    Artifact classJar = ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_CLASS_JAR);
    JavaRuleOutputJarsProvider.Builder ruleOutputJarsProviderBuilder =
        JavaRuleOutputJarsProvider.builder().addOutputJar(classJar, null /* iJar */, srcJar);

    CppConfiguration cppConfiguration = ruleContext.getConfiguration().getFragment(
        CppConfiguration.class);
    boolean stripAsDefault = cppConfiguration.useFission()
        && cppConfiguration.getCompilationMode() == CompilationMode.OPT;
    Artifact launcher = semantics.getLauncher(ruleContext, common, deployArchiveBuilder,
        runfilesBuilder, jvmFlags, attributesBuilder, stripAsDefault);

    DeployArchiveBuilder unstrippedDeployArchiveBuilder = null;
    Artifact unstrippedLauncher = null;
    if (stripAsDefault) {
      unstrippedDeployArchiveBuilder = new DeployArchiveBuilder(semantics, ruleContext);
      unstrippedLauncher = semantics.getLauncher(ruleContext, common,
          unstrippedDeployArchiveBuilder, runfilesBuilder, jvmFlags, attributesBuilder,
          false  /* shouldStrip */);
    }

    JavaCompilationArtifacts.Builder javaArtifactsBuilder = new JavaCompilationArtifacts.Builder();
    Artifact instrumentationMetadata =
        helper.createInstrumentationMetadata(classJar, javaArtifactsBuilder);

    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    Artifact executableForRunfiles = null;
    if (createExecutable) {
      // This artifact is named as the rule itself, e.g. //foo:bar_bin -> bazel-bin/foo/bar_bin
      executableForRunfiles = ruleContext.createOutputArtifact();
      filesBuilder.add(classJar).add(executableForRunfiles);

      if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
        mainClass =
            semantics.addCoverageSupport(
                helper,
                attributesBuilder,
                executableForRunfiles,
                instrumentationMetadata,
                javaArtifactsBuilder,
                mainClass);
      }
    } else {
      filesBuilder.add(classJar);
    }

    JavaTargetAttributes attributes = helper.getAttributes();
    List<Artifact> nativeLibraries = attributes.getNativeLibraries();
    if (!nativeLibraries.isEmpty()) {
      jvmFlags.add("-Djava.library.path="
          + JavaCommon.javaLibraryPath(nativeLibraries,
              ruleContext.getRule().getPackage().getWorkspaceName()));
    }

    JavaConfiguration javaConfig = ruleContext.getFragment(JavaConfiguration.class);
    if (attributes.hasMessages()) {
      helper.setTranslations(
          semantics.translate(ruleContext, javaConfig, attributes.getMessages()));
    }

    if (attributes.hasSourceFiles() || attributes.hasSourceJars()
        || attributes.hasResources() || attributes.hasClassPathResources()) {
      // We only want to add a jar to the classpath of a dependent rule if it has content.
      javaArtifactsBuilder.addRuntimeJar(classJar);
    }

    GeneratedExtensionRegistryProvider generatedExtensionRegistryProvider =
        semantics.createGeneratedExtensionRegistry(
            ruleContext,
            common,
            filesBuilder,
            javaArtifactsBuilder,
            ruleOutputJarsProviderBuilder,
            javaSourceJarsProviderBuilder);
    Artifact outputDepsProto = helper.createOutputDepsProtoArtifact(classJar, javaArtifactsBuilder);
    ruleOutputJarsProviderBuilder.setJdeps(outputDepsProto);

    JavaCompilationArtifacts javaArtifacts = javaArtifactsBuilder.build();
    common.setJavaCompilationArtifacts(javaArtifacts);

    Artifact manifestProtoOutput = helper.createManifestProtoOutput(classJar);

    // The gensrc jar is created only if the target uses annotation processing. Otherwise,
    // it is null, and the source jar action will not depend on the compile action.
    Artifact genSourceJar = null;
    Artifact genClassJar = null;
    if (helper.usesAnnotationProcessing()) {
      genClassJar = helper.createGenJar(classJar);
      genSourceJar = helper.createGensrcJar(classJar);
      helper.createGenJarAction(classJar, manifestProtoOutput, genClassJar);
    }

    helper.createCompileAction(
        classJar, manifestProtoOutput, genSourceJar, outputDepsProto, instrumentationMetadata);
    helper.createSourceJarAction(srcJar, genSourceJar);

    common.setClassPathFragment(
        new ClasspathConfiguredFragment(
            javaArtifacts, attributes, false, helper.getBootclasspathOrDefault()));

    // Collect the action inputs for the runfiles collector here because we need to access the
    // analysis environment, and that may no longer be safe when the runfiles collector runs.
    Iterable<Artifact> dynamicRuntimeActionInputs =
        CppHelper.getToolchain(ruleContext).getDynamicRuntimeLinkInputs();


    Iterables.addAll(jvmFlags,
        semantics.getJvmFlags(ruleContext, common.getSrcsArtifacts(), userJvmFlags));
    if (ruleContext.hasErrors()) {
      return null;
    }

    Artifact executableToRun = executableForRunfiles;
    if (createExecutable) {
      // Create a shell stub for a Java application
      executableToRun =
          semantics.createStubAction(
              ruleContext,
              common,
              jvmFlags,
              executableForRunfiles,
              mainClass,
              JavaCommon.getJavaBinSubstitution(ruleContext, launcher));
      if (!executableToRun.equals(executableForRunfiles)) {
        filesBuilder.add(executableToRun);
        runfilesBuilder.addArtifact(executableToRun);
      }
    }

    JavaSourceJarsProvider sourceJarsProvider = javaSourceJarsProviderBuilder.build();
    NestedSet<Artifact> transitiveSourceJars = sourceJarsProvider.getTransitiveSourceJars();

    // TODO(bazel-team): if (getOptions().sourceJars) then make this a dummy prerequisite for the
    // DeployArchiveAction ? Needs a few changes there as we can't pass inputs
    SingleJarActionBuilder.createSourceJarAction(ruleContext,
        ImmutableMap.<PathFragment, Artifact>of(), transitiveSourceJars.toCollection(),
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_DEPLOY_SOURCE_JAR));

    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext);
    builder.add(
        JavaPrimaryClassProvider.class,
        new JavaPrimaryClassProvider(
            semantics.getPrimaryClass(ruleContext, common.getSrcsArtifacts())));
    semantics.addProviders(ruleContext, common, jvmFlags, classJar, srcJar,
            genClassJar, genSourceJar, ImmutableMap.<Artifact, Artifact>of(),
            filesBuilder, builder);
    if (generatedExtensionRegistryProvider != null) {
      builder.add(GeneratedExtensionRegistryProvider.class, generatedExtensionRegistryProvider);
    }

    Artifact deployJar =
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_DEPLOY_JAR);
    boolean runProguard = applyProguardIfRequested(
        ruleContext, deployJar, common.getBootClasspath(), mainClass, semantics, filesBuilder);

    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    // Need not include normal runtime classpath in runfiles if Proguard is used because _deploy.jar
    // is used as classpath instead.  Keeping runfiles unchanged has however the advantage that
    // manually running executable without --singlejar works (although it won't depend on Proguard).
    collectDefaultRunfiles(runfilesBuilder, ruleContext, common, javaArtifacts, filesToBuild,
        launcher, dynamicRuntimeActionInputs);
    Runfiles defaultRunfiles = runfilesBuilder.build();

    RunfilesSupport runfilesSupport = null;
    if (createExecutable) {
      List<String> extraArgs =
          new ArrayList<>(semantics.getExtraArguments(ruleContext, common.getSrcsArtifacts()));
      if (runProguard) {
        // Instead of changing the classpath written into the wrapper script, pass --singlejar when
        // running the script (which causes the deploy.jar written by Proguard to be used instead of
        // the normal classpath). It's a bit odd to do this b/c manually running the script wouldn't
        // use Proguard's output unless --singlejar is explicitly supplied.  On the other hand the
        // behavior of the script is more consistent: the (proguarded) deploy.jar is only used with
        // --singlejar.  Moreover, people will almost always run tests using blaze test, which does
        // use Proguard's output thanks to this extra arg when enabled.  Also, it's actually hard to
        // get the classpath changed in the wrapper script (would require calling
        // JavaCommon.setClasspathFragment with a new fragment at the *end* of this method because
        // the classpath is evaluated lazily when generating the wrapper script) and the wrapper
        // script would essentially have an if (--singlejar was set), set classpath to deploy jar,
        // otherwise, set classpath to deploy jar.
        extraArgs.add("--wrapper_script_flag=--singlejar");
      }
      // The executable we pass here will be used when creating the runfiles directory. E.g. for the
      // stub script called bazel-bin/foo/bar_bin, the runfiles directory will be created under
      // bazel-bin/foo/bar_bin.runfiles . On platforms where there's an extra stub script (Windows)
      // which dispatches to this one, we still create the runfiles directory for the shell script,
      // but use the dispatcher script (a batch file) as the RunfilesProvider's executable.
      runfilesSupport =
          RunfilesSupport.withExecutable(
              ruleContext, defaultRunfiles, executableForRunfiles, extraArgs);
    }

    RunfilesProvider runfilesProvider = RunfilesProvider.withData(
        defaultRunfiles,
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(),
            ruleContext.getConfiguration().legacyExternalRunfiles())
            .merge(runfilesSupport)
            .build());

    ImmutableList<String> deployManifestLines =
        getDeployManifestLines(ruleContext, originalMainClass);

    // When running Proguard:
    // (1) write single jar to intermediate destination; Proguard will write _deploy.jar file
    // (2) Don't depend on runfiles to avoid circular dependency, since _deploy.jar is itself part
    //     of runfiles when Proguard runs (because executable then needs it) and _deploy.jar depends
    //     on this single jar.
    // (3) Don't bother with compression since Proguard will write the final jar anyways
    deployArchiveBuilder
        .setOutputJar(
            runProguard
                ? ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_MERGED_JAR)
                : deployJar)
        .setJavaStartClass(mainClass)
        .setDeployManifestLines(deployManifestLines)
        .setAttributes(attributes)
        .addRuntimeJars(javaArtifacts.getRuntimeJars())
        .setIncludeBuildData(true)
        .setRunfilesMiddleman(
            runProguard || runfilesSupport == null ? null : runfilesSupport.getRunfilesMiddleman())
        .setCompression(runProguard ? UNCOMPRESSED : COMPRESSED)
        .setLauncher(launcher)
        .build();

    Artifact unstrippedDeployJar =
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_UNSTRIPPED_BINARY_DEPLOY_JAR);
    if (stripAsDefault) {
      unstrippedDeployArchiveBuilder
          .setOutputJar(unstrippedDeployJar)
          .setJavaStartClass(mainClass)
          .setDeployManifestLines(deployManifestLines)
          .setAttributes(attributes)
          .addRuntimeJars(javaArtifacts.getRuntimeJars())
          .setIncludeBuildData(true)
          .setRunfilesMiddleman(
              runfilesSupport == null ? null : runfilesSupport.getRunfilesMiddleman())
          .setCompression(COMPRESSED)
          .setLauncher(unstrippedLauncher);

      unstrippedDeployArchiveBuilder.build();
    } else {
      // Write an empty file as the name_deploy.jar.unstripped when the default output jar is not
      // stripped.
      ruleContext.registerAction(
          FileWriteAction.create(ruleContext, unstrippedDeployJar, "", false));
    }

    JavaRuleOutputJarsProvider ruleOutputJarsProvider = ruleOutputJarsProviderBuilder.build();
    JavaSkylarkApiProvider.Builder skylarkApiProvider =
        JavaSkylarkApiProvider.builder()
            .setRuleOutputJarsProvider(ruleOutputJarsProvider)
            .setSourceJarsProvider(sourceJarsProvider);

    common.addTransitiveInfoProviders(builder, skylarkApiProvider, filesToBuild, classJar);
    common.addGenJarsProvider(builder, skylarkApiProvider, genClassJar, genSourceJar);

    return builder
        .setFilesToBuild(filesToBuild)
        .addSkylarkTransitiveInfo(JavaSkylarkApiProvider.NAME, skylarkApiProvider.build())
        .add(JavaRuleOutputJarsProvider.class, ruleOutputJarsProvider)
        .add(RunfilesProvider.class, runfilesProvider)
        // The executable to run (below) may be different from the executable for runfiles (the one
        // we create the runfiles support object with). On Linux they are the same (it's the same
        // shell script), on Windows they are different (the executable to run is a batch file, the
        // executable for runfiles is the shell script).
        .setRunfilesSupport(runfilesSupport, executableToRun)
        .add(
            JavaRuntimeClasspathProvider.class,
            new JavaRuntimeClasspathProvider(common.getRuntimeClasspath()))
        .add(
            JavaSourceInfoProvider.class,
            JavaSourceInfoProvider.fromJavaTargetAttributes(attributes, semantics))
        .add(JavaSourceJarsProvider.class, sourceJarsProvider)
        .addOutputGroup(JavaSemantics.SOURCE_JARS_OUTPUT_GROUP, transitiveSourceJars)
        .build();
  }

  // Create the deploy jar and make it dependent on the runfiles middleman if an executable is
  // created. Do not add the deploy jar to files to build, so we will only build it when it gets
  // requested.
  private ImmutableList<String> getDeployManifestLines(RuleContext ruleContext,
      String originalMainClass) {
    ImmutableList.Builder<String> builder = ImmutableList.<String>builder()
          .addAll(ruleContext.attributes().get("deploy_manifest_lines", Type.STRING_LIST));
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      builder.add("Coverage-Main-Class: " + originalMainClass);
    }
    return builder.build();
  }

  private void collectDefaultRunfiles(Runfiles.Builder builder, RuleContext ruleContext,
      JavaCommon common, JavaCompilationArtifacts javaArtifacts, NestedSet<Artifact> filesToBuild,
      Artifact launcher, Iterable<Artifact> dynamicRuntimeActionInputs) {
    // Convert to iterable: filesToBuild has a different order.
    builder.addArtifacts((Iterable<Artifact>) filesToBuild);
    builder.addArtifacts(javaArtifacts.getRuntimeJars());
    if (launcher != null) {
      final TransitiveInfoCollection defaultLauncher =
          JavaHelper.launcherForTarget(semantics, ruleContext);
      final Artifact defaultLauncherArtifact =
          JavaHelper.launcherArtifactForTarget(semantics, ruleContext);
      if (!defaultLauncherArtifact.equals(launcher)) {
        builder.addArtifact(launcher);

        // N.B. The "default launcher" referred to here is the launcher target specified through
        // an attribute or flag. We wish to retain the runfiles of the default launcher, *except*
        // for the original cc_binary artifact, because we've swapped it out with our custom
        // launcher. Hence, instead of calling builder.addTarget(), or adding an odd method
        // to Runfiles.Builder, we "unravel" the call and manually add things to the builder.
        // Because the NestedSet representing each target's launcher runfiles is re-built here,
        // we may see increased memory consumption for representing the target's runfiles.
        Runfiles runfiles =
            defaultLauncher.getProvider(RunfilesProvider.class)
              .getDefaultRunfiles();
        NestedSetBuilder<Artifact> unconditionalArtifacts = NestedSetBuilder.compileOrder();
        for (Artifact a : runfiles.getUnconditionalArtifacts()) {
          if (!a.equals(defaultLauncherArtifact)) {
            unconditionalArtifacts.add(a);
          }
        }
        builder.addTransitiveArtifacts(unconditionalArtifacts.build());
        builder.addSymlinks(runfiles.getSymlinks());
        builder.addRootSymlinks(runfiles.getRootSymlinks());
        builder.addPruningManifests(runfiles.getPruningManifests());
      } else {
        builder.addTarget(defaultLauncher, RunfilesProvider.DEFAULT_RUNFILES);
      }
    }

    semantics.addRunfilesForBinary(ruleContext, launcher, builder);
    builder.addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES);
    builder.add(ruleContext, JavaRunfilesProvider.TO_RUNFILES);

    List<? extends TransitiveInfoCollection> runtimeDeps =
        ruleContext.getPrerequisites("runtime_deps", Mode.TARGET);
    builder.addTargets(runtimeDeps, JavaRunfilesProvider.TO_RUNFILES);
    builder.addTargets(runtimeDeps, RunfilesProvider.DEFAULT_RUNFILES);
    semantics.addDependenciesForRunfiles(ruleContext, builder);

    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      Artifact instrumentedJar = javaArtifacts.getInstrumentedJar();
      if (instrumentedJar != null) {
        builder.addArtifact(instrumentedJar);
      }
    }

    builder.addArtifacts((Iterable<Artifact>) common.getRuntimeClasspath());

    // Add the JDK files if it comes from the source repository (see java_stub_template.txt).
    TransitiveInfoCollection javabaseTarget = ruleContext.getPrerequisite(":jvm", Mode.TARGET);
    if (javabaseTarget != null) {
      builder.addArtifacts(
          (Iterable<Artifact>) javabaseTarget.getProvider(FileProvider.class).getFilesToBuild());

      // Add symlinks to the C++ runtime libraries under a path that can be built
      // into the Java binary without having to embed the crosstool, gcc, and grte
      // version information contained within the libraries' package paths.
      for (Artifact lib : dynamicRuntimeActionInputs) {
        PathFragment path = CPP_RUNTIMES.getRelative(lib.getExecPath().getBaseName());
        builder.addSymlink(path, lib);
      }
    }
  }

  /**
   * Collects the native libraries in the transitive closure of the deps.
   *
   * @param deps the dependencies to be included as roots of the transitive closure.
   * @return the native libraries found in the transitive closure of the deps.
   */
  public static Collection<Artifact> collectNativeLibraries(
      Iterable<? extends TransitiveInfoCollection> deps) {
    NestedSet<LinkerInput> linkerInputs = new NativeLibraryNestedSetBuilder()
        .addJavaTargets(deps)
        .build();
    ImmutableList.Builder<Artifact> result = ImmutableList.builder();
    for (LinkerInput linkerInput : linkerInputs) {
      result.add(linkerInput.getArtifact());
    }

    return result.build();
  }

  /**
   * This method uses {@link ProguardHelper#applyProguardIfRequested} to create a proguard action
   * if necessary and adds any artifacts created by proguard to the given {@code filesBuilder}.
   * This is convenience to make sure the proguarded Jar is included in the files to build, which is
   * necessary because the Jar written by proguard is used at runtime.
   * If this method returns {@code true} the Proguard is being used and we need to use a
   * {@link DeployArchiveBuilder} to write the input artifact assumed by
   * {@link ProguardHelper#applyProguardIfRequested}.
   */
  private static boolean applyProguardIfRequested(RuleContext ruleContext, Artifact deployJar,
      ImmutableList<Artifact> bootclasspath, String mainClassName, JavaSemantics semantics,
      NestedSetBuilder<Artifact> filesBuilder) throws InterruptedException {
    // We only support proguarding tests so Proguard doesn't try to proguard itself.
    if (!ruleContext.getRule().getRuleClass().endsWith("_test")) {
      return false;
    }
    ProguardOutput output =
        JavaBinaryProguardHelper.INSTANCE.applyProguardIfRequested(
            ruleContext, deployJar, bootclasspath, mainClassName, semantics);
    if (output == null) {
      return false;
    }
    output.addAllToSet(filesBuilder);
    return true;
  }

  private static class JavaBinaryProguardHelper extends ProguardHelper {

    static final JavaBinaryProguardHelper INSTANCE = new JavaBinaryProguardHelper();

    @Override
    @Nullable
    protected FilesToRunProvider findProguard(RuleContext ruleContext) {
      // TODO(bazel-team): Find a way to use Proguard specified in android_sdk rules
      return ruleContext.getExecutablePrerequisite(":proguard", Mode.HOST);
    }

    @Override
    protected ImmutableList<Artifact> collectProguardSpecsForRule(
        RuleContext ruleContext, ImmutableList<Artifact> bootclasspath, String mainClassName) {
      return ImmutableList.of(generateSpecForJavaBinary(ruleContext, mainClassName));
    }
  }
}
