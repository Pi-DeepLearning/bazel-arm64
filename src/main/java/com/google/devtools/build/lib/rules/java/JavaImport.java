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

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.cpp.CcLinkParams;
import com.google.devtools.build.lib.rules.cpp.CcLinkParamsProvider;
import com.google.devtools.build.lib.rules.cpp.CcLinkParamsStore;
import com.google.devtools.build.lib.rules.cpp.CppCompilationContext;
import com.google.devtools.build.lib.rules.cpp.LinkerInput;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgs.ClasspathType;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An implementation for the "java_import" rule.
 */
public class JavaImport implements RuleConfiguredTargetFactory {
  private final JavaSemantics semantics;

  protected JavaImport(JavaSemantics semantics) {
    this.semantics = semantics;
  }

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException {
    ImmutableList<Artifact> srcJars = ImmutableList.of();
    ImmutableList<Artifact> jars = collectJars(ruleContext);
    Artifact srcJar = ruleContext.getPrerequisiteArtifact("srcjar", Mode.TARGET);

    if (ruleContext.hasErrors()) {
      return null;
    }

    ImmutableList<TransitiveInfoCollection> targets =
        ImmutableList.<TransitiveInfoCollection>builder()
            .addAll(ruleContext.getPrerequisites("deps", Mode.TARGET))
            .addAll(ruleContext.getPrerequisites("exports", Mode.TARGET))
            .build();
    final JavaCommon common = new JavaCommon(
        ruleContext, semantics, /*srcs=*/ImmutableList.<Artifact>of(), targets, targets, targets);
    semantics.checkRule(ruleContext, common);

    // No need for javac options - no compilation happening here.
    ImmutableBiMap.Builder<Artifact, Artifact> compilationToRuntimeJarMapBuilder =
        ImmutableBiMap.builder();
    ImmutableList<Artifact> interfaceJars =
        processWithIjar(jars, ruleContext, compilationToRuntimeJarMapBuilder);

    JavaCompilationArtifacts javaArtifacts = collectJavaArtifacts(jars, interfaceJars);
    common.setJavaCompilationArtifacts(javaArtifacts);

    CppCompilationContext transitiveCppDeps = common.collectTransitiveCppDeps();
    NestedSet<LinkerInput> transitiveJavaNativeLibraries =
        common.collectTransitiveJavaNativeLibraries();
    boolean neverLink = JavaCommon.isNeverLink(ruleContext);
    JavaCompilationArgs javaCompilationArgs =
        common.collectJavaCompilationArgs(false, neverLink, false);
    JavaCompilationArgs recursiveJavaCompilationArgs =
        common.collectJavaCompilationArgs(true, neverLink, false);
    NestedSet<Artifact> transitiveJavaSourceJars =
        collectTransitiveJavaSourceJars(ruleContext, srcJar);
    if (srcJar != null) {
      srcJars = ImmutableList.of(srcJar);
    }

    // The "neverlink" attribute is transitive, so if it is enabled, we don't add any
    // runfiles from this target or its dependencies.
    Runfiles runfiles = neverLink ?
        Runfiles.EMPTY :
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(),
            ruleContext.getConfiguration().legacyExternalRunfiles())
            // add the jars to the runfiles
            .addArtifacts(javaArtifacts.getRuntimeJars())
            .addTargets(targets, RunfilesProvider.DEFAULT_RUNFILES)
            .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
            .addTargets(targets, JavaRunfilesProvider.TO_RUNFILES)
            .add(ruleContext, JavaRunfilesProvider.TO_RUNFILES)
            .build();

    CcLinkParamsStore ccLinkParamsStore = new CcLinkParamsStore() {
      @Override
      protected void collect(CcLinkParams.Builder builder, boolean linkingStatically,
                             boolean linkShared) {
        builder.addTransitiveTargets(common.targetsTreatedAsDeps(ClasspathType.BOTH),
            JavaCcLinkParamsProvider.TO_LINK_PARAMS, CcLinkParamsProvider.TO_LINK_PARAMS);
      }
    };
    RuleConfiguredTargetBuilder ruleBuilder =
        new RuleConfiguredTargetBuilder(ruleContext);
    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    filesBuilder.addAll(jars);

    ImmutableBiMap<Artifact, Artifact> compilationToRuntimeJarMap =
        compilationToRuntimeJarMapBuilder.build();
    semantics.addProviders(
        ruleContext,
        common,
        ImmutableList.<String>of(),
        null /* classJar */,
        srcJar /* srcJar */,
        null /* genJar */,
        null /* gensrcJar */,
        compilationToRuntimeJarMap,
        filesBuilder,
        ruleBuilder);

    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    JavaSourceInfoProvider javaSourceInfoProvider = new JavaSourceInfoProvider.Builder()
        .setJarFiles(jars)
        .setSourceJarsForJarFiles(srcJars)
        .build();

    JavaRuleOutputJarsProvider.Builder ruleOutputJarsProviderBuilder =
        JavaRuleOutputJarsProvider.builder();
    for (Artifact jar : jars) {
      ruleOutputJarsProviderBuilder.addOutputJar(
          jar, compilationToRuntimeJarMap.inverse().get(jar), srcJar);
    }

    NestedSet<Artifact> proguardSpecs = new ProguardLibrary(ruleContext).collectProguardSpecs();

