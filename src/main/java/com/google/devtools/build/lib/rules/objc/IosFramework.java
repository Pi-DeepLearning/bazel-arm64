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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.rules.objc.ObjcCommon.uniqueContainers;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MERGE_ZIP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STATIC_FRAMEWORK_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STATIC_FRAMEWORK_FILE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration.ConfigurationDistinguisher;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.apple.Platform.PlatformType;
import com.google.devtools.build.lib.rules.cpp.CcCommon;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Implementation for {@code ios_framework}.
 */
public class IosFramework extends ReleaseBundlingTargetFactory {

  @VisibleForTesting
  static final DottedVersion MINIMUM_OS_VERSION = DottedVersion.fromString("8.0");

  public IosFramework() {
    super(
        ReleaseBundlingSupport.FRAMEWORK_BUNDLE_DIR_FORMAT,
        XcodeProductType.FRAMEWORK,
        ImmutableSet.of(new Attribute("binary", Mode.SPLIT)),
        ConfigurationDistinguisher.FRAMEWORK);
  }

  @Override
  protected String bundleName(RuleContext ruleContext) {
    String frameworkName = null;

    for (IosFrameworkProvider provider :
        ruleContext.getPrerequisites("binary", Mode.SPLIT, IosFrameworkProvider.class)) {
      frameworkName = provider.getFrameworkName();
    }


    return checkNotNull(frameworkName);
  }

  @Override
  protected DottedVersion bundleMinimumOsVersion(RuleContext ruleContext) {
    // Frameworks are not accepted by Apple below version 8.0. While applications built with a
    // minimum iOS version of less than 8.0 may contain frameworks in their bundle, the framework
    // itself needs to be built with 8.0 or higher. This logic overrides (if necessary) any
    // flag-set minimum iOS version for framework only so that this requirement is not violated.
    DottedVersion fromFlag = ruleContext.getFragment(AppleConfiguration.class)
        .getMinimumOsForPlatformType(PlatformType.IOS);
    return Ordering.natural().max(fromFlag, MINIMUM_OS_VERSION);
  }

  /**
   * Returns a map of original {@code Artifact} to symlinked {@code Artifact} inside framework
   * bundle.
   */
  private ImmutableMap<Artifact, Artifact> getExtraArtifacts(RuleContext ruleContext) {
    IntermediateArtifacts intermediateArtifacts =
        ObjcRuleClasses.intermediateArtifacts(ruleContext);

    ImmutableList<Pair<Artifact, Label>> headers =
        ImmutableList.copyOf(CcCommon.getHeaders(ruleContext));

    ImmutableMap.Builder<Artifact, Artifact> builder = new ImmutableMap.Builder<>();

    // Create framework binary
    Artifact frameworkBinary =
        outputArtifact(ruleContext, new PathFragment(bundleName(ruleContext)));
    builder.put(intermediateArtifacts.combinedArchitectureBinary(), frameworkBinary);

    // Create framework headers
    for (Pair<Artifact, Label> header : headers) {
      Artifact frameworkHeader =
          outputArtifact(ruleContext, new PathFragment("Headers/" + header.first.getFilename()));

      builder.put(header.first, frameworkHeader);
    }

    return builder.build();
  }

  @Override
  protected ObjcProvider exposedObjcProvider(
      RuleContext ruleContext, ReleaseBundlingSupport releaseBundlingSupport)
      throws InterruptedException {
    // Assemble framework binary and headers in the label-scoped location, so that it's possible to
    // pass -F X.framework to the compiler and -framework X to the linker. This mimics usage of
    // frameworks when built from Xcode.

    // To do this, we symlink all required artifacts into destination and pass them to
    // FRAMEWORK_IMPORTS list, thus utilizing ObjcFramework rule to do the work of propagating
    // them correctly.
    Iterable<Artifact> frameworkImports = getExtraArtifacts(ruleContext).values();

    ObjcProvider frameworkProvider =
        new ObjcProvider.Builder()
            .add(MERGE_ZIP, ruleContext.getImplicitOutputArtifact(ReleaseBundlingSupport.IPA))
            // TODO(dmishe): The framework returned by this rule is dynamic, not static. But because
            // this rule (incorrectly?) propagates its contents as a bundle (see "IPA" above) we
            // cannot declare its files as dynamic framework files because they would then be copied
            // into the final IPA, conflicting with the exported bundle. Instead we propagate using
            // the static frameworks key which will see the files correctly added to compile and
            // linker actions but not added to the final bundle.
            .addAll(STATIC_FRAMEWORK_FILE, frameworkImports)
            .addAll(
                STATIC_FRAMEWORK_DIR,
                uniqueContainers(frameworkImports, ObjcCommon.FRAMEWORK_CONTAINER_TYPE))
            .build();

    return frameworkProvider;
  }

  @Override
  protected void configureTarget(
      RuleConfiguredTargetBuilder target,
      RuleContext ruleContext,
      ReleaseBundlingSupport releaseBundlingSupport) {
    // Create generating actions for framework artifacts
    for (ImmutableMap.Entry<Artifact, Artifact> entry : getExtraArtifacts(ruleContext).entrySet()) {
      ruleContext.registerAction(
          new SymlinkAction(
              ruleContext.getActionOwner(),
              entry.getKey(),
              entry.getValue(),
              "Symlinking framework artifact"));
    }
  }

  /**
   * Returns an artifact at given path under "package/_frameworks/bundleName.framework" directory.
   */
  private Artifact outputArtifact(RuleContext ruleContext, PathFragment path) {
    PathFragment frameworkRoot =
        new PathFragment(
            new PathFragment("_frameworks"),
            new PathFragment(bundleName(ruleContext) + ".framework"),
            path);

    return ruleContext.getPackageRelativeArtifact(
        frameworkRoot, ruleContext.getBinOrGenfilesDirectory());
  }
}
