// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.rules.objc.XcodeProductType.LIBRARY_STATIC;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabel;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaGenJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaHelper;
import com.google.devtools.build.lib.rules.java.JavaSourceInfoProvider;
import com.google.devtools.build.lib.rules.java.Jvm;
import com.google.devtools.build.lib.rules.objc.CompilationSupport.ExtraCompileArgs;
import com.google.devtools.build.lib.rules.objc.J2ObjcSource.SourceType;
import com.google.devtools.build.lib.rules.proto.ProtoCommon;
import com.google.devtools.build.lib.rules.proto.ProtoCompileActionBuilder;
import com.google.devtools.build.lib.rules.proto.ProtoConfiguration;
import com.google.devtools.build.lib.rules.proto.ProtoSourceFileBlacklist;
import com.google.devtools.build.lib.rules.proto.ProtoSourcesProvider;
import com.google.devtools.build.lib.rules.proto.ProtoSupportDataProvider;
import com.google.devtools.build.lib.rules.proto.SupportData;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;

/** J2ObjC transpilation aspect for Java and proto rules. */
public class J2ObjcAspect extends NativeAspectClass implements ConfiguredAspectFactory {
  public static final String NAME = "J2ObjcAspect";
  private final String toolsRepository;

  private static final ExtraCompileArgs EXTRA_COMPILE_ARGS = new ExtraCompileArgs(
      "-fno-strict-overflow");

  public J2ObjcAspect(String toolsRepository) {
    this.toolsRepository = toolsRepository;
  }

  private static final Iterable<Attribute> JAVA_DEPENDENT_ATTRIBUTES =
      ImmutableList.of(
          new Attribute(":jre_lib", Mode.TARGET),
          new Attribute("deps", Mode.TARGET),
          new Attribute("exports", Mode.TARGET),
          new Attribute("runtime_deps", Mode.TARGET));

  private static final Iterable<Attribute> PROTO_DEPENDENT_ATTRIBUTES =
      ImmutableList.of(
          new Attribute("$protobuf_lib", Mode.TARGET), new Attribute("deps", Mode.TARGET));

  private static final Label JRE_CORE_LIB =
      Label.parseAbsoluteUnchecked("//third_party/java/j2objc:jre_core_lib");

  private static final Label JRE_EMUL_LIB =
      Label.parseAbsoluteUnchecked("//third_party/java/j2objc:jre_emul_lib");

  private static final String PROTO_SOURCE_FILE_BLACKLIST_ATTR = "$j2objc_proto_blacklist";

  /** Flags passed to J2ObjC proto compiler plugin. */
  protected static final Iterable<String> J2OBJC_PLUGIN_PARAMS =
      ImmutableList.of("file_dir_mapping", "generate_class_mappings");

  private static final LateBoundLabel<BuildConfiguration> JRE_LIB =
      new LateBoundLabel<BuildConfiguration>(JRE_CORE_LIB, J2ObjcConfiguration.class) {
    @Override
    public Label resolve(Rule rule, AttributeMap attributes, BuildConfiguration configuration) {
      return configuration.getFragment(J2ObjcConfiguration.class).explicitJreDeps()
          ? JRE_CORE_LIB : JRE_EMUL_LIB;
    }
  };

  /** Adds additional attribute aspects and attributes to the given AspectDefinition.Builder. */
  protected AspectDefinition.Builder addAdditionalAttributes(AspectDefinition.Builder builder) {
    return builder.add(
        attr("$j2objc_plugin", LABEL)
            .cfg(HOST)
            .exec()
            .value(
                Label.parseAbsoluteUnchecked(
                    toolsRepository + "//third_party/java/j2objc:proto_plugin")));
  }

  /** Returns whether this aspect should generate J2ObjC protos from this proto rule */
  protected boolean shouldAttachToProtoRule(RuleContext ruleContext) {
    return true;
  }

  /** Returns whether this aspect allows proto services to be generated from this proto rule */
  protected boolean shouldAllowProtoServices(RuleContext ruleContext) {
    return true;
  }

