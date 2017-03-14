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
package com.google.devtools.build.lib.rules.android;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.rules.android.ResourceContainerConverter.Builder.SeparatorType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builder for creating resource processing action.
 */
public class AndroidResourcesProcessorBuilder {
  private static final ResourceContainerConverter.ToArtifacts RESOURCE_CONTAINER_TO_ARTIFACTS =
      ResourceContainerConverter.builder()
          .includeResourceRoots()
          .includeManifest()
          .toArtifactConverter();

  private static final ResourceContainerConverter.ToArtifacts RESOURCE_DEP_TO_ARTIFACTS =
      ResourceContainerConverter.builder()
          .includeResourceRoots()
          .includeManifest()
          .includeRTxt()
          .includeSymbolsBin()
          .toArtifactConverter();

  private static final ResourceContainerConverter.ToArg RESOURCE_CONTAINER_TO_ARG =
      ResourceContainerConverter.builder()
          .includeResourceRoots()
          .includeManifest()
          .withSeparator(SeparatorType.COLON_COMMA)
          .toArgConverter();

  private static final ResourceContainerConverter.ToArg RESOURCE_DEP_TO_ARG =
      ResourceContainerConverter.builder()
          .includeResourceRoots()
          .includeManifest()
          .includeRTxt()
          .includeSymbolsBin()
          .withSeparator(SeparatorType.COLON_COMMA)
          .toArgConverter();

  private ResourceContainer primary;
  private ResourceDependencies dependencies;
  private Artifact proguardOut;
  private Artifact mainDexProguardOut;
  private Artifact rTxtOut;
  private Artifact sourceJarOut;
  private boolean debug = false;
  private List<String> resourceConfigs = Collections.emptyList();
  private List<String> uncompressedExtensions = Collections.emptyList();
  private Artifact apkOut;
  private final AndroidSdkProvider sdk;
  private List<String> assetsToIgnore = Collections.emptyList();
  private SpawnAction.Builder spawnActionBuilder;
  private List<String> densities = Collections.emptyList();
  private String customJavaPackage;
  private final RuleContext ruleContext;
  private String versionCode;
  private String applicationId;
  private String versionName;
  private Artifact symbols;
  private Artifact dataBindingInfoZip;

  private Artifact manifestOut;
  private Artifact mergedResourcesOut;
  private boolean isLibrary;
  private boolean crunchPng = true;

  /**
   * @param ruleContext The RuleContext that was used to create the SpawnAction.Builder.
   */
  public AndroidResourcesProcessorBuilder(RuleContext ruleContext) {
    this.sdk = AndroidSdkProvider.fromRuleContext(ruleContext);
    this.ruleContext = ruleContext;
    this.spawnActionBuilder = new SpawnAction.Builder();
  }

  /**
   * The primary resource for merging. This resource will overwrite any resource or data
   * value in the transitive closure.
   */
  public AndroidResourcesProcessorBuilder withPrimary(ResourceContainer primary) {
    this.primary = primary;
    return this;
  }

  /**
   * The output zip for resource-processed data binding expressions (i.e. a zip of .xml files).
   * If null, data binding processing is skipped (and data binding expressions aren't allowed in
   * layout resources).
   */
  public AndroidResourcesProcessorBuilder setDataBindingInfoZip(Artifact zip) {
    this.dataBindingInfoZip = zip;
    return this;
  }

  public AndroidResourcesProcessorBuilder withDependencies(ResourceDependencies resourceDeps) {
    this.dependencies = resourceDeps;
    return this;
  }

  public AndroidResourcesProcessorBuilder setUncompressedExtensions(
      List<String> uncompressedExtensions) {
    this.uncompressedExtensions = uncompressedExtensions;
    return this;
  }

  public AndroidResourcesProcessorBuilder setCrunchPng(boolean crunchPng) {
    this.crunchPng = crunchPng;
    return this;
  }

  public AndroidResourcesProcessorBuilder setDensities(List<String> densities) {
    this.densities = densities;
    return this;
  }

