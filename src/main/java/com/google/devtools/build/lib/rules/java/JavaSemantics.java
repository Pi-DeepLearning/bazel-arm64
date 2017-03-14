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

import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.LanguageDependentFragment.LibraryLanguage;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.Runfiles.Builder;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabel;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabelList;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.java.DeployArchiveBuilder.Compression;
import com.google.devtools.build.lib.rules.java.proto.GeneratedExtensionRegistryProvider;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Pluggable Java compilation semantics.
 */
public interface JavaSemantics {

  LibraryLanguage LANGUAGE = new LibraryLanguage("Java");

  SafeImplicitOutputsFunction JAVA_LIBRARY_CLASS_JAR =
      fromTemplates("lib%{name}.jar");
  SafeImplicitOutputsFunction JAVA_LIBRARY_SOURCE_JAR =
      fromTemplates("lib%{name}-src.jar");

  SafeImplicitOutputsFunction JAVA_BINARY_CLASS_JAR =
      fromTemplates("%{name}.jar");
  SafeImplicitOutputsFunction JAVA_BINARY_SOURCE_JAR =
      fromTemplates("%{name}-src.jar");

  SafeImplicitOutputsFunction JAVA_BINARY_DEPLOY_JAR =
      fromTemplates("%{name}_deploy.jar");
  SafeImplicitOutputsFunction JAVA_BINARY_MERGED_JAR =
      fromTemplates("%{name}_merged.jar");
  SafeImplicitOutputsFunction JAVA_UNSTRIPPED_BINARY_DEPLOY_JAR =
      fromTemplates("%{name}_deploy.jar.unstripped");
  SafeImplicitOutputsFunction JAVA_BINARY_PROGUARD_MAP =
      fromTemplates("%{name}_proguard.map");
  SafeImplicitOutputsFunction JAVA_BINARY_PROGUARD_PROTO_MAP =
      fromTemplates("%{name}_proguard.pbmap");
  SafeImplicitOutputsFunction JAVA_BINARY_PROGUARD_SEEDS =
      fromTemplates("%{name}_proguard.seeds");
  SafeImplicitOutputsFunction JAVA_BINARY_PROGUARD_USAGE =
      fromTemplates("%{name}_proguard.usage");
  SafeImplicitOutputsFunction JAVA_BINARY_PROGUARD_CONFIG =
      fromTemplates("%{name}_proguard.config");

  SafeImplicitOutputsFunction JAVA_BINARY_DEPLOY_SOURCE_JAR =
      fromTemplates("%{name}_deploy-src.jar");

  FileType JAVA_SOURCE = FileType.of(".java");
  FileType JAR = FileType.of(".jar");
  FileType PROPERTIES = FileType.of(".properties");
  FileType SOURCE_JAR = FileType.of(".srcjar");
  // TODO(bazel-team): Rename this metadata extension to something meaningful.
  FileType COVERAGE_METADATA = FileType.of(".em");

  /**
   * Label to the Java Toolchain rule. It is resolved from a label given in the java options.
   */
  String JAVA_TOOLCHAIN_LABEL = "//tools/defaults:java_toolchain";

  /** The java_toolchain.compatible_javacopts key for Java 7 javacopts */
  public static final String JAVA7_JAVACOPTS_KEY = "java7";
  /** The java_toolchain.compatible_javacopts key for Android javacopts */
  public static final String ANDROID_JAVACOPTS_KEY = "android";
  /** The java_toolchain.compatible_javacopts key for proto compilations. */
  public static final String PROTO_JAVACOPTS_KEY = "proto";

  LateBoundLabel<BuildConfiguration> JAVA_TOOLCHAIN =
      new LateBoundLabel<BuildConfiguration>(JAVA_TOOLCHAIN_LABEL, JavaConfiguration.class) {
        @Override
        public Label resolve(Rule rule, AttributeMap attributes,
            BuildConfiguration configuration) {
          return configuration.getFragment(JavaConfiguration.class).getToolchainLabel();
        }
      };

