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

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CommandLine;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.collect.IterablesChain;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Utility for configuring an action to generate a deploy archive.
 */
public class DeployArchiveBuilder {
  /**
   * Memory consumption of SingleJar is about 250 bytes per entry in the output file. Unfortunately,
   * the JVM tends to kill the process with an OOM long before we're at the limit. In the most
   * recent example, 400 MB of memory was enough for about 500,000 entries.
   */
  private static final String SINGLEJAR_MAX_MEMORY = "-Xmx1600m";

  private final RuleContext ruleContext;

  private final IterablesChain.Builder<Artifact> runtimeJarsBuilder = IterablesChain.builder();

  private final JavaSemantics semantics;

  private JavaTargetAttributes attributes;
  private boolean includeBuildData;
  private Compression compression = Compression.UNCOMPRESSED;
  @Nullable private Artifact runfilesMiddleman;
  private Artifact outputJar;
  @Nullable private String javaStartClass;
  private ImmutableList<String> deployManifestLines = ImmutableList.of();
  @Nullable private Artifact launcher;
  private Function<Artifact, Artifact> derivedJars = Functions.identity();

  /**
   * Type of compression to apply to output archive.
   */
  public enum Compression {

    /** Output should be compressed */
    COMPRESSED,

    /** Output should not be compressed */
    UNCOMPRESSED;
  }

  /**
   * Creates a builder using the configuration of the rule as the action configuration.
   */
  public DeployArchiveBuilder(JavaSemantics semantics, RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    this.semantics = semantics;
  }

  /**
   * Sets the processed attributes of the rule generating the deploy archive.
   */
  public DeployArchiveBuilder setAttributes(JavaTargetAttributes attributes) {
    this.attributes = attributes;
    return this;
  }

  /**
   * Sets whether to include build-data.properties in the deploy archive.
   */
  public DeployArchiveBuilder setIncludeBuildData(boolean includeBuildData) {
    this.includeBuildData = includeBuildData;
    return this;
  }

  /**
   * Sets whether to enable compression of the output deploy archive.
   */
  public DeployArchiveBuilder setCompression(Compression compress) {
    this.compression = Preconditions.checkNotNull(compress);
    return this;
  }

  /**
   * Sets additional dependencies to be added to the action that creates the
   * deploy jar so that we force the runtime dependencies to be built.
   */
  public DeployArchiveBuilder setRunfilesMiddleman(@Nullable Artifact runfilesMiddleman) {
    this.runfilesMiddleman = runfilesMiddleman;
    return this;
  }

  /**
   * Sets the artifact to create with the action.
   */
  public DeployArchiveBuilder setOutputJar(Artifact outputJar) {
    this.outputJar = Preconditions.checkNotNull(outputJar);
    return this;
  }

  /**
   * Sets the class to launch the Java application.
   */
  public DeployArchiveBuilder setJavaStartClass(@Nullable String javaStartClass) {
    this.javaStartClass = javaStartClass;
    return this;
  }

  /**
   * Adds additional jars that should be on the classpath at runtime.
   */
  public DeployArchiveBuilder addRuntimeJars(Iterable<Artifact> jars) {
    this.runtimeJarsBuilder.add(jars);
    return this;
  }

  /**
   * Sets the list of extra lines to add to the archive's MANIFEST.MF file.
   */
  public DeployArchiveBuilder setDeployManifestLines(Iterable<String> deployManifestLines) {
    this.deployManifestLines = ImmutableList.copyOf(deployManifestLines);
    return this;
  }

  /**
   * Sets the optional launcher to be used as the executable for this deploy
   * JAR
   */
  public DeployArchiveBuilder setLauncher(@Nullable Artifact launcher) {
    this.launcher = launcher;
    return this;
  }

  public DeployArchiveBuilder setDerivedJarFunction(Function<Artifact, Artifact> derivedJars) {
    this.derivedJars = derivedJars;
    return this;
  }

  public static CustomCommandLine.Builder defaultSingleJarCommandLine(Artifact outputJar,
      String javaMainClass,
      ImmutableList<String> deployManifestLines, Iterable<Artifact> buildInfoFiles,
      ImmutableList<Artifact> classpathResources,
      Iterable<Artifact> runtimeClasspath, boolean includeBuildData,
      Compression compress, Artifact launcher) {

    CustomCommandLine.Builder args = CustomCommandLine.builder();
    args.addExecPath("--output", outputJar);
    if (compress == Compression.COMPRESSED) {
      args.add("--compression");
    }
    args.add("--normalize");
    if (javaMainClass != null) {
      args.add("--main_class");
      args.add(javaMainClass);
    }

    if (!deployManifestLines.isEmpty()) {
      args.add("--deploy_manifest_lines");
      args.add(deployManifestLines);
    }

    if (buildInfoFiles != null) {
      for (Artifact artifact : buildInfoFiles) {
        args.addExecPath("--build_info_file", artifact);
      }
    }
    if (!includeBuildData) {
      args.add("--exclude_build_data");
    }
    if (launcher != null) {
      args.add("--java_launcher");
      args.add(launcher.getExecPathString());
    }

    args.addExecPaths("--classpath_resources", classpathResources);
    args.addExecPaths("--sources", runtimeClasspath);
    return args;
  }