    JavaRuleOutputJarsProvider ruleOutputJarsProvider = ruleOutputJarsProviderBuilder.build();
    JavaSourceJarsProvider sourceJarsProvider =
        JavaSourceJarsProvider.create(transitiveJavaSourceJars, srcJars);
    JavaCompilationArgsProvider compilationArgsProvider =
        JavaCompilationArgsProvider.create(javaCompilationArgs, recursiveJavaCompilationArgs);
    JavaSkylarkApiProvider.Builder skylarkApiProvider =
        JavaSkylarkApiProvider.builder()
            .setRuleOutputJarsProvider(ruleOutputJarsProvider)
            .setSourceJarsProvider(sourceJarsProvider)
            .setCompilationArgsProvider(compilationArgsProvider);
    common.addTransitiveInfoProviders(ruleBuilder, skylarkApiProvider, filesToBuild, null);
    return ruleBuilder
        .setFilesToBuild(filesToBuild)
        .addSkylarkTransitiveInfo(JavaSkylarkApiProvider.NAME, skylarkApiProvider.build())
        .add(JavaRuleOutputJarsProvider.class, ruleOutputJarsProvider)
        .add(
            JavaRuntimeJarProvider.class,
            new JavaRuntimeJarProvider(javaArtifacts.getRuntimeJars()))
        .add(JavaNeverlinkInfoProvider.class, new JavaNeverlinkInfoProvider(neverLink))
        .add(RunfilesProvider.class, RunfilesProvider.simple(runfiles))
        .add(CcLinkParamsProvider.class, new CcLinkParamsProvider(ccLinkParamsStore))
        .add(JavaCompilationArgsProvider.class, compilationArgsProvider)
        .add(
            JavaNativeLibraryProvider.class,
            new JavaNativeLibraryProvider(transitiveJavaNativeLibraries))
        .add(CppCompilationContext.class, transitiveCppDeps)
        .add(JavaSourceInfoProvider.class, javaSourceInfoProvider)
        .add(JavaSourceJarsProvider.class, sourceJarsProvider)
        .add(ProguardSpecProvider.class, new ProguardSpecProvider(proguardSpecs))
        .addOutputGroup(JavaSemantics.SOURCE_JARS_OUTPUT_GROUP, transitiveJavaSourceJars)
        .addOutputGroup(OutputGroupProvider.HIDDEN_TOP_LEVEL, proguardSpecs)
        .build();
  }

  private NestedSet<Artifact> collectTransitiveJavaSourceJars(RuleContext ruleContext,
      Artifact srcJar) {
    NestedSetBuilder<Artifact> transitiveJavaSourceJarBuilder =
        NestedSetBuilder.stableOrder();
    if (srcJar != null) {
      transitiveJavaSourceJarBuilder.add(srcJar);
    }
    for (JavaSourceJarsProvider other :
        ruleContext.getPrerequisites("exports", Mode.TARGET, JavaSourceJarsProvider.class)) {
      transitiveJavaSourceJarBuilder.addTransitive(other.getTransitiveSourceJars());
    }
    return transitiveJavaSourceJarBuilder.build();
  }

  private JavaCompilationArtifacts collectJavaArtifacts(
      ImmutableList<Artifact> jars,
      ImmutableList<Artifact> interfaceJars) {
    JavaCompilationArtifacts.Builder javaArtifactsBuilder = new JavaCompilationArtifacts.Builder();
    javaArtifactsBuilder.addRuntimeJars(jars);
    // interfaceJars Artifacts have proper owner labels
    javaArtifactsBuilder.addCompileTimeJars(interfaceJars);
    return javaArtifactsBuilder.build();
  }

  private ImmutableList<Artifact> collectJars(RuleContext ruleContext) {
    Set<Artifact> jars = new LinkedHashSet<>();
    for (TransitiveInfoCollection info : ruleContext.getPrerequisites("jars", Mode.TARGET)) {
      if (info.getProvider(JavaCompilationArgsProvider.class) != null) {
        ruleContext.attributeError("jars", "should not refer to Java rules");
      }
      for (Artifact jar : info.getProvider(FileProvider.class).getFilesToBuild()) {
        if (!JavaSemantics.JAR.matches(jar.getFilename())) {
          ruleContext.attributeError("jars", jar.getFilename() + " is not a .jar file");
        } else {
          if (!jars.add(jar)) {
            ruleContext.attributeError("jars", jar.getFilename() + " is a duplicate");
          }
        }
      }
    }
    return ImmutableList.copyOf(jars);
  }

  private ImmutableList<Artifact> processWithIjar(ImmutableList<Artifact> jars,
      RuleContext ruleContext,
      ImmutableMap.Builder<Artifact, Artifact> compilationToRuntimeJarMap) {
    ImmutableList.Builder<Artifact> interfaceJarsBuilder = ImmutableList.builder();
    for (Artifact jar : jars) {
      Artifact ijar = JavaCompilationHelper.createIjarAction(
          ruleContext,
          JavaCompilationHelper.getJavaToolchainProvider(ruleContext),
          jar, true);
      interfaceJarsBuilder.add(ijar);
      compilationToRuntimeJarMap.put(ijar, jar);
    }
    return interfaceJarsBuilder.build();
  }
}
