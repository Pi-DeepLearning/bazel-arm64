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

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

import java.util.Set;

/**
 * Transitive info provider for rules that behave differently when used from
 * different languages.
 *
 * <p>Most rules generate code for a particular language or are totally language independent.
 * Some rules, however, behave differently when depended upon from different languages.
 * They might generate different libraries when used from different languages (and with
 * different API versions). This interface allows code sharing between implementations.
 *
 * <p>This provider is not really a roll-up of transitive information.
 */
@Immutable
public final class LanguageDependentFragment implements TransitiveInfoProvider {
  /**
   * A language that can be supported by a multi-language configured target.
   *
   * <p>Note that no {@code hashCode}/{@code equals} methods are provided, because these
   * objects are expected to be compared for object identity, which is the default.
   */
  public static final class LibraryLanguage {
    private final String displayName;

    public LibraryLanguage(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }

  private final Label label;
  private final ImmutableSet<LibraryLanguage> languages;

  public LanguageDependentFragment(Label label, Set<LibraryLanguage> languages) {
    this.label = label;
    this.languages = ImmutableSet.copyOf(languages);
  }

  /**
   * Returns the label that is associated with this piece of information.
   *
   * <p>This is usually the label of the target that provides the information.
   */
  public Label getLabel() {
    return label;
  }

  /**
   * Returns a set of the languages the ConfiguredTarget generates output for.
   * For use only by rules that directly depend on this library via a "deps" attribute.
   */
  public ImmutableSet<LibraryLanguage> getSupportedLanguages() {
    return languages;
  }

  /**
   * Routines for verifying that dependency provide the right output.
   */
  public static final class Checker {
    /**
     * Checks that given dep supports the given language.
     */
    public static boolean depSupportsLanguage(
        RuleContext context, LanguageDependentFragment dep, LibraryLanguage language) {
      if (dep.getSupportedLanguages().contains(language)) {
        return true;
      } else {
        context.attributeError(
            "deps", String.format("'%s' does not produce output for %s", dep.getLabel(), language));
        return false;
      }
    }

    /**
     * Checks that all LanguageDependentFragment support the given language.
     */
    public static void depsSupportsLanguage(RuleContext context, LibraryLanguage language) {
      for (LanguageDependentFragment dep :
               context.getPrerequisites("deps", Mode.TARGET, LanguageDependentFragment.class)) {
        depSupportsLanguage(context, dep, language);
      }
    }
  }
}