  /** Computes input artifacts for a deploy archive based on the given attributes. */
  public static IterablesChain<Artifact> getArchiveInputs(JavaTargetAttributes attributes) {
    return getArchiveInputs(attributes, Functions.<Artifact>identity());
  }

  private static IterablesChain<Artifact> getArchiveInputs(JavaTargetAttributes attributes,
      Function<Artifact, Artifact> derivedJarFunction) {
    IterablesChain.Builder<Artifact> inputs = IterablesChain.builder();
    inputs.add(
        ImmutableList.copyOf(
            Iterables.transform(attributes.getRuntimeClassPathForArchive(), derivedJarFunction)));
    // TODO(bazel-team): Remove?  Resources not used as input to singlejar action
    inputs.add(ImmutableList.copyOf(attributes.getResources().values()));
    inputs.add(attributes.getClassPathResources());
    return inputs.build();
  }

  /** Builds the action as configured. */
  public void build() throws InterruptedException {
    ImmutableList<Artifact> classpathResources = attributes.getClassPathResources();
    Set<String> classPathResourceNames = new HashSet<>();
    for (Artifact artifact : classpathResources) {
      String name = artifact.getExecPath().getBaseName();
      if (!classPathResourceNames.add(name)) {
        ruleContext.attributeError("classpath_resources",
            "entries must have different file names (duplicate: " + name + ")");
        return;
      }
    }

    IterablesChain<Artifact> runtimeJars = runtimeJarsBuilder.build();

    // TODO(kmb): Consider not using getArchiveInputs, specifically because we don't want/need to
    // transform anything but the runtimeClasspath and b/c we currently do it twice here and below
    IterablesChain.Builder<Artifact> inputs = IterablesChain.builder();
    inputs.add(getArchiveInputs(attributes, derivedJars));

    inputs.add(ImmutableList.copyOf(Iterables.transform(runtimeJars, derivedJars)));
    if (runfilesMiddleman != null) {
      inputs.addElement(runfilesMiddleman);
    }

    ImmutableList<Artifact> buildInfoArtifacts = ruleContext.getBuildInfo(JavaBuildInfoFactory.KEY);
    inputs.add(buildInfoArtifacts);

    Iterable<Artifact> runtimeClasspath =
        Iterables.transform(
            Iterables.concat(runtimeJars, attributes.getRuntimeClassPathForArchive()),
            derivedJars);

    if (launcher != null) {
      inputs.addElement(launcher);
    }

    CommandLine commandLine =  semantics.buildSingleJarCommandLine(ruleContext.getConfiguration(),
        outputJar, javaStartClass, deployManifestLines, buildInfoArtifacts, classpathResources,
        runtimeClasspath, includeBuildData, compression, launcher);

    List<String> jvmArgs = ImmutableList.of(SINGLEJAR_MAX_MEMORY);
    ResourceSet resourceSet =
        ResourceSet.createWithRamCpuIo(/*memoryMb = */200.0, /*cpuUsage = */.2, /*ioUsage=*/.2);

    // If singlejar's name ends with .jar, it is Java application, otherwise it is native.
    // TODO(asmundak): once https://github.com/bazelbuild/bazel/issues/2241 is fixed (that is,
    // the native singlejar is used on windows) remove support for the Java implementation
    Artifact singlejar = getSingleJar(ruleContext);
    if (singlejar.getFilename().endsWith(".jar")) {
      ruleContext.registerAction(
          new SpawnAction.Builder()
              .addInputs(inputs.build())
              .addTransitiveInputs(JavaHelper.getHostJavabaseInputs(ruleContext))
              .addOutput(outputJar)
              .setResources(resourceSet)
              .setJarExecutable(
                  ruleContext.getHostConfiguration().getFragment(Jvm.class).getJavaExecutable(),
                  singlejar,
                  jvmArgs)
              .setCommandLine(commandLine)
              .alwaysUseParameterFile(ParameterFileType.SHELL_QUOTED)
              .setProgressMessage("Building deploy jar " + outputJar.prettyPrint())
              .setMnemonic("JavaDeployJar")
              .setExecutionInfo(ImmutableMap.of("supports-workers", "1"))
              .build(ruleContext));
    } else {
      ruleContext.registerAction(
          new SpawnAction.Builder()
              .addInputs(inputs.build())
              .addOutput(outputJar)
              .setResources(resourceSet)
              .setExecutable(singlejar)
              .setCommandLine(commandLine)
              .alwaysUseParameterFile(ParameterFileType.SHELL_QUOTED)
              .setProgressMessage("Building deploy jar " + outputJar.prettyPrint())
              .setMnemonic("JavaDeployJar")
              .build(ruleContext));
    }
  }

  /** Returns the SingleJar deploy jar Artifact. */
  private static Artifact getSingleJar(RuleContext ruleContext) {
    Artifact singleJar = JavaToolchainProvider.fromRuleContext(ruleContext).getSingleJar();
    if (singleJar != null) {
      return singleJar;
    }
    return ruleContext.getPrerequisiteArtifact("$singlejar", Mode.HOST);
  }
}
