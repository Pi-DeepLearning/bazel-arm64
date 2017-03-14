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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.rules.cpp.Link.LINK_LIBRARY_FILETYPES;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.ASSET_CATALOG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.BUNDLE_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.BUNDLE_IMPORT_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.CC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DEBUG_SYMBOLS;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DEBUG_SYMBOLS_PLIST;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DEFINE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DYNAMIC_FRAMEWORK_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DYNAMIC_FRAMEWORK_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FLAG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FORCE_LOAD_FOR_XCODEGEN;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FORCE_LOAD_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_CPP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.GENERAL_RESOURCE_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.GENERAL_RESOURCE_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.IMPORTED_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE_SYSTEM;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.J2OBJC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKED_BINARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKMAP_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LINKOPT;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_DYLIB;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SOURCE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STATIC_FRAMEWORK_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STATIC_FRAMEWORK_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STORYBOARD;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STRINGS;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.TOP_LEVEL_MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.WEAK_SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XCASSETS_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XCDATAMODEL;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XIB;
import static com.google.devtools.build.lib.vfs.PathFragment.TO_PATH_FRAGMENT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.UnmodifiableIterator;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.cpp.CcLinkParams;
import com.google.devtools.build.lib.rules.cpp.CcLinkParamsProvider;
import com.google.devtools.build.lib.rules.cpp.CppCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CppModuleMap;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contains information common to multiple objc_* rules, and provides a unified API for extracting
 * and accessing it.
 */
// TODO(bazel-team): Decompose and subsume area-specific logic and data into the various *Support
// classes. Make sure to distinguish rule output (providers, runfiles, ...) from intermediate,
// rule-internal information. Any provider created by a rule should not be read, only published.
public final class ObjcCommon {
  /**
   * Provides a way to access attributes that are common to all resources rules.
   */
  // TODO(bazel-team): Delete and move into support-specific attributes classes once ObjcCommon is
  // gone.
  static final class ResourceAttributes {
    private final RuleContext ruleContext;

    ResourceAttributes(RuleContext ruleContext) {
      this.ruleContext = ruleContext;
    }

    ImmutableList<Artifact> strings() {
      return ruleContext.getPrerequisiteArtifacts("strings", Mode.TARGET).list();
    }

    ImmutableList<Artifact> xibs() {
      return ruleContext.getPrerequisiteArtifacts("xibs", Mode.TARGET).list();
    }

    ImmutableList<Artifact> storyboards() {
      return ruleContext.getPrerequisiteArtifacts("storyboards", Mode.TARGET).list();
    }

    ImmutableList<Artifact> resources() {
      return ruleContext.getPrerequisiteArtifacts("resources", Mode.TARGET).list();
    }

    ImmutableList<Artifact> structuredResources() {
      return ruleContext.getPrerequisiteArtifacts("structured_resources", Mode.TARGET).list();
    }

    ImmutableList<Artifact> datamodels() {
      return ruleContext.getPrerequisiteArtifacts("datamodels", Mode.TARGET).list();
    }

    ImmutableList<Artifact> assetCatalogs() {
      return ruleContext.getPrerequisiteArtifacts("asset_catalogs", Mode.TARGET).list();
    }
  }