  public AndroidResourcesProcessorBuilder setConfigurationFilters(List<String> resourceConfigs) {
    this.resourceConfigs = resourceConfigs;
    return this;
  }

  public AndroidResourcesProcessorBuilder setDebug(boolean debug) {
    this.debug = debug;
    return this;
  }

  public AndroidResourcesProcessorBuilder setProguardOut(Artifact proguardCfg) {
    this.proguardOut = proguardCfg;
    return this;
  }

  public AndroidResourcesProcessorBuilder setMainDexProguardOut(Artifact mainDexProguardCfg) {
    this.mainDexProguardOut = mainDexProguardCfg;
    return this;
  }

  public AndroidResourcesProcessorBuilder setRTxtOut(Artifact rTxtOut) {
    this.rTxtOut = rTxtOut;
    return this;
  }

  public AndroidResourcesProcessorBuilder setSymbols(Artifact symbols) {
    this.symbols = symbols;
    return this;
  }

  public AndroidResourcesProcessorBuilder setApkOut(Artifact apkOut) {
    this.apkOut = apkOut;
    return this;
  }

  public AndroidResourcesProcessorBuilder setSourceJarOut(Artifact sourceJarOut) {
    this.sourceJarOut = sourceJarOut;
    return this;
  }

  public AndroidResourcesProcessorBuilder setAssetsToIgnore(List<String> assetsToIgnore) {
    this.assetsToIgnore = assetsToIgnore;
    return this;
  }

  public AndroidResourcesProcessorBuilder setManifestOut(Artifact manifestOut) {
    this.manifestOut = manifestOut;
    return this;
  }

  public AndroidResourcesProcessorBuilder setMergedResourcesOut(Artifact mergedResourcesOut) {
    this.mergedResourcesOut = mergedResourcesOut;
    return this;
  }

  public AndroidResourcesProcessorBuilder setLibrary(boolean isLibrary) {
    this.isLibrary = isLibrary;
    return this;
  }