  /**
   * Name of the output group used for source jars.
   */
  String SOURCE_JARS_OUTPUT_GROUP =
      OutputGroupProvider.HIDDEN_OUTPUT_GROUP_PREFIX + "source_jars";

  /**
   * Name of the output group used for gen jars (the jars containing the class files for sources
   * generated from annotation processors).
   */
  String GENERATED_JARS_OUTPUT_GROUP =
      OutputGroupProvider.HIDDEN_OUTPUT_GROUP_PREFIX + "gen_jars";


  /**
   * Implementation for the :jvm attribute.
   */
  LateBoundLabel<BuildConfiguration> JVM =
      new LateBoundLabel<BuildConfiguration>(JavaImplicitAttributes.JDK_LABEL, Jvm.class) {
        @Override
        public Label resolve(Rule rule, AttributeMap attributes,
            BuildConfiguration configuration) {
          return configuration.getFragment(Jvm.class).getJvmLabel();
        }
      };

  /**
   * Implementation for the :host_jdk attribute.
   */
  LateBoundLabel<BuildConfiguration> HOST_JDK =
      new LateBoundLabel<BuildConfiguration>(JavaImplicitAttributes.JDK_LABEL, Jvm.class) {
        @Override
        public boolean useHostConfiguration() {
          return true;
        }

        @Override
        public Label resolve(Rule rule, AttributeMap attributes,
            BuildConfiguration configuration) {
          return configuration.getFragment(Jvm.class).getJvmLabel();
        }
      };

  /**
   * Implementation for the :java_launcher attribute. Note that the Java launcher is disabled by
   * default, so it returns null for the configuration-independent default value.
   */
  LateBoundLabel<BuildConfiguration> JAVA_LAUNCHER =
      new LateBoundLabel<BuildConfiguration>(JavaConfiguration.class) {
        @Override
        public Label resolve(Rule rule, AttributeMap attributes, BuildConfiguration configuration) {
          // don't read --java_launcher if this target overrides via a launcher attribute
          if (attributes != null && attributes.isAttributeValueExplicitlySpecified("launcher")) {
            return attributes.get("launcher", LABEL);
          }
          return configuration.getFragment(JavaConfiguration.class).getJavaLauncherLabel();
        }
      };

  LateBoundLabelList<BuildConfiguration> JAVA_PLUGINS =
      new LateBoundLabelList<BuildConfiguration>() {
        @Override
        public List<Label> resolve(Rule rule, AttributeMap attributes,
            BuildConfiguration configuration) {
          return ImmutableList.copyOf(configuration.getPlugins());
        }
      };

  /**
   * Implementation for the :proguard attribute.
   */
  LateBoundLabel<BuildConfiguration> PROGUARD =
      new LateBoundLabel<BuildConfiguration>(JavaConfiguration.class) {
        @Override
        public Label resolve(Rule rule, AttributeMap attributes,
            BuildConfiguration configuration) {
          return configuration.getFragment(JavaConfiguration.class).getProguardBinary();
        }
      };

  LateBoundLabelList<BuildConfiguration> EXTRA_PROGUARD_SPECS =
      new LateBoundLabelList<BuildConfiguration>() {
        @Override
        public List<Label> resolve(Rule rule, AttributeMap attributes,
            BuildConfiguration configuration) {
          return ImmutableList.copyOf(
              configuration.getFragment(JavaConfiguration.class).getExtraProguardSpecs());
        }
      };

  String IJAR_LABEL = "//tools/defaults:ijar";

  /**
   * Verifies if the rule contains any errors.
   *
   * <p>Errors should be signaled through {@link RuleContext}.
   */
  void checkRule(RuleContext ruleContext, JavaCommon javaCommon);

  /**
   * Verifies there are no conflicts in protos.
   *
   * <p>Errors should be signaled through {@link RuleContext}.
   */
  void checkForProtoLibraryAndJavaProtoLibraryOnSameProto(
      RuleContext ruleContext, JavaCommon javaCommon);