  static class Builder {
    private final RuleContext context;
    private final BuildConfiguration buildConfiguration;
    private Optional<CompilationAttributes> compilationAttributes = Optional.absent();
    private Optional<ResourceAttributes> resourceAttributes = Optional.absent();
    private Iterable<SdkFramework> extraSdkFrameworks = ImmutableList.of();
    private Iterable<SdkFramework> extraWeakSdkFrameworks = ImmutableList.of();
    private Iterable<String> extraSdkDylibs = ImmutableList.of();
    private Iterable<Artifact> staticFrameworkImports = ImmutableList.of();
    private Iterable<Artifact> dynamicFrameworkImports = ImmutableList.of();
    private Optional<CompilationArtifacts> compilationArtifacts = Optional.absent();
    private Iterable<ObjcProvider> depObjcProviders = ImmutableList.of();
    private Iterable<ObjcProvider> directDepObjcProviders = ImmutableList.of();
    private Iterable<ObjcProvider> runtimeDepObjcProviders = ImmutableList.of();
    private Iterable<String> defines = ImmutableList.of();
    private Iterable<PathFragment> userHeaderSearchPaths = ImmutableList.of();
    private Iterable<PathFragment> directDependencyHeaderSearchPaths = ImmutableList.of();
    private IntermediateArtifacts intermediateArtifacts;
    private boolean alwayslink;
    private boolean hasModuleMap;
    private Iterable<Artifact> extraImportLibraries = ImmutableList.of();
    private DsymOutputType dsymOutputType;
    private Optional<Artifact> linkedBinary = Optional.absent();
    private Optional<Artifact> linkmapFile = Optional.absent();
    private Iterable<CppCompilationContext> depCcHeaderProviders = ImmutableList.of();
    private Iterable<CcLinkParamsProvider> depCcLinkProviders = ImmutableList.of();

    /**
     * Builder for {@link ObjcCommon} obtaining both attribute data and configuration data from
     * the given rule context.
     */
    Builder(RuleContext context) {
      this(context, context.getConfiguration());
    }