  public ResourceContainer build(ActionConstructionContext context) {
    List<Artifact> outs = new ArrayList<>();
    CustomCommandLine.Builder builder = new CustomCommandLine.Builder();

    if (!Strings.isNullOrEmpty(sdk.getBuildToolsVersion())) {
      builder.add("--buildToolsVersion").add(sdk.getBuildToolsVersion());
    }

    builder.addExecPath("--aapt", sdk.getAapt().getExecutable());
    // Use a FluentIterable to avoid flattening the NestedSets
    NestedSetBuilder<Artifact> inputs = NestedSetBuilder.naiveLinkOrder();
    inputs.addAll(ruleContext.getExecutablePrerequisite("$android_resources_processor", Mode.HOST)
            .getRunfilesSupport()
            .getRunfilesArtifactsWithoutMiddlemen());

    builder.addExecPath("--annotationJar", sdk.getAnnotationsJar());
    inputs.add(sdk.getAnnotationsJar());

    builder.addExecPath("--androidJar", sdk.getAndroidJar());
    inputs.add(sdk.getAndroidJar());

    builder.add("--primaryData").add(RESOURCE_CONTAINER_TO_ARG.apply(primary));
    inputs.addTransitive(RESOURCE_CONTAINER_TO_ARTIFACTS.apply(primary));

    ResourceContainerConverter.convertDependencies(
        dependencies, builder, inputs, RESOURCE_DEP_TO_ARG, RESOURCE_DEP_TO_ARTIFACTS);

    if (isLibrary) {
      builder.add("--packageType").add("LIBRARY");
    }

    if (rTxtOut != null) {
      builder.addExecPath("--rOutput", rTxtOut);
      outs.add(rTxtOut);
    }

    if (symbols != null) {
      builder.addExecPath("--symbolsOut", symbols);
      outs.add(symbols);
    }
    if (sourceJarOut != null) {
      builder.addExecPath("--srcJarOutput", sourceJarOut);
      outs.add(sourceJarOut);
    }
    if (proguardOut != null) {
      builder.addExecPath("--proguardOutput", proguardOut);
      outs.add(proguardOut);
    }

    if (mainDexProguardOut != null) {
      builder.addExecPath("--mainDexProguardOutput", mainDexProguardOut);
      outs.add(mainDexProguardOut);
    }

    if (manifestOut != null) {
      builder.addExecPath("--manifestOutput", manifestOut);
      outs.add(manifestOut);
    }

    if (mergedResourcesOut != null) {
      builder.addExecPath("--resourcesOutput", mergedResourcesOut);
      outs.add(mergedResourcesOut);
    }

    if (apkOut != null) {
      builder.addExecPath("--packagePath", apkOut);
      outs.add(apkOut);
    }
    if (!resourceConfigs.isEmpty()) {
      builder.addJoinStrings("--resourceConfigs", ",", resourceConfigs);
    }
    if (!densities.isEmpty()) {
      builder.addJoinStrings("--densities", ",", densities);
    }
    if (!uncompressedExtensions.isEmpty()) {
      builder.addJoinStrings("--uncompressedExtensions", ",", uncompressedExtensions);
    }
    if (!crunchPng) {
      builder.add("--useAaptCruncher=no");
    }
    if (!assetsToIgnore.isEmpty()) {
      builder.addJoinStrings("--assetsToIgnore", ",", assetsToIgnore);
    }
    if (debug) {
      builder.add("--debug");
    }

    if (versionCode != null) {
      builder.add("--versionCode").add(versionCode);
    }

    if (versionName != null) {
      builder.add("--versionName").add(versionName);
    }

    if (applicationId != null) {
      builder.add("--applicationId").add(applicationId);
    }

    if (dataBindingInfoZip != null) {
      builder.addExecPath("--dataBindingInfoOut", dataBindingInfoZip);
      outs.add(dataBindingInfoZip);
    }

    if (!Strings.isNullOrEmpty(customJavaPackage)) {
      // Sets an alternative java package for the generated R.java
      // this allows android rules to generate resources outside of the java{,tests} tree.
      builder.add("--packageForR").add(customJavaPackage);
    }

    // Create the spawn action.
    ruleContext.registerAction(
        this.spawnActionBuilder
            .addTool(sdk.getAapt())
            .addTransitiveInputs(inputs.build())
            .addOutputs(ImmutableList.<Artifact>copyOf(outs))
            .setCommandLine(builder.build())
            .setExecutable(
                ruleContext.getExecutablePrerequisite("$android_resources_processor", Mode.HOST))
            .setProgressMessage("Processing Android resources for " + ruleContext.getLabel())
            .setMnemonic("AndroidAapt")
            .build(context));

    // Return the full set of processed transitive dependencies.
    ResourceContainer.Builder result = primary.toBuilder()
        .setJavaSourceJar(sourceJarOut)
        .setRTxt(rTxtOut)
        .setSymbols(symbols);
    // If there is an apk to be generated, use it, else reuse the apk from the primary resources.
    // All android_binary ResourceContainers have to have an apk, but if a new one is not
    // requested to be built for this resource processing action (in case of just creating an
    // R.txt or proguard merging), reuse the primary resource from the dependencies.
    if (apkOut != null) {
      result.setApk(apkOut);
    }
    if (manifestOut != null) {
      result.setManifest(manifestOut);
    }
    return result.build();
  }

  public AndroidResourcesProcessorBuilder setJavaPackage(String customJavaPackage) {
    this.customJavaPackage = customJavaPackage;
    return this;
  }

  public AndroidResourcesProcessorBuilder setVersionCode(String versionCode) {
    this.versionCode = versionCode;
    return this;
  }

  public AndroidResourcesProcessorBuilder setApplicationId(String applicationId) {
    if (applicationId != null && !applicationId.isEmpty()) {
      this.applicationId = applicationId;
    }
    return this;
  }

  public AndroidResourcesProcessorBuilder setVersionName(String versionName) {
    this.versionName = versionName;
    return this;
  }
}
