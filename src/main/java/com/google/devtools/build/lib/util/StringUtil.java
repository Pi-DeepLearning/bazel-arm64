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
package com.google.devtools.build.lib.util;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Various utility methods operating on strings.
 */
public class StringUtil {
  /**
   * Creates a comma-separated list of words as in English.
   *
   * <p>Example: ["a", "b", "c"] -&gt; "a, b or c".
   */
  public static String joinEnglishList(Iterable<?> choices) {
    return joinEnglishList(choices, "or", "");
  }

  /**
   * Creates a comma-separated list of words as in English with the given last-separator.
   *
   * <p>Example with lastSeparator="then": ["a", "b", "c"] -&gt; "a, b then c".
   */
  public static String joinEnglishList(Iterable<?> choices, String lastSeparator) {
    return joinEnglishList(choices, lastSeparator, "");
  }

  /**
   * Creates a comma-separated list of words as in English with the given last-separator and quotes.
   *
   * <p>Example with lastSeparator="then", quote="'": ["a", "b", "c"] -&gt; "'a', 'b' then 'c'".
   */
  public static String joinEnglishList(Iterable<?> choices, String lastSeparator, String quote) {
    StringBuilder buf = new StringBuilder();
    for (Iterator<?> ii = choices.iterator(); ii.hasNext(); ) {
      Object choice = ii.next();
      if (buf.length() > 0) {
        buf.append(ii.hasNext() ? "," : " " + lastSeparator);
        buf.append(" ");
      }
      buf.append(quote).append(choice).append(quote);
    }
    return buf.length() == 0 ? "nothing" : buf.toString();
  }

  /**
   * Split a single space-separated string into a List of values.
   *
   * <p>Individual values are canonicalized such that within and
   * across calls to this method, equal values point to the same
   * object.
   *
   * <p>If the input is null, return an empty list.
   *
   * @param in space-separated list of values, eg "value1   value2".
   */
  public static List<String> splitAndInternString(String in) {
    List<String> result = new ArrayList<>();
    if (in == null) {
      return result;
    }
    for (String val : Splitter.on(' ').omitEmptyStrings().split(in)) {
      // Note that splitter returns a substring(), effectively
      // retaining the entire "in" String. Make an explicit copy here
      // to avoid that memory pitfall. Further, because there may be
      // many concurrent submissions that touch the same files,
      // attempt to use a single reference for equal strings via the
      // deduplicator.
      result.add(StringCanonicalizer.intern(val));
    }
    return result;
  }

  /**
   * Lists items up to a given limit, then prints how many were omitted.
   */
  public static StringBuilder listItemsWithLimit(StringBuilder appendTo, int limit,
      Collection<?> items) {
    Preconditions.checkState(limit > 0);
    Joiner.on(", ").appendTo(appendTo, Iterables.limit(items, limit));
    if (items.size() > limit) {
      appendTo.append(" ...(omitting ")
          .append(items.size() - limit)
          .append(" more item(s))");
    }
    return appendTo;
  }

  /**
   * Returns the ordinal representation of the number.
   */
  public static String ordinal(int number) {
    switch (number) {
      case 1:
        return "1st";
      case 2:
        return "2nd";
      case 3:
        return "3rd";
      default:
        return number + "th";
    }
  }

  /**
   * Appends a prefix and a suffix to each of the Strings.
   */
  public static Iterable<String> append(Iterable<String> values, final String prefix,
      final String suffix) {
  return Iterables.transform(values, new Function<String, String>() {
      @Override
      public String apply(String input) {
        return prefix + input + suffix;
      }
    });
  }

  /**
   * Indents the specified string by the given number of characters.
   *
   * <p>The beginning of the string before the first newline is not indented.
   */
  public static String indent(String input, int depth) {
    StringBuilder prefix = new StringBuilder();
    prefix.append("\n");
    for (int i = 0; i < depth; i++) {
      prefix.append(" ");
    }

    return input.replace("\n", prefix);
  }

  /**
   * Strips a suffix from a string. If the string does not end with the suffix, returns null.
   */
  public static String stripSuffix(String input, String suffix) {
    return input.endsWith(suffix)
        ? input.substring(0, input.length() - suffix.length())
        : null;
  }

  /**
   * Capitalizes the first character of a string.
   */
  public static String capitalize(String input) {
    if (input.isEmpty()) {
      return input;
    }

    char first = input.charAt(0);
    char capitalized = Character.toUpperCase(first);
    return first == capitalized ? input : capitalized + input.substring(1);
  }
}
