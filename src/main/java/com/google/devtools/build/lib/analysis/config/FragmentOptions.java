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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.Attribute.SplitTransition;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line build options for a Blaze module.
 */
public abstract class FragmentOptions extends OptionsBase implements Cloneable, Serializable {

  /**
   * Adds all labels defined by the options to a multimap. See {@code BuildOptions.getAllLabels()}.
   *
   * <p>There should generally be no code duplication between this code and DefaultsPackage. Either
   * the labels are loaded unconditionally using this method, or they are added as magic labels
   * using the tools/defaults package, but not both.
   *
   * @param labelMap a mutable multimap to which the labels of this fragment should be added
   */
  public void addAllLabels(Multimap<String, Label> labelMap) {
  }

  /**
   * Returns the labels contributed to the defaults package by this fragment.
   *
   * <p>The set of keys returned by this function should be constant, however, the values are
   * allowed to change depending on the value of the options.
   */
  @SuppressWarnings("unused")
  public Map<String, Set<Label>> getDefaultsLabels(BuildConfiguration.Options commonOptions) {
    return ImmutableMap.of();
  }

  /**
   * Returns the extra rules contributed to the default package by this fragment.
   *
   * <p>The return value should be a list of strings, which are merged into the BUILD files of the
   * defaults package.
   *
   * <p><strong>WARNING;</strong> this method should only be used when absolutely necessary. Always
   * prefer {@code getDefaultsLabels()} to this.
   */
  public ImmutableList<String> getDefaultsRules() {
    return ImmutableList.of();
  }

  /**
   * Returns a list of potential split configuration transitions for this fragment. Split
   * configurations usually need to be explicitly enabled by passing in an option.
   */
  public List<SplitTransition<BuildOptions>> getPotentialSplitTransitions() {
    return ImmutableList.of();
  }

  @Override
  public FragmentOptions clone() {
    try {
      return (FragmentOptions) super.clone();
    } catch (CloneNotSupportedException e) {
      // This can't happen.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Creates a new FragmentOptions instance with all flags set to default.
   */
  public FragmentOptions getDefault() {
    return Options.getDefaults(getClass());
  }

  /**
   * Creates a new FragmentOptions instance with flags adjusted to host platform.
   *
   * @param fallback see {@code BuildOptions.createHostOptions}
   */
  @SuppressWarnings("unused")
  public FragmentOptions getHost(boolean fallback) {
    return getDefault();
  }

  protected void addOptionalLabel(Multimap<String, Label> map, String key, String value) {
    Label label = parseOptionalLabel(value);
    if (label != null) {
      map.put(key, label);
    }
  }

  private static Label parseOptionalLabel(String value) {
    if (value != null) {
      try {
        return Label.parseAbsolute(value);
      } catch (LabelSyntaxException e) {
        // We ignore this exception here - it will cause an error message at a later time.
        // TODO(bazel-team): We can use a Converter to check the validity of the crosstoolTop
        // earlier.
        return null;
      }
    } else {
      return null;
    }
  }

  /**
   * Returns {@code true} if static configurations should be used with
   * {@link BuildConfiguration.Options.DynamicConfigsMode.NOTRIM_PARTIAL}.
   */
  public boolean useStaticConfigurationsOverride() {
    return false;
  }
}