  /**
   * Returns the main class of a Java binary.
   */
  String getMainClass(RuleContext ruleContext, ImmutableList<Artifact> srcsArtifacts);

  /**
   * Returns the primary class for a Java binary - either the main class, or, in case of a test,
   * the test class (not the test runner main class).
   */
  String getPrimaryClass(RuleContext ruleContext, ImmutableList<Artifact> srcsArtifacts);

  /**
   * Returns the resources contributed by a Java rule (usually the contents of the
   * {@code resources} attribute)
   */
  ImmutableList<Artifact> collectResources(RuleContext ruleContext);

  /**
   * Constructs the command line to call SingleJar to join all artifacts from
   * {@code classpath} (java code) and {@code resources} into {@code output}.
   */
  CustomCommandLine buildSingleJarCommandLine(BuildConfiguration configuration,
      Artifact output, String mainClass, ImmutableList<String> manifestLines,
      Iterable<Artifact> buildInfoFiles, ImmutableList<Artifact> resources,
      Iterable<Artifact> classpath, boolean includeBuildData,
      Compression compression, Artifact launcher);

  /**
   * Creates the action that writes the Java executable stub script.
   *
   * <p>Returns the launcher script artifact. This may or may not be the same as {@code executable},
   * depending on the implementation of this method. If they are the same, then this Artifact should
   * be used when creating both the {@code RunfilesProvider} and the {@code RunfilesSupport}. If
   * they are different, the new value should be used when creating the {@code RunfilesProvider} (so
   * it will be the stub script executed by "bazel run" for example), and the old value should be
   * used when creasting the {@code RunfilesSupport} (so the runfiles directory will be named after
   * it).
   *
   * <p>For example on Windows we use a double dispatch approach: the launcher is a batch file (and
   * is created and returned by this method) which shells out to a shell script (the {@code
   * executable} argument).
   */
  Artifact createStubAction(
      RuleContext ruleContext,
      final JavaCommon javaCommon,
      List<String> jvmFlags,
      Artifact executable,
      String javaStartClass,
      String javaExecutable);

  /**
   * Adds extra runfiles for a {@code java_binary} rule.
   */
  void addRunfilesForBinary(RuleContext ruleContext, Artifact launcher,
      Runfiles.Builder runfilesBuilder);

  /**
   * Adds extra runfiles for a {@code java_library} rule.
   */
  void addRunfilesForLibrary(RuleContext ruleContext, Runfiles.Builder runfilesBuilder);

  /**
   * Returns the additional options to be passed to javac.
   */
  Iterable<String> getExtraJavacOpts(RuleContext ruleContext);

  /**
   * Add additional targets to be treated as direct dependencies.
   */
  void collectTargetsTreatedAsDeps(
      RuleContext ruleContext, ImmutableList.Builder<TransitiveInfoCollection> builder);

  /**
   * Enables coverage support for the java target - adds instrumented jar to the classpath and
   * modifies main class.
   *
   * @return new main class
   */
  String addCoverageSupport(
      JavaCompilationHelper helper,
      JavaTargetAttributes.Builder attributes,
      Artifact executable,
      Artifact instrumentationMetadata,
      JavaCompilationArtifacts.Builder javaArtifactsBuilder,
      String mainClass)
      throws InterruptedException;

  /**
   * Return the JVM flags to be used in a Java binary.
   */
  Iterable<String> getJvmFlags(
      RuleContext ruleContext, ImmutableList<Artifact> srcsArtifacts, List<String> userJvmFlags);

  /**
   * Adds extra providers to a Java target.
   * @throws InterruptedException
   */
  void addProviders(RuleContext ruleContext,
      JavaCommon javaCommon,
      List<String> jvmFlags,
      Artifact classJar,
      Artifact srcJar,
      Artifact genJar,
      Artifact gensrcJar,
      ImmutableMap<Artifact, Artifact> compilationToRuntimeJarMap,
      NestedSetBuilder<Artifact> filesBuilder,
      RuleConfiguredTargetBuilder ruleBuilder) throws InterruptedException;

