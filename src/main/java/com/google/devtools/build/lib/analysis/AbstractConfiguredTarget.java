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

package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.PackageSpecification;
import com.google.devtools.build.lib.packages.SkylarkClassObjectConstructor;
import com.google.devtools.build.lib.packages.SkylarkProviderIdentifier;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import javax.annotation.Nullable;

/**
 * An abstract implementation of ConfiguredTarget in which all properties are
 * assigned trivial default values.
 */
public abstract class AbstractConfiguredTarget
    implements ConfiguredTarget, VisibilityProvider, ClassObject {
  private final Target target;
  private final BuildConfiguration configuration;

  private final NestedSet<PackageSpecification> visibility;

  // Accessors for Skylark
  private static final String DATA_RUNFILES_FIELD = "data_runfiles";
  private static final String DEFAULT_RUNFILES_FIELD = "default_runfiles";

  AbstractConfiguredTarget(Target target,
                           BuildConfiguration configuration) {
    this.target = target;
    this.configuration = configuration;
    this.visibility = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  AbstractConfiguredTarget(TargetContext targetContext) {
    this.target = targetContext.getTarget();
    this.configuration = targetContext.getConfiguration();
    this.visibility = targetContext.getVisibility();
  }

  @Override
  public final NestedSet<PackageSpecification> getVisibility() {
    return visibility;
  }

  @Override
  public Target getTarget() {
    return target;
  }

  @Override
  public BuildConfiguration getConfiguration() {
    return configuration;
  }

  @Nullable
  @Override
  public Object get(SkylarkProviderIdentifier id) {
    if (id.isLegacy()) {
      return get(id.getLegacyId());
    }
    return get(id.getKey());
  }

  @Override
  public Label getLabel() {
    return getTarget().getLabel();
  }

  @Override
  public String toString() {
    return "ConfiguredTarget(" + getTarget().getLabel() + ", " + getConfiguration() + ")";
  }

  @Nullable
  @Override
  public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
    AnalysisUtils.checkProvider(provider);
    if (provider.isAssignableFrom(getClass())) {
      return provider.cast(this);
    } else {
      return null;
    }
  }

  @Override
  public Object getValue(String name) {
    switch (name) {
      case LABEL_FIELD:
        return getLabel();
      case FILES_FIELD:
        // A shortcut for files to build in Skylark. FileConfiguredTarget and RuleConfiguredTarget
        // always has FileProvider and Error- and PackageGroupConfiguredTarget-s shouldn't be
        // accessible in Skylark.
        return SkylarkNestedSet.of(
            Artifact.class, getProvider(FileProvider.class).getFilesToBuild());
      case ASPECTS_FIELD:
        return ImmutableList.<String>of();
      case DEFAULT_RUNFILES_FIELD:
        return RunfilesProvider.DEFAULT_RUNFILES.apply(this);
      case DATA_RUNFILES_FIELD:
        return RunfilesProvider.DATA_RUNFILES.apply(this);
      default:
        return get(name);
    }
  }

  @Override
  public Object getIndex(Object key, Location loc) throws EvalException {
    if (!(key instanceof SkylarkClassObjectConstructor)) {
      throw new EvalException(loc, String.format(
          "Type Target only supports indexing by object constructors, got %s instead",
          EvalUtils.getDataTypeName(key)));
    }
    SkylarkClassObjectConstructor constructor = (SkylarkClassObjectConstructor) key;
    SkylarkProviders provider = getProvider(SkylarkProviders.class);
    if (provider != null) {
      Object declaredProvider = provider.getDeclaredProvider(constructor.getKey());
      if (declaredProvider != null) {
        return declaredProvider;
      }
    }
    // Either provider or declaredProvider is null
    throw new EvalException(loc, String.format(
        "Object of type Target doesn't contain declared provider %s",
        constructor.getKey().getExportedName()));
  }

  @Override
  public boolean containsKey(Object key, Location loc) throws EvalException {
    if (!(key instanceof SkylarkClassObjectConstructor)) {
      throw new EvalException(loc, String.format(
          "Type Target only supports querying by object constructors, got %s instead",
          EvalUtils.getDataTypeName(key)));
    }
    SkylarkClassObjectConstructor constructor = (SkylarkClassObjectConstructor) key;
    SkylarkProviders provider = getProvider(SkylarkProviders.class);
    if (provider != null) {
      Object declaredProvider = provider.getDeclaredProvider(constructor.getKey());
      if (declaredProvider != null) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String errorMessage(String name) {
    return null;
  }

  @Override
  public ImmutableCollection<String> getKeys() {
    return ImmutableList.of(
        DATA_RUNFILES_FIELD, DEFAULT_RUNFILES_FIELD, LABEL_FIELD, FILES_FIELD, ASPECTS_FIELD);
  }
}