  @Override
  public AspectDefinition getDefinition(AspectParameters aspectParameters) {
    return addAdditionalAttributes(new AspectDefinition.Builder(this))
        .propagateAlongAttribute("deps")
        .propagateAlongAttribute("exports")
        .propagateAlongAttribute("runtime_deps")
        .requireProviderSets(
            ImmutableList.of(
                ImmutableSet.<Class<?>>of(JavaCompilationArgsProvider.class),
                ImmutableSet.<Class<?>>of(ProtoSourcesProvider.class)))
        .requiresConfigurationFragments(
            AppleConfiguration.class,
            J2ObjcConfiguration.class,
            ObjcConfiguration.class,
            ProtoConfiguration.class)
        .requiresHostConfigurationFragments(Jvm.class)
        .add(
            attr("$j2objc", LABEL)
                .cfg(HOST)
                .exec()
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//tools/j2objc:j2objc_deploy.jar")))
        .add(
            attr("$j2objc_wrapper", LABEL)
                .allowedFileTypes(FileType.of(".py"))
                .cfg(HOST)
                .exec()
                .singleArtifact()
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//tools/j2objc:j2objc_wrapper")))
        .add(
            attr("$jre_emul_jar", LABEL)
                .cfg(HOST)
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//third_party/java/j2objc:jre_emul.jar")))
        .add(attr(":jre_lib", LABEL).value(JRE_LIB))
        .add(
            attr("$protobuf_lib", LABEL)
                .value(Label.parseAbsoluteUnchecked("//third_party/java/j2objc:proto_runtime")))
        .add(
            attr("$xcrunwrapper", LABEL)
                .cfg(HOST)
                .exec()
                .value(Label.parseAbsoluteUnchecked(toolsRepository + "//tools/objc:xcrunwrapper")))
        .add(
            attr(ObjcRuleClasses.LIBTOOL_ATTRIBUTE, LABEL)
                .cfg(HOST)
                .exec()
                .value(Label.parseAbsoluteUnchecked(toolsRepository + "//tools/objc:libtool")))
        .add(
            attr(":xcode_config", LABEL)
                .allowedRuleClasses("xcode_config")
                .checkConstraints()
                .direct_compile_time_input()
                .cfg(HOST)
                .value(new AppleToolchain.XcodeConfigLabel(toolsRepository)))
        .add(
            attr("$zipper", LABEL)
                .cfg(HOST)
                .exec()
                .value(Label.parseAbsoluteUnchecked(toolsRepository + "//tools/zip:zipper")))
        .add(
            ProtoSourceFileBlacklist.blacklistFilegroupAttribute(
                PROTO_SOURCE_FILE_BLACKLIST_ATTR,
                ImmutableList.of(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//tools/j2objc:j2objc_proto_blacklist"))))
        .build();
  }

  @Override
  public ConfiguredAspect create(
      ConfiguredTarget base, RuleContext ruleContext, AspectParameters parameters)
      throws InterruptedException {
    if (isProtoRule(base)) {
      if (shouldAttachToProtoRule(ruleContext)) {
        return proto(base, ruleContext, parameters);
      } else {
        return new ConfiguredAspect.Builder(this, parameters, ruleContext).build();
      }
    } else {
      return java(base, ruleContext, parameters);
    }
  }

  private ConfiguredAspect buildAspect(
      ConfiguredTarget base,
      RuleContext ruleContext,
      AspectParameters parameters,
      J2ObjcSource j2ObjcSource,
      J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider,
      Iterable<Attribute> depAttributes)
      throws InterruptedException {
    ConfiguredAspect.Builder builder = new ConfiguredAspect.Builder(this, parameters, ruleContext);
    ObjcCommon common;
    XcodeProvider xcodeProvider;

    if (!Iterables.isEmpty(j2ObjcSource.getObjcSrcs())) {
      common =
          common(
              ruleContext,
              j2ObjcSource.getObjcSrcs(),
              j2ObjcSource.getObjcHdrs(),
              j2ObjcSource.getHeaderSearchPaths(),
              depAttributes);

      xcodeProvider =
          xcodeProvider(
              ruleContext,
              common,
              j2ObjcSource.getObjcHdrs(),
              j2ObjcSource.getHeaderSearchPaths(),
              depAttributes);

      try {
        CompilationSupport.create(ruleContext)
            .registerCompileAndArchiveActions(common, EXTRA_COMPILE_ARGS)
            .registerFullyLinkAction(common.getObjcProvider(),
                ruleContext.getImplicitOutputArtifact(CompilationSupport.FULLY_LINKED_LIB));
      } catch (RuleErrorException e) {
        ruleContext.ruleError(e.getMessage());
      }
    } else {
      common =
          common(
              ruleContext,
              ImmutableList.<Artifact>of(),
              ImmutableList.<Artifact>of(),
              ImmutableList.<PathFragment>of(),
              depAttributes);
      xcodeProvider =
          xcodeProvider(
              ruleContext,
              common,
              ImmutableList.<Artifact>of(),
              ImmutableList.<PathFragment>of(),
              depAttributes);
    }

    return builder
        .addProvider(
            exportedJ2ObjcMappingFileProvider(base, ruleContext, directJ2ObjcMappingFileProvider))
        .addProvider(common.getObjcProvider())
        .addProvider(xcodeProvider)
        .build();
  }

  private ConfiguredAspect java(
      ConfiguredTarget base, RuleContext ruleContext, AspectParameters parameters)
      throws InterruptedException {
    JavaCompilationArgsProvider compilationArgsProvider =
        base.getProvider(JavaCompilationArgsProvider.class);
    JavaSourceInfoProvider sourceInfoProvider = base.getProvider(JavaSourceInfoProvider.class);
    JavaGenJarsProvider genJarProvider = base.getProvider(JavaGenJarsProvider.class);
    ImmutableSet.Builder<Artifact> javaSourceFilesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<Artifact> javaSourceJarsBuilder = ImmutableSet.builder();
    if (sourceInfoProvider != null) {
      javaSourceFilesBuilder.addAll(sourceInfoProvider.getSourceFiles());

      if (ruleContext.getFragment(J2ObjcConfiguration.class).experimentalSrcJarProcessing()) {
        javaSourceJarsBuilder
            .addAll(sourceInfoProvider.getSourceJars())
            .addAll(sourceInfoProvider.getSourceJarsForJarFiles());
      } else {
        // The old source jar support treates source jars as source files, in the sense that
        // we generate a single concatenated ObjC header and source files for all sources inside
        // a given Java source jar.
        javaSourceFilesBuilder
            .addAll(sourceInfoProvider.getSourceJars())
            .addAll(sourceInfoProvider.getSourceJarsForJarFiles());
      }
    }

    if (genJarProvider != null && genJarProvider.getGenSourceJar() != null) {
      javaSourceJarsBuilder.add(genJarProvider.getGenSourceJar());
    }

    ImmutableSet<Artifact> javaSourceFiles = javaSourceFilesBuilder.build();
    ImmutableSet<Artifact> javaSourceJars = javaSourceJarsBuilder.build();
    J2ObjcSource j2ObjcSource = javaJ2ObjcSource(ruleContext, javaSourceFiles, javaSourceJars);
    J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider =
        depJ2ObjcMappingFileProvider(ruleContext);

    J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider;
    if (Iterables.isEmpty(j2ObjcSource.getObjcSrcs())) {
      directJ2ObjcMappingFileProvider = new J2ObjcMappingFileProvider.Builder().build();
    } else {
      directJ2ObjcMappingFileProvider =
          createJ2ObjcTranspilationAction(
              ruleContext,
              javaSourceFiles,
              javaSourceJars,
              depJ2ObjcMappingFileProvider,
              compilationArgsProvider,
              j2ObjcSource);
    }
    return buildAspect(
        base,
        ruleContext,
        parameters,
        j2ObjcSource,
        directJ2ObjcMappingFileProvider,
        JAVA_DEPENDENT_ATTRIBUTES);
  }

  private ConfiguredAspect proto(
      ConfiguredTarget base, RuleContext ruleContext, AspectParameters parameters)
      throws InterruptedException {
    ProtoSourcesProvider protoSourcesProvider = base.getProvider(ProtoSourcesProvider.class);
    ImmutableList<Artifact> protoSources = protoSourcesProvider.getDirectProtoSources();

    // Avoid pulling in any generated files from blacklisted protos.
    ProtoSourceFileBlacklist protoBlacklist =
        new ProtoSourceFileBlacklist(
            ruleContext,
            ruleContext
                .getPrerequisiteArtifacts(PROTO_SOURCE_FILE_BLACKLIST_ATTR, Mode.HOST)
                .list());
    ImmutableList<Artifact> filteredProtoSources =
        ImmutableList.copyOf(protoBlacklist.filter(protoSources));
    J2ObjcSource j2ObjcSource = protoJ2ObjcSource(ruleContext, filteredProtoSources);

    J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider;
    if (Iterables.isEmpty(j2ObjcSource.getObjcSrcs())) {
      directJ2ObjcMappingFileProvider = new J2ObjcMappingFileProvider.Builder().build();
    } else {
      directJ2ObjcMappingFileProvider =
          createJ2ObjcProtoCompileActions(base, ruleContext, filteredProtoSources, j2ObjcSource);
    }

    return buildAspect(
        base,
        ruleContext,
        parameters,
        j2ObjcSource,
        directJ2ObjcMappingFileProvider,
        PROTO_DEPENDENT_ATTRIBUTES);
  }

  private static J2ObjcMappingFileProvider exportedJ2ObjcMappingFileProvider(
      ConfiguredTarget base,
      RuleContext ruleContext,
      J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider) {
    J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider =
        depJ2ObjcMappingFileProvider(ruleContext);

    NestedSetBuilder<Artifact> exportedHeaderMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getHeaderMappingFiles());

    NestedSetBuilder<Artifact> exportedClassMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getClassMappingFiles())
            .addTransitive(depJ2ObjcMappingFileProvider.getClassMappingFiles());

    NestedSetBuilder<Artifact> exportedDependencyMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getDependencyMappingFiles())
            .addTransitive(depJ2ObjcMappingFileProvider.getDependencyMappingFiles());

    NestedSetBuilder<Artifact> archiveSourceMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getArchiveSourceMappingFiles())
            .addTransitive(depJ2ObjcMappingFileProvider.getArchiveSourceMappingFiles());

    // J2ObjC merges all transitive input header mapping files into one header mapping file,
    // so we only need to re-export other dependent output header mapping files in proto rules and
    // rules where J2ObjC is not run (e.g., no sources).
    if (isProtoRule(base) || exportedHeaderMappingFiles.isEmpty()) {
      exportedHeaderMappingFiles.addTransitive(
          depJ2ObjcMappingFileProvider.getHeaderMappingFiles());
    }

    return new J2ObjcMappingFileProvider(
        exportedHeaderMappingFiles.build(),
        exportedClassMappingFiles.build(),
        exportedDependencyMappingFiles.build(),
        archiveSourceMappingFiles.build());
  }

  private static J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> depsHeaderMappingsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> depsClassMappingsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> depsDependencyMappingsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> depsArchiveSourceMappingsBuilder = NestedSetBuilder.stableOrder();

    for (J2ObjcMappingFileProvider mapping : getJ2ObjCMappings(ruleContext)) {
      depsHeaderMappingsBuilder.addTransitive(mapping.getHeaderMappingFiles());
      depsClassMappingsBuilder.addTransitive(mapping.getClassMappingFiles());
      depsDependencyMappingsBuilder.addTransitive(mapping.getDependencyMappingFiles());
      depsArchiveSourceMappingsBuilder.addTransitive(mapping.getArchiveSourceMappingFiles());
    }

    return new J2ObjcMappingFileProvider(
        depsHeaderMappingsBuilder.build(),
        depsClassMappingsBuilder.build(),
        depsDependencyMappingsBuilder.build(),
        depsArchiveSourceMappingsBuilder.build());
  }

  private static List<Artifact> sourceJarOutputs(RuleContext ruleContext) {
    return ImmutableList.of(
        j2ObjcSourceJarTranslatedSourceFiles(ruleContext),
        j2objcSourceJarTranslatedHeaderFiles(ruleContext));
  }

  private static List<String> sourceJarFlags(RuleContext ruleContext) {
    return ImmutableList.of(
        "--output_gen_source_dir",
        j2ObjcSourceJarTranslatedSourceFiles(ruleContext).getExecPathString(),
        "--output_gen_header_dir",
        j2objcSourceJarTranslatedHeaderFiles(ruleContext).getExecPathString());
  }

  private static J2ObjcMappingFileProvider createJ2ObjcTranspilationAction(
      RuleContext ruleContext,
      Iterable<Artifact> sources,
      Iterable<Artifact> sourceJars,
      J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider,
      JavaCompilationArgsProvider compArgsProvider,
      J2ObjcSource j2ObjcSource) {
    CustomCommandLine.Builder argBuilder = CustomCommandLine.builder();
    PathFragment javaExecutable = ruleContext.getFragment(Jvm.class, HOST).getJavaExecutable();
    argBuilder.add("--java").add(javaExecutable.getPathString());

    Artifact j2ObjcDeployJar = ruleContext.getPrerequisiteArtifact("$j2objc", Mode.HOST);
    argBuilder.addExecPath("--j2objc", j2ObjcDeployJar);

    argBuilder.add("--main_class").add("com.google.devtools.j2objc.J2ObjC");
    argBuilder.add("--objc_file_path").addPath(j2ObjcSource.getObjcFilePath());

    Artifact outputDependencyMappingFile = j2ObjcOutputDependencyMappingFile(ruleContext);
    argBuilder.addExecPath("--output_dependency_mapping_file", outputDependencyMappingFile);

    ImmutableList.Builder<Artifact> sourceJarOutputFiles = ImmutableList.builder();
    if (!Iterables.isEmpty(sourceJars)) {
      sourceJarOutputFiles.addAll(sourceJarOutputs(ruleContext));
      argBuilder.addJoinExecPaths("--src_jars", ",", sourceJars);
      argBuilder.add(sourceJarFlags(ruleContext));
    }

    Iterable<String> translationFlags = ruleContext
        .getFragment(J2ObjcConfiguration.class)
        .getTranslationFlags();
    argBuilder.add(translationFlags);

    NestedSet<Artifact> depsHeaderMappingFiles =
        depJ2ObjcMappingFileProvider.getHeaderMappingFiles();
    if (!depsHeaderMappingFiles.isEmpty()) {
      argBuilder.addJoinExecPaths("--header-mapping", ",", depsHeaderMappingFiles);
    }

    Artifact outputHeaderMappingFile = j2ObjcOutputHeaderMappingFile(ruleContext);
    argBuilder.addExecPath("--output-header-mapping", outputHeaderMappingFile);

    NestedSet<Artifact> depsClassMappingFiles = depJ2ObjcMappingFileProvider.getClassMappingFiles();
    if (!depsClassMappingFiles.isEmpty()) {
      argBuilder.addJoinExecPaths("--mapping", ",", depsClassMappingFiles);
    }

    Artifact archiveSourceMappingFile = j2ObjcOutputArchiveSourceMappingFile(ruleContext);
    argBuilder.addExecPath("--output_archive_source_mapping_file", archiveSourceMappingFile);

    Artifact compiledLibrary = ObjcRuleClasses.j2objcIntermediateArtifacts(ruleContext).archive();
    argBuilder.addExecPath("--compiled_archive_file_path", compiledLibrary);

    Artifact bootclasspathJar = ruleContext.getPrerequisiteArtifact("$jre_emul_jar", Mode.HOST);
    argBuilder.add("-Xbootclasspath:" + bootclasspathJar.getExecPathString());

    argBuilder.add("-d").addPath(j2ObjcSource.getObjcFilePath());

    // In J2ObjC, the jars you pass as dependencies must be precisely the same as the
    // jars used to transpile those dependencies--we cannot use ijars here.
    NestedSet<Artifact> compileTimeJars =
        compArgsProvider.getRecursiveJavaCompilationArgs().getRuntimeJars();
    if (!compileTimeJars.isEmpty()) {
      argBuilder.addJoinExecPaths("-classpath", ":", compileTimeJars);
    }

    argBuilder.addExecPaths(sources);

    Artifact paramFile = j2ObjcOutputParamFile(ruleContext);
    ruleContext.registerAction(new ParameterFileWriteAction(
        ruleContext.getActionOwner(),
        paramFile,
        argBuilder.build(),
        ParameterFile.ParameterFileType.UNQUOTED,
        ISO_8859_1));

    SpawnAction.Builder builder = new SpawnAction.Builder()
        .setMnemonic("TranspilingJ2objc")
        .setExecutable(ruleContext.getPrerequisiteArtifact("$j2objc_wrapper", Mode.HOST))
        .addInput(ruleContext.getPrerequisiteArtifact("$j2objc_wrapper", Mode.HOST))
        .addInput(j2ObjcDeployJar)
        .addInput(bootclasspathJar)
        .addInputs(sources)
        .addInputs(sourceJars)
        .addTransitiveInputs(compileTimeJars)
        .addTransitiveInputs(JavaHelper.getHostJavabaseInputs(ruleContext))
        .addTransitiveInputs(depsHeaderMappingFiles)
        .addTransitiveInputs(depsClassMappingFiles)
        .addInput(paramFile)
        .setCommandLine(CustomCommandLine.builder()
            .addPaths("@%s", paramFile.getExecPath())
            .build())
        .addOutputs(j2ObjcSource.getObjcSrcs())
        .addOutputs(j2ObjcSource.getObjcHdrs())
        .addOutput(outputHeaderMappingFile)
        .addOutput(outputDependencyMappingFile)
        .addOutput(archiveSourceMappingFile);

    ruleContext.registerAction(builder.build(ruleContext));

    return new J2ObjcMappingFileProvider(
        NestedSetBuilder.<Artifact>stableOrder().add(outputHeaderMappingFile).build(),
        NestedSetBuilder.<Artifact>stableOrder().build(),
        NestedSetBuilder.<Artifact>stableOrder().add(outputDependencyMappingFile).build(),
        NestedSetBuilder.<Artifact>stableOrder().add(archiveSourceMappingFile).build());
  }

  private J2ObjcMappingFileProvider createJ2ObjcProtoCompileActions(
      ConfiguredTarget base,
      RuleContext ruleContext,
      Iterable<Artifact> filteredProtoSources,
      J2ObjcSource j2ObjcSource) {
    Iterable<Artifact> outputHeaderMappingFiles =
        ProtoCommon.getGeneratedOutputs(
            ruleContext, ImmutableList.copyOf(filteredProtoSources), ".j2objc.mapping");
    Iterable<Artifact> outputClassMappingFiles =
        ProtoCommon.getGeneratedOutputs(
            ruleContext, ImmutableList.copyOf(filteredProtoSources), ".clsmap.properties");
    ImmutableList<Artifact> outputs =
        ImmutableList.<Artifact>builder()
            .addAll(j2ObjcSource.getObjcSrcs())
            .addAll(j2ObjcSource.getObjcHdrs())
            .addAll(outputHeaderMappingFiles)
            .addAll(outputClassMappingFiles)
            .build();

    String langPluginParameter =
        String.format(
            "%s:%s",
            Joiner.on(',').join(J2OBJC_PLUGIN_PARAMS),
            ruleContext.getConfiguration().getGenfilesFragment().getPathString());

    SupportData supportData = base.getProvider(ProtoSupportDataProvider.class).getSupportData();

    ProtoCompileActionBuilder actionBuilder =
        new ProtoCompileActionBuilder(ruleContext, supportData, "J2ObjC", "j2objc", outputs)
            .setLangPluginName("$j2objc_plugin")
            .setLangPluginParameter(langPluginParameter)
            .allowServices(shouldAllowProtoServices(ruleContext));
    ruleContext.registerAction(actionBuilder.build());

    return new J2ObjcMappingFileProvider(
        NestedSetBuilder.<Artifact>stableOrder().addAll(outputHeaderMappingFiles).build(),
        NestedSetBuilder.<Artifact>stableOrder().addAll(outputClassMappingFiles).build(),
        NestedSetBuilder.<Artifact>stableOrder().build(),
        NestedSetBuilder.<Artifact>stableOrder().build());
  }

  private static List<? extends J2ObjcMappingFileProvider> getJ2ObjCMappings(RuleContext context) {
    ImmutableList.Builder<J2ObjcMappingFileProvider> mappingFileProviderBuilder =
        new ImmutableList.Builder<>();
    addJ2ObjCMappingsForAttribute(mappingFileProviderBuilder, context, "deps");
    addJ2ObjCMappingsForAttribute(mappingFileProviderBuilder, context, "runtime_deps");
    addJ2ObjCMappingsForAttribute(mappingFileProviderBuilder, context, "exports");
    return mappingFileProviderBuilder.build();
  }

  private static void addJ2ObjCMappingsForAttribute(
      ImmutableList.Builder<J2ObjcMappingFileProvider> builder, RuleContext context,
      String attributeName) {
    if (context.attributes().has(attributeName, BuildType.LABEL_LIST)) {
      for (TransitiveInfoCollection dependencyInfoDatum :
          context.getPrerequisites(attributeName, Mode.TARGET)) {
        J2ObjcMappingFileProvider provider =
            dependencyInfoDatum.getProvider(J2ObjcMappingFileProvider.class);
        if (provider != null) {
          builder.add(provider);
        }
      }
    }
  }

  private static Artifact j2ObjcOutputHeaderMappingFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, ".mapping.j2objc");
  }

  private static Artifact j2ObjcOutputDependencyMappingFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, ".dependency_mapping.j2objc");
  }

  private static Artifact j2ObjcOutputParamFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, ".param.j2objc");
  }

  private static Artifact j2ObjcOutputArchiveSourceMappingFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(
        ruleContext, ".archive_source_mapping.j2objc");
  }

  private static Artifact j2ObjcSourceJarTranslatedSourceFiles(RuleContext ruleContext) {
    PathFragment rootRelativePath = ruleContext
        .getUniqueDirectory("_j2objc/src_jar_files")
        .getRelative("source_files");
    return ruleContext.getTreeArtifact(rootRelativePath, ruleContext.getBinOrGenfilesDirectory());
  }

  private static Artifact j2objcSourceJarTranslatedHeaderFiles(RuleContext ruleContext) {
    PathFragment rootRelativePath = ruleContext
        .getUniqueDirectory("_j2objc/src_jar_files")
        .getRelative("header_files");
    return ruleContext.getTreeArtifact(rootRelativePath, ruleContext.getBinOrGenfilesDirectory());
  }

  private static J2ObjcSource javaJ2ObjcSource(
      RuleContext ruleContext,
      Iterable<Artifact> javaInputSourceFiles,
      Iterable<Artifact> javaSourceJarFiles) {
    PathFragment objcFileRootRelativePath = ruleContext.getUniqueDirectory("_j2objc");
    PathFragment objcFileRootExecPath = ruleContext
        .getConfiguration()
        .getBinFragment()
        .getRelative(objcFileRootRelativePath);
    Iterable<Artifact> objcSrcs = getOutputObjcFiles(ruleContext, javaInputSourceFiles,
        objcFileRootRelativePath, ".m");
    Iterable<Artifact> objcHdrs = getOutputObjcFiles(ruleContext, javaInputSourceFiles,
        objcFileRootRelativePath, ".h");
    Iterable<PathFragment> headerSearchPaths = J2ObjcLibrary.j2objcSourceHeaderSearchPaths(
        ruleContext, objcFileRootExecPath, javaInputSourceFiles);

    Optional<Artifact> sourceJarTranslatedSrcs = Optional.absent();
    Optional<Artifact> sourceJarTranslatedHdrs = Optional.absent();
    Optional<PathFragment> sourceJarFileHeaderSearchPaths = Optional.absent();

    if (!Iterables.isEmpty(javaSourceJarFiles)) {
      sourceJarTranslatedSrcs = Optional.of(j2ObjcSourceJarTranslatedSourceFiles(ruleContext));
      sourceJarTranslatedHdrs = Optional.of(j2objcSourceJarTranslatedHeaderFiles(ruleContext));
      sourceJarFileHeaderSearchPaths = Optional.of(sourceJarTranslatedHdrs.get().getExecPath());
    }

    return new J2ObjcSource(
        ruleContext.getRule().getLabel(),
        Iterables.concat(objcSrcs, sourceJarTranslatedSrcs.asSet()),
        Iterables.concat(objcHdrs, sourceJarTranslatedHdrs.asSet()),
        objcFileRootExecPath,
        SourceType.JAVA,
        Iterables.concat(headerSearchPaths, sourceJarFileHeaderSearchPaths.asSet()));
  }

  private static J2ObjcSource protoJ2ObjcSource(
      RuleContext ruleContext, ImmutableList<Artifact> protoSources) {
    PathFragment objcFileRootExecPath =
        ruleContext
            .getConfiguration()
            .getGenfilesDirectory(ruleContext.getRule().getRepository())
            .getExecPath();
    Iterable<PathFragment> headerSearchPaths =
        J2ObjcLibrary.j2objcSourceHeaderSearchPaths(
            ruleContext, objcFileRootExecPath, protoSources);

    return new J2ObjcSource(
        ruleContext.getTarget().getLabel(),
        ProtoCommon.getGeneratedOutputs(ruleContext, protoSources, ".j2objc.pb.m"),
        ProtoCommon.getGeneratedOutputs(ruleContext, protoSources, ".j2objc.pb.h"),
        objcFileRootExecPath,
        SourceType.PROTO,
        headerSearchPaths);
  }

  private static boolean isProtoRule(ConfiguredTarget base) {
    return base.getProvider(ProtoSourcesProvider.class) != null;
  }

  private static Iterable<Artifact> getOutputObjcFiles(
      RuleContext ruleContext,
      Iterable<Artifact> javaSrcs,
      PathFragment objcFileRootRelativePath,
      String suffix) {
    ImmutableList.Builder<Artifact> objcSources = ImmutableList.builder();

    for (Artifact javaSrc : javaSrcs) {
      objcSources.add(ruleContext.getRelatedArtifact(
          objcFileRootRelativePath.getRelative(javaSrc.getExecPath()), suffix));
    }

    return objcSources.build();
  }

  /**
   * Sets up and returns an {@link ObjcCommon} object containing the J2ObjC-translated code.
   *
   */
  static ObjcCommon common(RuleContext ruleContext, Iterable<Artifact> transpiledSources,
      Iterable<Artifact> transpiledHeaders, Iterable<PathFragment> headerSearchPaths,
      Iterable<Attribute> dependentAttributes) {
    ObjcCommon.Builder builder = new ObjcCommon.Builder(ruleContext);
    IntermediateArtifacts intermediateArtifacts =
        ObjcRuleClasses.j2objcIntermediateArtifacts(ruleContext);

    if (!Iterables.isEmpty(transpiledSources) || !Iterables.isEmpty(transpiledHeaders)) {
      CompilationArtifacts compilationArtifacts = new CompilationArtifacts.Builder()
          .addNonArcSrcs(transpiledSources)
          .setIntermediateArtifacts(intermediateArtifacts)
          .setPchFile(Optional.<Artifact>absent())
          .addAdditionalHdrs(transpiledHeaders)
          .build();
      builder.setCompilationArtifacts(compilationArtifacts);
      builder.setHasModuleMap();
    }

    for (Attribute dependentAttribute : dependentAttributes) {
      if (ruleContext.attributes().has(dependentAttribute.getName(), BuildType.LABEL_LIST)
          || ruleContext.attributes().has(dependentAttribute.getName(), BuildType.LABEL)) {
        builder.addDepObjcProviders(ruleContext.getPrerequisites(
            dependentAttribute.getName(),
            dependentAttribute.getAccessMode(),
            ObjcProvider.class));
      }
    }

    return builder
        .addUserHeaderSearchPaths(headerSearchPaths)
        .setIntermediateArtifacts(intermediateArtifacts)
        .build();
  }

  /**
   * Sets up and returns an {@link XcodeProvider} object containing the J2ObjC-translated code.
   *
   */
  static XcodeProvider xcodeProvider(RuleContext ruleContext, ObjcCommon common,
      Iterable<Artifact> transpiledHeaders, Iterable<PathFragment> headerSearchPaths,
      Iterable<Attribute> dependentAttributes) {
    XcodeProvider.Builder xcodeProviderBuilder = new XcodeProvider.Builder();
    XcodeSupport xcodeSupport = new XcodeSupport(ruleContext);
    xcodeSupport.addXcodeSettings(xcodeProviderBuilder, common.getObjcProvider(), LIBRARY_STATIC);

    for (Attribute dependentAttribute : dependentAttributes) {
      if (ruleContext.attributes().has(dependentAttribute.getName(), BuildType.LABEL_LIST)
          || ruleContext.attributes().has(dependentAttribute.getName(), BuildType.LABEL)) {
        xcodeSupport.addDependencies(xcodeProviderBuilder, dependentAttribute);
      }
    }

    if (!Iterables.isEmpty(transpiledHeaders)) {
      xcodeProviderBuilder
          .addUserHeaderSearchPaths(headerSearchPaths)
          .addCopts(ruleContext.getFragment(ObjcConfiguration.class).getCopts())
          .addHeaders(transpiledHeaders);
    }

    if (common.getCompilationArtifacts().isPresent()) {
      xcodeProviderBuilder.setCompilationArtifacts(common.getCompilationArtifacts().get());
    }

    return xcodeProviderBuilder.build();
  }
}