    /**
     * Builder for {@link ObjcCommon} obtaining attribute data from the rule context and
     * configuration data from the given configuration object for use in situations where a single
     * target's outputs are under multiple configurations.
     */
    Builder(RuleContext context, BuildConfiguration buildConfiguration) {
      this.context = Preconditions.checkNotNull(context);
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public Builder setCompilationAttributes(CompilationAttributes baseCompilationAttributes) {
      Preconditions.checkState(
          !this.compilationAttributes.isPresent(),
          "compilationAttributes is already set to: %s",
          this.compilationAttributes);
      this.compilationAttributes = Optional.of(baseCompilationAttributes);
      return this;
    }

    public Builder setResourceAttributes(ResourceAttributes baseResourceAttributes) {
      Preconditions.checkState(
          !this.resourceAttributes.isPresent(),
          "resourceAttributes is already set to: %s",
          this.resourceAttributes);
      this.resourceAttributes = Optional.of(baseResourceAttributes);
      return this;
    }

    Builder addExtraSdkFrameworks(Iterable<SdkFramework> extraSdkFrameworks) {
      this.extraSdkFrameworks = Iterables.concat(this.extraSdkFrameworks, extraSdkFrameworks);
      return this;
    }

    Builder addExtraWeakSdkFrameworks(Iterable<SdkFramework> extraWeakSdkFrameworks) {
      this.extraWeakSdkFrameworks =
          Iterables.concat(this.extraWeakSdkFrameworks, extraWeakSdkFrameworks);
      return this;
    }

    Builder addExtraSdkDylibs(Iterable<String> extraSdkDylibs) {
      this.extraSdkDylibs = Iterables.concat(this.extraSdkDylibs, extraSdkDylibs);
      return this;
    }

    /**
     * Adds all given artifacts as members of static frameworks. They must be contained in
     * {@code .frameworks} directories and the binary in that framework should be statically linked.
     */
    Builder addStaticFrameworkImports(Iterable<Artifact> frameworkImports) {
      this.staticFrameworkImports = Iterables.concat(this.staticFrameworkImports, frameworkImports);
      return this;
    }

    /**
     * Adds all given artifacts as members of dynamic frameworks. They must be contained in
     * {@code .frameworks} directories and the binary in that framework should be dynamically
     * linked.
     */
    Builder addDynamicFrameworkImports(Iterable<Artifact> frameworkImports) {
      this.dynamicFrameworkImports =
          Iterables.concat(this.dynamicFrameworkImports, frameworkImports);
      return this;
    }

    Builder setCompilationArtifacts(CompilationArtifacts compilationArtifacts) {
      Preconditions.checkState(
          !this.compilationArtifacts.isPresent(),
          "compilationArtifacts is already set to: %s",
          this.compilationArtifacts);
      this.compilationArtifacts = Optional.of(compilationArtifacts);
      return this;
    }

    Builder addDeps(List<? extends TransitiveInfoCollection> deps) {
      ImmutableList.Builder<ObjcProvider> propagatedObjcDeps =
          ImmutableList.<ObjcProvider>builder();
      ImmutableList.Builder<CppCompilationContext> cppDeps =
          ImmutableList.<CppCompilationContext>builder();
      ImmutableList.Builder<CcLinkParamsProvider> cppDepLinkParams =
          ImmutableList.<CcLinkParamsProvider>builder();

      for (TransitiveInfoCollection dep : deps) {
        addAnyProviders(propagatedObjcDeps, dep, ObjcProvider.class);
        addAnyProviders(cppDeps, dep, CppCompilationContext.class);
        if (isCcLibrary(dep)) {
          cppDepLinkParams.add(dep.getProvider(CcLinkParamsProvider.class));
          addDefines(dep.getProvider(CppCompilationContext.class).getDefines());
        }
      }
      addDepObjcProviders(propagatedObjcDeps.build());
      this.depCcHeaderProviders = Iterables.concat(this.depCcHeaderProviders, cppDeps.build());
      this.depCcLinkProviders = Iterables.concat(this.depCcLinkProviders, cppDepLinkParams.build());
      return this;
    }

    /**
     * Adds providers for runtime frameworks included in the final app bundle but not linked with
     * at build time.
     */
    Builder addRuntimeDeps(List<? extends TransitiveInfoCollection> runtimeDeps) {
      ImmutableList.Builder<ObjcProvider> propagatedDeps =
          ImmutableList.<ObjcProvider>builder();

      for (TransitiveInfoCollection dep : runtimeDeps) {
        addAnyProviders(propagatedDeps, dep, ObjcProvider.class);
      }
      this.runtimeDepObjcProviders = Iterables.concat(
          this.runtimeDepObjcProviders, propagatedDeps.build());
      return this;
    }

    private <T extends TransitiveInfoProvider> ImmutableList.Builder<T> addAnyProviders(
        ImmutableList.Builder<T> listBuilder,
        TransitiveInfoCollection collection,
        Class<T> providerClass) {
      if (collection.getProvider(providerClass) != null) {
        listBuilder.add(collection.getProvider(providerClass));
      }
      return listBuilder;
    }

    /**
     * Add providers which will be exposed both to the declaring rule and to any dependers on the
     * declaring rule.
     */
    Builder addDepObjcProviders(Iterable<ObjcProvider> depObjcProviders) {
      this.depObjcProviders = Iterables.concat(this.depObjcProviders, depObjcProviders);
      return this;
    }

    /**
     * Add providers which will only be used by the declaring rule, and won't be propagated to any
     * dependers on the declaring rule.
     */
    Builder addNonPropagatedDepObjcProviders(Iterable<ObjcProvider> directDepObjcProviders) {
      this.directDepObjcProviders =
          Iterables.concat(this.directDepObjcProviders, directDepObjcProviders);
      return this;
    }

    public Builder addUserHeaderSearchPaths(Iterable<PathFragment> userHeaderSearchPaths) {
      this.userHeaderSearchPaths =
          Iterables.concat(this.userHeaderSearchPaths, userHeaderSearchPaths);
      return this;
    }

    /**
     * Adds header search paths that will only be visible by strict dependents of the provider.
     */
    public Builder addDirectDependencyHeaderSearchPaths(
        Iterable<PathFragment> directDependencyHeaderSearchPaths) {
      this.directDependencyHeaderSearchPaths =
          Iterables.concat(
              this.directDependencyHeaderSearchPaths, directDependencyHeaderSearchPaths);
      return this;
    }

    public Builder addDefines(Iterable<String> defines) {
      this.defines = Iterables.concat(this.defines, defines);
      return this;
    }

    Builder setIntermediateArtifacts(IntermediateArtifacts intermediateArtifacts) {
      this.intermediateArtifacts = intermediateArtifacts;
      return this;
    }

    Builder setAlwayslink(boolean alwayslink) {
      this.alwayslink = alwayslink;
      return this;
    }

    /**
     * Specifies that this target has a clang module map. This should be called if this target
     * compiles sources or exposes headers for other targets to use. Note that this does not add
     * the action to generate the module map. It simply indicates that it should be added to the
     * provider.
     */
    Builder setHasModuleMap() {
      this.hasModuleMap = true;
      return this;
    }

    /**
     * Adds additional static libraries to be linked into the final ObjC application bundle.
     */
    Builder addExtraImportLibraries(Iterable<Artifact> extraImportLibraries) {
      this.extraImportLibraries = Iterables.concat(this.extraImportLibraries, extraImportLibraries);
      return this;
    }

    /**
     * Sets a linked binary generated by this rule to be propagated to dependers.
     */
    Builder setLinkedBinary(Artifact linkedBinary) {
      this.linkedBinary = Optional.of(linkedBinary);
      return this;
    }

    /**
     * Sets which type of dsym output this rule generated to be propagated to dependers.
     */
    Builder addDebugArtifacts(DsymOutputType dsymOutputType) {
      this.dsymOutputType = dsymOutputType;
      return this;
    }

    /**
     * Sets a linkmap file generated by this rule to be propagated to dependers.
     */
    Builder setLinkmapFile(Artifact linkmapFile) {
      this.linkmapFile = Optional.of(linkmapFile);
      return this;
    }

    ObjcCommon build() {
      Iterable<BundleableFile> bundleImports = BundleableFile.bundleImportsFromRule(context);

      ObjcProvider.Builder objcProvider =
          new ObjcProvider.Builder()
              .addAll(IMPORTED_LIBRARY, extraImportLibraries)
              .addAll(BUNDLE_FILE, bundleImports)
              .addAll(
                  BUNDLE_IMPORT_DIR,
                  uniqueContainers(
                      BundleableFile.toArtifacts(bundleImports), BUNDLE_CONTAINER_TYPE))
              .addAll(SDK_FRAMEWORK, extraSdkFrameworks)
              .addAll(WEAK_SDK_FRAMEWORK, extraWeakSdkFrameworks)
              .addAll(SDK_DYLIB, extraSdkDylibs)
              .addAll(STATIC_FRAMEWORK_FILE, staticFrameworkImports)
              .addAll(DYNAMIC_FRAMEWORK_FILE, dynamicFrameworkImports)
              .addAll(STATIC_FRAMEWORK_DIR,
                  uniqueContainers(staticFrameworkImports, FRAMEWORK_CONTAINER_TYPE))
              .addAll(DYNAMIC_FRAMEWORK_DIR,
                  uniqueContainers(dynamicFrameworkImports, FRAMEWORK_CONTAINER_TYPE))
              .addAll(INCLUDE, userHeaderSearchPaths)
              .addAllForDirectDependents(INCLUDE, directDependencyHeaderSearchPaths)
              .addAll(DEFINE, defines)
              .addTransitiveAndPropagate(depObjcProviders)
              .addTransitiveWithoutPropagating(directDepObjcProviders);

      for (ObjcProvider provider : runtimeDepObjcProviders) {
        objcProvider.addTransitiveAndPropagate(ObjcProvider.DYNAMIC_FRAMEWORK_FILE, provider);
        // TODO(b/28637288): Remove STATIC_FRAMEWORK_FILE and MERGE_ZIP when they are
        // no longer provided by ios_framework.
        objcProvider.addTransitiveAndPropagate(ObjcProvider.STATIC_FRAMEWORK_FILE, provider);
        objcProvider.addTransitiveAndPropagate(ObjcProvider.MERGE_ZIP, provider);
      }

      for (CppCompilationContext headerProvider : depCcHeaderProviders) {
        objcProvider.addTransitiveAndPropagate(HEADER, headerProvider.getDeclaredIncludeSrcs());
        objcProvider.addAll(INCLUDE, headerProvider.getIncludeDirs());
        // TODO(bazel-team): This pulls in stl via CppHelper.mergeToolchainDependentContext but
        // probably shouldn't.
        objcProvider.addAll(INCLUDE_SYSTEM, headerProvider.getSystemIncludeDirs());
        objcProvider.addAll(DEFINE, headerProvider.getDefines());
      }
      for (CcLinkParamsProvider linkProvider : depCcLinkProviders) {
        CcLinkParams params = linkProvider.getCcLinkParams(true, false);
        ImmutableList<String> linkOpts = params.flattenedLinkopts();
        ImmutableSet.Builder<SdkFramework> frameworkLinkOpts = new ImmutableSet.Builder<>();
        ImmutableList.Builder<String> nonFrameworkLinkOpts = new ImmutableList.Builder<>();
        // Add any framework flags as frameworks directly, rather than as linkopts.
        for (UnmodifiableIterator<String> iterator = linkOpts.iterator(); iterator.hasNext(); ) {
          String arg = iterator.next();
          if (arg.equals("-framework") && iterator.hasNext()) {
            String framework = iterator.next();
            frameworkLinkOpts.add(new SdkFramework(framework));
          } else {
            nonFrameworkLinkOpts.add(arg);
          }
        }

        objcProvider
            .addAll(SDK_FRAMEWORK, frameworkLinkOpts.build())
            .addAll(LINKOPT, nonFrameworkLinkOpts.build())
            .addTransitiveAndPropagate(CC_LIBRARY, params.getLibraries());

        for (LinkerInputs.LibraryToLink library : params.getLibraries()) {
          Artifact artifact = library.getArtifact();
          if (LINK_LIBRARY_FILETYPES.matches(artifact.getFilename())) {
            objcProvider.add(
                FORCE_LOAD_FOR_XCODEGEN,
                "$(WORKSPACE_ROOT)/" + artifact.getExecPath().getSafePathString());
          }
        }
      }

      if (compilationAttributes.isPresent()) {
        CompilationAttributes attributes = compilationAttributes.get();
        Iterable<PathFragment> sdkIncludes =
            Iterables.transform(
                Interspersing.prependEach(
                    AppleToolchain.sdkDir() + "/usr/include/",
                    PathFragment.safePathStrings(attributes.sdkIncludes())),
                TO_PATH_FRAGMENT);
        objcProvider
            .addAll(HEADER, attributes.hdrs())
            .addAll(HEADER, attributes.textualHdrs())
            .addAll(INCLUDE, attributes.headerSearchPaths(buildConfiguration.getGenfilesFragment()))
            .addAll(INCLUDE, sdkIncludes)
            .addAll(SDK_FRAMEWORK, attributes.sdkFrameworks())
            .addAll(WEAK_SDK_FRAMEWORK, attributes.weakSdkFrameworks())
            .addAll(SDK_DYLIB, attributes.sdkDylibs());
      }

      if (resourceAttributes.isPresent()) {
        ResourceAttributes attributes = resourceAttributes.get();
        objcProvider
            .addAll(GENERAL_RESOURCE_FILE, attributes.storyboards())
            .addAll(GENERAL_RESOURCE_FILE, attributes.resources())
            .addAll(GENERAL_RESOURCE_FILE, attributes.strings())
            .addAll(GENERAL_RESOURCE_FILE, attributes.xibs())
            .addAll(
                GENERAL_RESOURCE_DIR, xcodeStructuredResourceDirs(attributes.structuredResources()))
            .addAll(BUNDLE_FILE, BundleableFile.flattenedRawResourceFiles(attributes.resources()))
            .addAll(
                BUNDLE_FILE,
                BundleableFile.structuredRawResourceFiles(attributes.structuredResources()))
            .addAll(
                XCASSETS_DIR,
                uniqueContainers(attributes.assetCatalogs(), ASSET_CATALOG_CONTAINER_TYPE))
            .addAll(ASSET_CATALOG, attributes.assetCatalogs())
            .addAll(XCDATAMODEL, attributes.datamodels())
            .addAll(XIB, attributes.xibs())
            .addAll(STRINGS, attributes.strings())
            .addAll(STORYBOARD, attributes.storyboards());
      }

      if (useLaunchStoryboard(context)) {
        Artifact launchStoryboard =
            context.getPrerequisiteArtifact("launch_storyboard", Mode.TARGET);
        objcProvider.add(GENERAL_RESOURCE_FILE, launchStoryboard);
        if (ObjcRuleClasses.STORYBOARD_TYPE.matches(launchStoryboard.getPath())) {
          objcProvider.add(STORYBOARD, launchStoryboard);
        } else {
          objcProvider.add(XIB, launchStoryboard);
        }
      }

      for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
        Iterable<Artifact> allSources =
            Iterables.concat(artifacts.getSrcs(), artifacts.getNonArcSrcs());
        // TODO(bazel-team): Add private headers to the provider when we have module maps to enforce
        // them.
        objcProvider
            .addAll(HEADER, artifacts.getAdditionalHdrs())
            .addAll(LIBRARY, artifacts.getArchive().asSet())
            .addAll(SOURCE, allSources);

        if (artifacts.getArchive().isPresent()
            && J2ObjcLibrary.J2OBJC_SUPPORTED_RULES.contains(context.getRule().getRuleClass())) {
          objcProvider.addAll(J2OBJC_LIBRARY, artifacts.getArchive().asSet());
        }

        boolean usesCpp = false;
        for (Artifact sourceFile :
            Iterables.concat(artifacts.getSrcs(), artifacts.getNonArcSrcs())) {
          usesCpp = usesCpp || ObjcRuleClasses.CPP_SOURCES.matches(sourceFile.getExecPath());
        }

        if (usesCpp) {
          objcProvider.add(FLAG, USES_CPP);
        }
      }

      if (alwayslink) {
        for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
          for (Artifact archive : artifacts.getArchive().asSet()) {
            objcProvider.add(FORCE_LOAD_LIBRARY, archive);
            objcProvider.add(
                FORCE_LOAD_FOR_XCODEGEN,
                String.format(
                    "$(BUILT_PRODUCTS_DIR)/lib%s.a",
                    XcodeProvider.xcodeTargetName(context.getLabel())));
          }
        }
        for (Artifact archive : extraImportLibraries) {
          objcProvider.add(FORCE_LOAD_LIBRARY, archive);
          objcProvider.add(
              FORCE_LOAD_FOR_XCODEGEN,
              "$(WORKSPACE_ROOT)/" + archive.getExecPath().getSafePathString());
        }
      }

