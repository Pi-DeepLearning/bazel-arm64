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

import com.google.devtools.build.lib.analysis.config.ConfigurationEnvironment;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AbstractAttributeMapper;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.syntax.Type;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Tool for chasing redirects. This is intended to be used during configuration creation.
 */
public final class RedirectChaser {

  /**
   * Custom attribute mapper that throws an exception if an attribute's value depends on the
   * build configuration.
   */
  private static class StaticValuedAttributeMapper extends AbstractAttributeMapper {
    public StaticValuedAttributeMapper(Rule rule) {
      super(rule.getPackage(), rule.getRuleClassObject(), rule.getLabel(),
          rule.getAttributeContainer());
    }

    /**
     * Returns the value of the given attribute.
     *
     * @throws InvalidConfigurationException if the value is configuration-dependent
     */
    public <T> T getAndValidate(String attributeName, Type<T> type)
        throws InvalidConfigurationException {
      if (getSelectorList(attributeName, type) != null) {
        throw new InvalidConfigurationException
            ("The value of '" + attributeName + "' cannot be configuration-dependent");
      }
      return super.get(attributeName, type);
    }
  }

  /**
   * Follows the 'srcs' attribute of the given label recursively. Keeps repeating as long as the
   * labels are either <code>alias</code> or <code>bind</code> rules.
   *
   * @param env for loading the packages
   * @param label the label to start at
   * @param name user-meaningful description of the content being resolved
   * @return the label which cannot be further resolved
   * @throws InvalidConfigurationException if something goes wrong
   */
  @Nullable
  public static Label followRedirects(ConfigurationEnvironment env, Label label, String name)
      throws InvalidConfigurationException, InterruptedException {
    Label oldLabel = null;
    Set<Label> visitedLabels = new HashSet<>();
    visitedLabels.add(label);
    try {
      while (true) {
        Target possibleRedirect = env.getTarget(label);
        if (possibleRedirect == null) {
          return null;
        }
        Label newLabel = getBindOrAliasRedirect(possibleRedirect);
        if (newLabel == null) {
          return label;
        }

        newLabel = label.resolveRepositoryRelative(newLabel);
        oldLabel = label;
        label = newLabel;
        if (!visitedLabels.add(label)) {
          throw new InvalidConfigurationException("The " + name + " points to a rule which "
              + "recursively references itself. The label " + label + " is part of the loop");
        }
      }
    } catch (NoSuchThingException e) {
      String prefix = oldLabel == null
          ? ""
          : "in target '" + oldLabel + "': ";
      throw new InvalidConfigurationException(prefix + e.getMessage(), e);
    }
  }

  private static Label getBindOrAliasRedirect(Target target)
      throws InvalidConfigurationException {
    if (!(target instanceof Rule)) {
      return null;
    }

    Rule rule = (Rule) target;
    if (!rule.getRuleClass().equals("bind") && !rule.getRuleClass().equals("alias")) {
      return null;
    }

    return new StaticValuedAttributeMapper(rule).getAndValidate("actual", BuildType.LABEL);
  }
}
