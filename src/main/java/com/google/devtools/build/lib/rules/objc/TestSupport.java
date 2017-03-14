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

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles.Builder;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction.Substitution;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.SkylarkClassObject;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.SimulatorRule;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.rules.test.TestEnvironmentProvider;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Preconditions;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Support for running XcTests.
 */
public class TestSupport {
  private final RuleContext ruleContext;

  public TestSupport(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
  }

  /**
   * Registers actions to create all files needed in order to actually run the test.
   *
   * @throws InterruptedException
   */
  public TestSupport registerTestRunnerActions() throws InterruptedException {
    registerTestScriptSubstitutionAction();
    return this;
  }

  /**
   * Returns the script which should be run in order to actually run the tests.
   */
  public Artifact generatedTestScript() {
    return ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, "_test_script");
  }

  private void registerTestScriptSubstitutionAction() throws InterruptedException {
    // testBundleIpa is the bundle actually containing the tests.
    Artifact testBundleIpa = testBundleIpa();

    String runMemleaks =
        ruleContext.getFragment(ObjcConfiguration.class).runMemleaks() ? "true" : "false";

    ImmutableMap<String, String> testEnv = ruleContext.getConfiguration().getTestEnv();

    // The substitutions below are common for simulator and lab device.
    ImmutableList.Builder<Substitution> substitutions =
        new ImmutableList.Builder<Substitution>()
            .add(Substitution.of("%(memleaks)s", runMemleaks))
            .add(Substitution.of("%(test_app_ipa)s", testBundleIpa.getRootRelativePathString()))
            .add(Substitution.of("%(test_app_name)s", baseNameWithoutIpa(testBundleIpa)))
            .add(
                Substitution.of("%(plugin_jars)s", Artifact.joinRootRelativePaths(":", plugins())));

    substitutions.add(Substitution.ofSpaceSeparatedMap("%(test_env)s", testEnv));

    // testHarnessIpa is the app being tested in the case where testBundleIpa is a .xctest bundle.
    Optional<Artifact> testHarnessIpa = testHarnessIpa();
    if (testHarnessIpa.isPresent()) {
      substitutions
          .add(Substitution.of("%(xctest_app_ipa)s",
              testHarnessIpa.get().getRootRelativePathString()))
          .add(Substitution.of("%(xctest_app_name)s", baseNameWithoutIpa(testHarnessIpa.get())));
    } else {
      substitutions
          .add(Substitution.of("%(xctest_app_ipa)s", ""))
          .add(Substitution.of("%(xctest_app_name)s", ""));
    }

    Artifact template;
    if (!runWithLabDevice()) {
      substitutions.addAll(substitutionsForSimulator());
      template = ruleContext.getPrerequisiteArtifact(IosTest.TEST_TEMPLATE_ATTR, Mode.TARGET);
    } else {
      substitutions.addAll(substitutionsForLabDevice());
      template = testTemplateForLabDevice();
    }

    ruleContext.registerAction(new TemplateExpansionAction(ruleContext.getActionOwner(),
        template, generatedTestScript(), substitutions.build(), /*executable=*/true));
  }

  private boolean runWithLabDevice() {
    return iosLabDeviceSubstitutions() != null;
  }

  /**
   * Gets the substitutions for simulator.
   */
  private ImmutableList<Substitution> substitutionsForSimulator() {
    ImmutableList.Builder<Substitution> substitutions = new ImmutableList.Builder<Substitution>()
        .add(Substitution.of("%(std_redirect_dylib_path)s",
            stdRedirectDylib().getRunfilesPathString()))
        .addAll(deviceSubstitutions().getSubstitutionsForTestRunnerScript());

    Optional<Artifact> testRunner = testRunner();
    if (testRunner.isPresent()) {
      substitutions.add(
          Substitution.of("%(testrunner_binary)s", testRunner.get().getRunfilesPathString()));
    }
    return substitutions.build();
  }

  private IosTestSubstitutionProvider deviceSubstitutions() {
    return ruleContext.getPrerequisite(
        IosTest.TARGET_DEVICE, Mode.TARGET, IosTestSubstitutionProvider.class);
  }

  /*
   * The IPA of the bundle that contains the tests. Typically will be a .xctest bundle, but in the
   * case where the xctest attribute is false, it will be a .app bundle.
   */
  private Artifact testBundleIpa() throws InterruptedException {
    return ruleContext.getImplicitOutputArtifact(ReleaseBundlingSupport.IPA);
  }

  /*
   * The IPA of the testHarness in the case where the testBundleIpa is an .xctest bundle.
   */
  private Optional<Artifact> testHarnessIpa() {
    FileProvider fileProvider =
        ruleContext.getPrerequisite(IosTest.XCTEST_APP_ATTR, Mode.TARGET, FileProvider.class);
    if (fileProvider == null) {
      return Optional.absent();
    }
    List<Artifact> files =
        Artifact.filterFiles(fileProvider.getFilesToBuild(), FileType.of(".ipa"));
    if (files.size() == 0) {
      return Optional.absent();
    } else if (files.size() == 1) {
      return Optional.of(Iterables.getOnlyElement(files));
    } else {
      throw new IllegalStateException("Expected 0 or 1 files in xctest_app, got: " + files);
    }
  }

  private Artifact stdRedirectDylib() {
    return ruleContext.getPrerequisiteArtifact(SimulatorRule.STD_REDIRECT_DYLIB_ATTR, Mode.HOST);
  }

  /**
   * Gets the binary of the testrunner attribute, if there is one.
   */
  private Optional<Artifact> testRunner() {
    return Optional.fromNullable(
        ruleContext.getPrerequisiteArtifact(IosTest.TEST_RUNNER_ATTR, Mode.TARGET));
  }

  /**
   * Gets the substitutions for lab device.
   */
  private ImmutableList<Substitution> substitutionsForLabDevice() {
    return new ImmutableList.Builder<Substitution>()
        .addAll(iosLabDeviceSubstitutions().getSubstitutionsForTestRunnerScript())
        .add(Substitution.of("%(ios_device_arg)s", Joiner.on(" ").join(iosDeviceArgs()))).build();
  }

  /**
   * Gets the test template for lab devices.
   */
  private Artifact testTemplateForLabDevice() {
    return ruleContext
        .getPrerequisite(
            IosTest.TEST_TARGET_DEVICE_ATTR, Mode.TARGET, LabDeviceTemplateProvider.class)
        .getLabDeviceTemplate();
  }

  @Nullable
  private IosTestSubstitutionProvider iosLabDeviceSubstitutions() {
    return ruleContext.getPrerequisite(
        IosTest.TEST_TARGET_DEVICE_ATTR, Mode.TARGET, IosTestSubstitutionProvider.class);
  }

  private List<String> iosDeviceArgs() {
    return ruleContext.attributes().get(IosTest.DEVICE_ARG_ATTR, Type.STRING_LIST);
  }

  /**
   * Adds all files needed to run this test to the passed Runfiles builder.
   */
  public TestSupport addRunfiles(
      Builder runfilesBuilder, InstrumentedFilesProvider instrumentedFilesProvider)
      throws InterruptedException {
    runfilesBuilder
        .addArtifact(testBundleIpa())
        .addArtifacts(testHarnessIpa().asSet())
        .addArtifact(generatedTestScript())
        .addTransitiveArtifacts(plugins());
    if (!runWithLabDevice()) {
      runfilesBuilder
          .addArtifact(stdRedirectDylib())
          .addTransitiveArtifacts(deviceRunfiles())
          .addArtifacts(testRunner().asSet());
    } else {
      runfilesBuilder.addTransitiveArtifacts(labDeviceRunfiles());
    }

    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      runfilesBuilder.addArtifact(ruleContext.getHostPrerequisiteArtifact(IosTest.MCOV_TOOL_ATTR));
      runfilesBuilder.addTransitiveArtifacts(instrumentedFilesProvider.getInstrumentedFiles());
    }

    return this;
  }

  /**
   * Returns any additional providers that need to be exported to the rule context to the passed
   * builder.
   */
  public Iterable<SkylarkClassObject> getExtraProviders() {
    IosDeviceProvider deviceProvider =
        (IosDeviceProvider)
            ruleContext.getPrerequisite(
                IosTest.TARGET_DEVICE, Mode.TARGET, IosDeviceProvider.SKYLARK_CONSTRUCTOR.getKey());
    DottedVersion xcodeVersion = deviceProvider.getXcodeVersion();
    AppleConfiguration configuration = ruleContext.getFragment(AppleConfiguration.class);

    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();

    if (xcodeVersion != null) {
      envBuilder.putAll(configuration.getXcodeVersionEnv(xcodeVersion));
    }

    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      envBuilder.put("COVERAGE_GCOV_PATH",
          ruleContext.getHostPrerequisiteArtifact(IosTest.OBJC_GCOV_ATTR).getExecPathString());
      envBuilder.put("APPLE_COVERAGE", "1");
    }

    return ImmutableList.<SkylarkClassObject>of(new TestEnvironmentProvider(envBuilder.build()));
  }

  /**
   * Jar files for plugins to the test runner. May be empty.
   */
  private NestedSet<Artifact> plugins() {
    NestedSetBuilder<Artifact> pluginArtifacts = NestedSetBuilder.stableOrder();
    pluginArtifacts.addTransitive(
        PrerequisiteArtifacts.nestedSet(ruleContext, IosTest.PLUGINS_ATTR, Mode.TARGET));
    if (ruleContext.getFragment(ObjcConfiguration.class).runMemleaks()) {
      pluginArtifacts.addTransitive(
          PrerequisiteArtifacts.nestedSet(ruleContext, IosTest.MEMLEAKS_PLUGIN_ATTR, Mode.TARGET));
    }
    return pluginArtifacts.build();
  }

  /**
   * Runfiles required in order to use the specified target device.
   */
  private NestedSet<Artifact> deviceRunfiles() {
    return ruleContext.getPrerequisite(IosTest.TARGET_DEVICE, Mode.TARGET, RunfilesProvider.class)
        .getDefaultRunfiles().getAllArtifacts();
  }

  /**
   * Runfiles required in order to use the specified target device.
   */
  private NestedSet<Artifact> labDeviceRunfiles() {
    return ruleContext
        .getPrerequisite(IosTest.TEST_TARGET_DEVICE_ATTR, Mode.TARGET, RunfilesProvider.class)
        .getDefaultRunfiles().getAllArtifacts();
  }

  /**
   * Adds files which must be built in order to run this test to builder.
   */
  public TestSupport addFilesToBuild(NestedSetBuilder<Artifact> builder)
      throws InterruptedException {
    builder.add(testBundleIpa()).addAll(testHarnessIpa().asSet());
    return this;
  }

  /**
   * Returns the base name of the artifact, with the .ipa stuffix stripped.
   */
  private static String baseNameWithoutIpa(Artifact artifact) {
    String baseName = artifact.getExecPath().getBaseName();
    Preconditions.checkState(baseName.endsWith(".ipa"),
        "%s should end in .ipa but doesn't", baseName);
    return baseName.substring(0, baseName.length() - 4);
  }
}