      if (hasModuleMap) {
        CppModuleMap moduleMap = intermediateArtifacts.moduleMap();
        objcProvider.add(MODULE_MAP, moduleMap.getArtifact());
        objcProvider.add(TOP_LEVEL_MODULE_MAP, moduleMap);
      }

      objcProvider
          .addAll(LINKED_BINARY, linkedBinary.asSet())
          .addAll(LINKMAP_FILE, linkmapFile.asSet());

      if (dsymOutputType != null) {
        objcProvider
            .add(DEBUG_SYMBOLS, intermediateArtifacts.dsymSymbol(dsymOutputType))
            .add(DEBUG_SYMBOLS_PLIST, intermediateArtifacts.dsymPlist(dsymOutputType));
      }

      return new ObjcCommon(objcProvider.build(), compilationArtifacts);
    }

    private static boolean isCcLibrary(TransitiveInfoCollection info) {
      try {
        ConfiguredTarget target = (ConfiguredTarget) info;
        String targetName = target.getTarget().getTargetKind();
        for (String ruleClassName : ObjcRuleClasses.CompilingRule.ALLOWED_CC_DEPS_RULE_CLASSES) {
          if (targetName.equals(ruleClassName + " rule")) {
            return true;
          }
        }
        return false;
      } catch (Exception e) {
        return false;
      }
    }
    