  /**
   * Translates XMB messages to translations artifact suitable for Java targets.
   */
  ImmutableList<Artifact> translate(RuleContext ruleContext, JavaConfiguration javaConfig,
      List<Artifact> messages);

  /**
   * Get the launcher artifact for a java binary, creating the necessary actions for it.
   *
   * @param ruleContext The rule context
   * @param common The common helper class.
   * @param deployArchiveBuilder the builder to construct the deploy archive action (mutable).
   * @param runfilesBuilder the builder to construct the list of runfiles (mutable).
   * @param jvmFlags the list of flags to pass to the JVM when running the Java binary (mutable).
   * @param attributesBuilder the builder to construct the list of attributes of this target
   *        (mutable).
   * @return the launcher as an artifact
   * @throws InterruptedException
   */
  Artifact getLauncher(final RuleContext ruleContext, final JavaCommon common,
      DeployArchiveBuilder deployArchiveBuilder, Runfiles.Builder runfilesBuilder,
      List<String> jvmFlags, JavaTargetAttributes.Builder attributesBuilder, boolean shouldStrip)
      throws InterruptedException;

  /**
   * Add extra dependencies for runfiles of a Java binary.
   */
  void addDependenciesForRunfiles(RuleContext ruleContext, Builder builder);

  /**
   * Add a source artifact to a {@link JavaTargetAttributes.Builder}. It is called when a source
   * artifact is processed but is not matched by default patterns in the
   * {@link JavaTargetAttributes.Builder#addSourceArtifacts(Iterable)} method. The semantics can
   * then detect its custom artifact types and add it to the builder.
   */
  void addArtifactToJavaTargetAttribute(JavaTargetAttributes.Builder builder, Artifact srcArtifact);

  /**
   * Works on the list of dependencies of a java target to builder the {@link JavaTargetAttributes}.
   * This work is performed in {@link JavaCommon} for all java targets.
   */
  void commonDependencyProcessing(RuleContext ruleContext, JavaTargetAttributes.Builder attributes,
      Collection<? extends TransitiveInfoCollection> deps);

  /**
   * Takes the path of a Java resource and tries to determine the Java
   * root relative path of the resource.
   *
   * <p>This is only used if the Java rule doesn't have a {@code resource_strip_prefix} attribute.
   *
   * @param path the root relative path of the resource.
   * @return the Java root relative path of the resource of the root
   *         relative path of the resource if no Java root relative path can be
   *         determined.
   */
  PathFragment getDefaultJavaResourcePath(PathFragment path);

  /**
   * @return a list of extra arguments to appends to the runfiles support.
   */
  List<String> getExtraArguments(RuleContext ruleContext, ImmutableList<Artifact> sources);

  /**
   * @return main class (entry point) for the Java compiler.
   */
  String getJavaBuilderMainClass();

  /**
   * @return An artifact representing the protobuf-format version of the
   * proguard mapping, or null if the proguard version doesn't support this.
   */
  Artifact getProtoMapping(RuleContext ruleContext) throws InterruptedException;

  /**
   * Produces the proto generated extension registry artifacts, or <tt>null</tt>
   * if no registry needs to be generated for the provided <tt>ruleContext</tt>.
   */
  @Nullable
  GeneratedExtensionRegistryProvider createGeneratedExtensionRegistry(
      RuleContext ruleContext,
      JavaCommon common,
      NestedSetBuilder<Artifact> filesBuilder,
      JavaCompilationArtifacts.Builder javaCompilationArtifactsBuilder,
      JavaRuleOutputJarsProvider.Builder javaRuleOutputJarsProviderBuilder,
      JavaSourceJarsProvider.Builder javaSourceJarsProviderBuilder)
      throws InterruptedException;

  Artifact getObfuscatedConstantStringMap(RuleContext ruleContext) throws InterruptedException;
}