    /**
     * Returns {@code true} if the given rule context has a launch storyboard set.
     */
    private static boolean useLaunchStoryboard(RuleContext ruleContext) {
      if (!ruleContext.attributes().has("launch_storyboard", LABEL)) {
        return false;
      }
      Artifact launchStoryboard =
          ruleContext.getPrerequisiteArtifact("launch_storyboard", Mode.TARGET);
      return launchStoryboard != null;
    }
  }

  static final FileType BUNDLE_CONTAINER_TYPE = FileType.of(".bundle");

  static final FileType ASSET_CATALOG_CONTAINER_TYPE = FileType.of(".xcassets");

  public static final FileType FRAMEWORK_CONTAINER_TYPE = FileType.of(".framework");
  private final ObjcProvider objcProvider;

  private final Optional<CompilationArtifacts> compilationArtifacts;

  private ObjcCommon(
      ObjcProvider objcProvider, Optional<CompilationArtifacts> compilationArtifacts) {
    this.objcProvider = Preconditions.checkNotNull(objcProvider);
    this.compilationArtifacts = Preconditions.checkNotNull(compilationArtifacts);
  }

  public ObjcProvider getObjcProvider() {
    return objcProvider;
  }

  public Optional<CompilationArtifacts> getCompilationArtifacts() {
    return compilationArtifacts;
  }

  /**
   * Returns an {@link Optional} containing the compiled {@code .a} file, or
   * {@link Optional#absent()} if this object contains no {@link CompilationArtifacts} or the
   * compilation information has no sources.
   */
  public Optional<Artifact> getCompiledArchive() {
    if (compilationArtifacts.isPresent()) {
      return compilationArtifacts.get().getArchive();
    }
    return Optional.absent();
  }

  /**
   * Returns effective compilation options that do not arise from the crosstool.
   */
  static Iterable<String> getNonCrosstoolCopts(RuleContext ruleContext) {
    return Iterables.concat(
        ruleContext.getFragment(ObjcConfiguration.class).getCopts(),
        ruleContext.getTokenizedStringListAttr("copts"));
  }

  static boolean shouldUseObjcModules(RuleContext ruleContext) {
    for (String copt : getNonCrosstoolCopts(ruleContext)) {
      if (copt.contains("-fmodules")) {
        return true;
      }
    }

    if (ruleContext.attributes().has("enable_modules", Type.BOOLEAN)
        && ruleContext.attributes().get("enable_modules", Type.BOOLEAN)) {
      return true;
    }

    if (ruleContext.getFragment(ObjcConfiguration.class).moduleMapsEnabled()) {
      return true;
    }

    return false;
  }

  static ImmutableList<PathFragment> userHeaderSearchPaths(BuildConfiguration configuration) {
    return ImmutableList.of(new PathFragment("."), configuration.getGenfilesFragment());
  }

  /**
   * Returns the first directory in the sequence of parents of the exec path of the given artifact
   * that matches {@code type}. For instance, if {@code type} is FileType.of(".foo") and the exec
   * path of {@code artifact} is {@code a/b/c/bar.foo/d/e}, then the return value is
   * {@code a/b/c/bar.foo}.
   */
  static Optional<PathFragment> nearestContainerMatching(FileType type, Artifact artifact) {
    PathFragment container = artifact.getExecPath();
    do {
      if (type.matches(container)) {
        return Optional.of(container);
      }
      container = container.getParentDirectory();
    } while (container != null);
    return Optional.absent();
  }

  /**
   * Similar to {@link #nearestContainerMatching(FileType, Artifact)}, but tries matching several
   * file types in {@code types}, and returns a path for the first match in the sequence.
   */
  static Optional<PathFragment> nearestContainerMatching(
      Iterable<FileType> types, Artifact artifact) {
    for (FileType type : types) {
      for (PathFragment container : nearestContainerMatching(type, artifact).asSet()) {
        return Optional.of(container);
      }
    }
    return Optional.absent();
  }

  /**
   * Returns all directories matching {@code containerType} that contain the items in
   * {@code artifacts}. This function ignores artifacts that are not in any directory matching
   * {@code containerType}.
   */
  static Iterable<PathFragment> uniqueContainers(
      Iterable<Artifact> artifacts, FileType containerType) {
    ImmutableSet.Builder<PathFragment> containers = new ImmutableSet.Builder<>();
    for (Artifact artifact : artifacts) {
      containers.addAll(ObjcCommon.nearestContainerMatching(containerType, artifact).asSet());
    }
    return containers.build();
  }

  /**
   * Returns the Xcode structured resource directory paths.
   *
   * <p>For a checked-in source artifact "//a/b/res/sub_dir/d" included by objc rule "//a/b:c",
   * "a/b/res" will be returned. For a generated source artifact "res/sub_dir/d" owned by genrule
   * "//a/b:c", "bazel-out/.../genfiles/a/b/res" will be returned.
   *
   * <p>When XCode sees a included resource directory of "a/b/res", the entire directory structure
   * up to "res" will be copied into the app bundle.
   */
  static Iterable<PathFragment> xcodeStructuredResourceDirs(Iterable<Artifact> artifacts) {
    ImmutableSet.Builder<PathFragment> containers = new ImmutableSet.Builder<>();
    for (Artifact artifact : artifacts) {
      PathFragment ownerRuleDirectory = artifact.getArtifactOwner().getLabel().getPackageFragment();
      String containerName =
          artifact.getRootRelativePath().relativeTo(ownerRuleDirectory).getSegment(0);
      PathFragment rootExecPath = artifact.getRoot().getExecPath();
      containers.add(rootExecPath.getRelative(ownerRuleDirectory.getRelative(containerName)));
    }

    return containers.build();
  }

  /**
   * Similar to {@link #nearestContainerMatching(FileType, Artifact)}, but returns the container
   * closest to the root that matches the given type.
   */
  static Optional<PathFragment> farthestContainerMatching(FileType type, Artifact artifact) {
    PathFragment container = artifact.getExecPath();
    Optional<PathFragment> lastMatch = Optional.absent();
    do {
      if (type.matches(container)) {
        lastMatch = Optional.of(container);
      }
      container = container.getParentDirectory();
    } while (container != null);
    return lastMatch;
  }

  static Iterable<String> notInContainerErrors(
      Iterable<Artifact> artifacts, FileType containerType) {
    return notInContainerErrors(artifacts, ImmutableList.of(containerType));
  }

  @VisibleForTesting
  static final String NOT_IN_CONTAINER_ERROR_FORMAT =
      "File '%s' is not in a directory of one of these type(s): %s";

  static Iterable<String> notInContainerErrors(
      Iterable<Artifact> artifacts, Iterable<FileType> containerTypes) {
    Set<String> errors = new HashSet<>();
    for (Artifact artifact : artifacts) {
      boolean inContainer = nearestContainerMatching(containerTypes, artifact).isPresent();
      if (!inContainer) {
        errors.add(
            String.format(
                NOT_IN_CONTAINER_ERROR_FORMAT,
                artifact.getExecPath(),
                Iterables.toString(containerTypes)));
      }
    }
    return errors;
  }
}
