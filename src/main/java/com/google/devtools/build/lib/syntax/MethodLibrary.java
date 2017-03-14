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

package com.google.devtools.build.lib.syntax;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;
import com.google.devtools.build.lib.syntax.Type.ConversionException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A helper class containing built in functions for the Skylark language. */
public class MethodLibrary {

  private MethodLibrary() {}

  // Emulate Python substring function
  // It converts out of range indices, and never fails
  private static String pythonSubstring(String str, int start, Object end, String msg)
      throws ConversionException {
    if (start == 0 && EvalUtils.isNullOrNone(end)) {
      return str;
    }
    start = EvalUtils.clampRangeEndpoint(start, str.length());
    int stop;
    if (EvalUtils.isNullOrNone(end)) {
      stop = str.length();
    } else {
      stop = EvalUtils.clampRangeEndpoint(Type.INTEGER.convert(end, msg), str.length());
    }
    if (start >= stop) {
      return "";
    }
    return str.substring(start, stop);
  }

  // supported string methods

  @SkylarkSignature(name = "join", objectType = StringModule.class, returnType = String.class,
      doc = "Returns a string in which the string elements of the argument have been "
          + "joined by this string as a separator. Example:<br>"
          + "<pre class=\"language-python\">\"|\".join([\"a\", \"b\", \"c\"]) == \"a|b|c\"</pre>",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string, a separator."),
        @Param(name = "elements", type = SkylarkList.class, doc = "The objects to join.")})
  private static final BuiltinFunction join = new BuiltinFunction("join") {
    public String invoke(String self, SkylarkList<?> elements) throws ConversionException {
      return Joiner.on(self).join(elements);
    }
  };

  @SkylarkSignature(name = "lower", objectType = StringModule.class, returnType = String.class,
      doc = "Returns the lower case version of this string.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string, to convert to lower case.")})
  private static final BuiltinFunction lower = new BuiltinFunction("lower") {
    public String invoke(String self) {
      return self.toLowerCase();
    }
  };

  @SkylarkSignature(name = "upper", objectType = StringModule.class, returnType = String.class,
      doc = "Returns the upper case version of this string.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string, to convert to upper case.")})
  private static final BuiltinFunction upper = new BuiltinFunction("upper") {
    public String invoke(String self) {
      return self.toUpperCase();
    }
  };

  /**
   * For consistency with Python we recognize the same whitespace characters as they do over the
   * range 0x00-0xFF. See https://hg.python.org/cpython/file/3.6/Objects/unicodetype_db.h#l5738
   * This list is a consequence of Unicode character information.
   *
   * Note that this differs from Python 2.7, which uses ctype.h#isspace(), and from
   * java.lang.Character#isWhitespace(), which does not recognize U+00A0.
   */
  private static final String LATIN1_WHITESPACE = (
      "\u0009"
    + "\n"
    + "\u000B"
    + "\u000C"
    + "\r"
    + "\u001C"
    + "\u001D"
    + "\u001E"
    + "\u001F"
    + "\u0020"
    + "\u0085"
    + "\u00A0"
  );

  private static String stringLStrip(String self, String chars) {
    CharMatcher matcher = CharMatcher.anyOf(chars);
    for (int i = 0; i < self.length(); i++) {
      if (!matcher.matches(self.charAt(i))) {
        return self.substring(i);
      }
    }
    return ""; // All characters were stripped.
  }

  private static String stringRStrip(String self, String chars) {
    CharMatcher matcher = CharMatcher.anyOf(chars);
    for (int i = self.length() - 1; i >= 0; i--) {
      if (!matcher.matches(self.charAt(i))) {
        return self.substring(0, i + 1);
      }
    }
    return ""; // All characters were stripped.
  }

  @SkylarkSignature(
    name = "lstrip",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string where leading characters that appear in <code>chars</code>"
            + "are removed."
            + "<pre class=\"language-python\">"
            + "\"abcba\".lstrip(\"ba\") == \"cba\""
            + "</pre>",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(
        name = "chars",
        type = String.class,
        noneable = true,
        doc = "The characters to remove, or all whitespace if None.",
        defaultValue = "None"
      )
    }
  )
  private static final BuiltinFunction lstrip =
      new BuiltinFunction("lstrip") {
        public String invoke(String self, Object charsOrNone) {
          String chars = charsOrNone != Runtime.NONE ? (String) charsOrNone : LATIN1_WHITESPACE;
          return stringLStrip(self, chars);
        }
      };

  @SkylarkSignature(
    name = "rstrip",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string where trailing characters that appear in <code>chars</code>"
            + "are removed."
            + "<pre class=\"language-python\">"
            + "\"abcba\".rstrip(\"ba\") == \"abc\""
            + "</pre>",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(
        name = "chars",
        type = String.class,
        noneable = true,
        doc = "The characters to remove, or all whitespace if None.",
        defaultValue = "None"
      )
    }
  )
  private static final BuiltinFunction rstrip =
      new BuiltinFunction("rstrip") {
        public String invoke(String self, Object charsOrNone) {
          String chars = charsOrNone != Runtime.NONE ? (String) charsOrNone : LATIN1_WHITESPACE;
          return stringRStrip(self, chars);
        }
      };

  @SkylarkSignature(
    name = "strip",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string where trailing characters that appear in <code>chars</code>"
            + "are removed."
            + "<pre class=\"language-python\">"
            + "\"abcba\".strip(\"ba\") == \"abc\""
            + "</pre>",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(
        name = "chars",
        type = String.class,
        noneable = true,
        doc = "The characters to remove, or all whitespace if None.",
        defaultValue = "None"
      )
    }
  )
  private static final BuiltinFunction strip =
      new BuiltinFunction("strip") {
        public String invoke(String self, Object charsOrNone) {
          String chars = charsOrNone != Runtime.NONE ? (String) charsOrNone : LATIN1_WHITESPACE;
          return stringLStrip(stringRStrip(self, chars), chars);
        }
      };

  @SkylarkSignature(
    name = "replace",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string in which the occurrences "
            + "of <code>old</code> have been replaced with <code>new</code>, optionally "
            + "restricting the number of replacements to <code>maxsplit</code>.",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(name = "old", type = String.class, doc = "The string to be replaced."),
      @Param(name = "new", type = String.class, doc = "The string to replace with."),
      @Param(
        name = "maxsplit",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc = "The maximum number of replacements."
      )
    },
    useLocation = true
  )
  private static final BuiltinFunction replace =
      new BuiltinFunction("replace") {
        public String invoke(
            String self, String oldString, String newString, Object maxSplitO, Location loc)
            throws EvalException {
          StringBuffer sb = new StringBuffer();
          Integer maxSplit =
              Type.INTEGER.convertOptional(
                  maxSplitO, "'maxsplit' argument of 'replace'", /*label*/ null, Integer.MAX_VALUE);
          try {
            Matcher m = Pattern.compile(oldString, Pattern.LITERAL).matcher(self);
            for (int i = 0; i < maxSplit && m.find(); i++) {
              m.appendReplacement(sb, Matcher.quoteReplacement(newString));
            }
            m.appendTail(sb);
          } catch (IllegalStateException e) {
            throw new EvalException(loc, e.getMessage() + " in call to replace");
          }
          return sb.toString();
        }
      };

  @SkylarkSignature(
    name = "split",
    objectType = StringModule.class,
    returnType = MutableList.class,
    doc =
        "Returns a list of all the words in the string, using <code>sep</code> as the separator, "
            + "optionally limiting the number of splits to <code>maxsplit</code>.",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(name = "sep", type = String.class, doc = "The string to split on."),
      @Param(
        name = "maxsplit",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc = "The maximum number of splits."
      )
    },
    useEnvironment = true,
    useLocation = true
  )
  private static final BuiltinFunction split =
      new BuiltinFunction("split") {
        public MutableList<String> invoke(
            String self, String sep, Object maxSplitO, Location loc, Environment env)
            throws EvalException {
          int maxSplit =
              Type.INTEGER.convertOptional(
                  maxSplitO, "'split' argument of 'split'", /*label*/ null, -2);
          // + 1 because the last result is the remainder. The default is -2 so that after +1,
          // it becomes -1.
          String[] ss = Pattern.compile(sep, Pattern.LITERAL).split(self, maxSplit + 1);
          return MutableList.of(env, ss);
        }
      };

  @SkylarkSignature(
    name = "rsplit",
    objectType = StringModule.class,
    returnType = MutableList.class,
    doc =
        "Returns a list of all the words in the string, using <code>sep</code> as the separator, "
            + "optionally limiting the number of splits to <code>maxsplit</code>. "
            + "Except for splitting from the right, this method behaves like split().",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(name = "sep", type = String.class, doc = "The string to split on."),
      @Param(
        name = "maxsplit",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc = "The maximum number of splits."
      )
    },
    useEnvironment = true,
    useLocation = true
  )
  private static final BuiltinFunction rsplit =
      new BuiltinFunction("rsplit") {
        @SuppressWarnings("unused")
        public MutableList<String> invoke(
            String self, String sep, Object maxSplitO, Location loc, Environment env)
            throws EvalException {
          int maxSplit =
              Type.INTEGER.convertOptional(maxSplitO, "'split' argument of 'split'", null, -1);
          try {
            return stringRSplit(self, sep, maxSplit, env);
          } catch (IllegalArgumentException ex) {
            throw new EvalException(loc, ex);
          }
        }
      };

  /**
   * Splits the given string into a list of words, using {@code separator} as a delimiter.
   *
   * <p>At most {@code maxSplits} will be performed, going from right to left.
   *
   * @param input The input string.
   * @param separator The separator string.
   * @param maxSplits The maximum number of splits. Negative values mean unlimited splits.
   * @return A list of words
   * @throws IllegalArgumentException
   */
  private static MutableList<String> stringRSplit(
      String input, String separator, int maxSplits, Environment env) {
    if (separator.isEmpty()) {
      throw new IllegalArgumentException("Empty separator");
    }

    if (maxSplits <= 0) {
      maxSplits = Integer.MAX_VALUE;
    }

    LinkedList<String> result = new LinkedList<>();
    String[] parts = input.split(Pattern.quote(separator), -1);
    int sepLen = separator.length();
    int remainingLength = input.length();
    int splitsSoFar = 0;

    // Copies parts from the array into the final list, starting at the end (because
    // it's rsplit), as long as fewer than maxSplits splits are performed. The
    // last spot in the list is reserved for the remaining string, whose length
    // has to be tracked throughout the loop.
    for (int pos = parts.length - 1; (pos >= 0) && (splitsSoFar < maxSplits); --pos) {
      String current = parts[pos];
      result.addFirst(current);

      ++splitsSoFar;
      remainingLength -= sepLen + current.length();
    }

    if (splitsSoFar == maxSplits && remainingLength >= 0)   {
      result.addFirst(input.substring(0, remainingLength));
    }

    return new MutableList(result, env);
  }

  @SkylarkSignature(name = "partition", objectType = StringModule.class,
      returnType = MutableList.class,
      doc = "Splits the input string at the first occurrence of the separator "
          + "<code>sep</code> and returns the resulting partition as a three-element "
          + "list of the form [substring_before, separator, substring_after].",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sep", type = String.class,
          defaultValue = "' '", doc = "The string to split on, default is space (\" \").")},
      useEnvironment = true,
      useLocation = true)
  private static final BuiltinFunction partition = new BuiltinFunction("partition") {
    @SuppressWarnings("unused")
    public MutableList<String> invoke(String self, String sep, Location loc, Environment env)
        throws EvalException {
      return partitionWrapper(self, sep, true, env, loc);
    }
  };

  @SkylarkSignature(name = "rpartition", objectType = StringModule.class,
      returnType = MutableList.class,
      doc = "Splits the input string at the last occurrence of the separator "
          + "<code>sep</code> and returns the resulting partition as a three-element "
          + "list of the form [substring_before, separator, substring_after].",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sep", type = String.class,
          defaultValue = "' '", doc = "The string to split on, default is space (\" \").")},
      useEnvironment = true,
      useLocation = true)
  private static final BuiltinFunction rpartition = new BuiltinFunction("rpartition") {
    @SuppressWarnings("unused")
    public MutableList<String> invoke(String self, String sep, Location loc, Environment env)
        throws EvalException {
      return partitionWrapper(self, sep, false, env, loc);
    }
  };

  /**
   * Wraps the stringPartition() method and converts its results and exceptions
   * to the expected types.
   *
   * @param self The input string
   * @param separator The string to split on
   * @param forward A flag that controls whether the input string is split around
   *    the first ({@code true}) or last ({@code false}) occurrence of the separator.
   * @param env The current environment
   * @param loc The location that is used for potential exceptions
   * @return A list with three elements
   */
  private static MutableList<String> partitionWrapper(
      String self, String separator, boolean forward,
      Environment env, Location loc) throws EvalException {
    try {
      return new MutableList(stringPartition(self, separator, forward), env);
    } catch (IllegalArgumentException ex) {
      throw new EvalException(loc, ex);
    }
  }

  /**
   * Splits the input string at the {first|last} occurrence of the given separator and returns the
   * resulting partition as a three-tuple of Strings, contained in a {@code MutableList}.
   *
   * <p>If the input string does not contain the separator, the tuple will consist of the original
   * input string and two empty strings.
   *
   * <p>This method emulates the behavior of Python's str.partition() and str.rpartition(),
   * depending on the value of the {@code forward} flag.
   *
   * @param input The input string
   * @param separator The string to split on
   * @param forward A flag that controls whether the input string is split around the first ({@code
   *     true}) or last ({@code false}) occurrence of the separator.
   * @return A three-tuple (List) of the form [part_before_separator, separator,
   *     part_after_separator].
   */
  private static List<String> stringPartition(String input, String separator, boolean forward) {
    if (separator.isEmpty()) {
      throw new IllegalArgumentException("Empty separator");
    }

    int partitionSize = 3;
    ArrayList<String> result = new ArrayList<>(partitionSize);
    int pos = forward ? input.indexOf(separator) : input.lastIndexOf(separator);

    if (pos < 0) {
      for (int i = 0; i < partitionSize; ++i) {
        result.add("");
      }

      // Following Python's implementation of str.partition() and str.rpartition(),
      // the input string is copied to either the first or the last position in the
      // list, depending on the value of the forward flag.
      result.set(forward ? 0 : partitionSize - 1, input);
    } else {
      result.add(input.substring(0, pos));
      result.add(separator);

      // pos + sep.length() is at most equal to input.length(). This worst-case
      // happens when the separator is at the end of the input string. However,
      // substring() will return an empty string in this scenario, thus making
      // any additional safety checks obsolete.
      result.add(input.substring(pos + separator.length()));
    }

    return result;
  }

  @SkylarkSignature(
    name = "capitalize",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Returns a copy of the string with its first character capitalized and the rest "
            + "lowercased. This method does not support non-ascii characters.",
    parameters = {@Param(name = "self", type = String.class, doc = "This string.")}
  )
  private static final BuiltinFunction capitalize =
      new BuiltinFunction("capitalize") {
        @SuppressWarnings("unused")
        public String invoke(String self) throws EvalException {
          if (self.isEmpty()) {
            return self;
          }
          return Character.toUpperCase(self.charAt(0)) + self.substring(1).toLowerCase();
        }
      };

  @SkylarkSignature(name = "title", objectType = StringModule.class,
      returnType = String.class,
      doc =
      "Converts the input string into title case, i.e. every word starts with an "
      + "uppercase letter while the remaining letters are lowercase. In this "
      + "context, a word means strictly a sequence of letters. This method does "
      + "not support supplementary Unicode characters.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction title = new BuiltinFunction("title") {
    @SuppressWarnings("unused")
    public String invoke(String self) throws EvalException {
      char[] data = self.toCharArray();
      boolean previousWasLetter = false;

      for (int pos = 0; pos < data.length; ++pos) {
        char current = data[pos];
        boolean currentIsLetter = Character.isLetter(current);

        if (currentIsLetter) {
          if (previousWasLetter && Character.isUpperCase(current)) {
            data[pos] = Character.toLowerCase(current);
          } else if (!previousWasLetter && Character.isLowerCase(current)) {
            data[pos] = Character.toUpperCase(current);
          }
        }
        previousWasLetter = currentIsLetter;
      }

      return new String(data);
    }
  };

  /**
   * Common implementation for find, rfind, index, rindex.
   * @param forward true if we want to return the last matching index.
   */
  private static int stringFind(boolean forward,
      String self, String sub, int start, Object end, String msg)
      throws ConversionException {
    String substr = pythonSubstring(self, start, end, msg);
    int subpos = forward ? substr.indexOf(sub) : substr.lastIndexOf(sub);
    start = EvalUtils.clampRangeEndpoint(start, self.length());
    return subpos < 0 ? subpos : subpos + start;
  }

  @SkylarkSignature(name = "rfind", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the last index where <code>sub</code> is found, "
          + "or -1 if no such index exists, optionally restricting to "
          + "[<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to find."),
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")})
  private static final BuiltinFunction rfind = new BuiltinFunction("rfind") {
    public Integer invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return stringFind(false, self, sub, start, end, "'end' argument to rfind");
    }
  };

  @SkylarkSignature(name = "find", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the first index where <code>sub</code> is found, "
          + "or -1 if no such index exists, optionally restricting to "
          + "[<code>start</code>:<code>end]</code>, "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to find."),
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")})
  private static final BuiltinFunction find = new BuiltinFunction("find") {
    public Integer invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return stringFind(true, self, sub, start, end, "'end' argument to find");
    }
  };

  @SkylarkSignature(
    name = "rindex",
    objectType = StringModule.class,
    returnType = Integer.class,
    doc =
        "Returns the last index where <code>sub</code> is found, "
            + "or raises an error if no such index exists, optionally restricting to "
            + "[<code>start</code>:<code>end</code>], "
            + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(name = "sub", type = String.class, doc = "The substring to find."),
      @Param(
        name = "start",
        type = Integer.class,
        defaultValue = "0",
        doc = "Restrict to search from this position."
      ),
      @Param(
        name = "end",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc = "optional position before which to restrict to search."
      )
    },
    useLocation = true
  )
  private static final BuiltinFunction rindex =
      new BuiltinFunction("rindex") {
        public Integer invoke(String self, String sub, Integer start, Object end, Location loc)
            throws EvalException {
          int res = stringFind(false, self, sub, start, end, "'end' argument to rindex");
          if (res < 0) {
            throw new EvalException(loc, Printer.format("substring %r not found in %r", sub, self));
          }
          return res;
        }
      };

  @SkylarkSignature(
    name = "index",
    objectType = StringModule.class,
    returnType = Integer.class,
    doc =
        "Returns the first index where <code>sub</code> is found, "
            + "or raises an error if no such index exists, optionally restricting to "
            + "[<code>start</code>:<code>end]</code>, "
            + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
      @Param(name = "sub", type = String.class, doc = "The substring to find."),
      @Param(
        name = "start",
        type = Integer.class,
        defaultValue = "0",
        doc = "Restrict to search from this position."
      ),
      @Param(
        name = "end",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc = "optional position before which to restrict to search."
      )
    },
    useLocation = true
  )
  private static final BuiltinFunction index =
      new BuiltinFunction("index") {
        public Integer invoke(String self, String sub, Integer start, Object end, Location loc)
            throws EvalException {
          int res = stringFind(true, self, sub, start, end, "'end' argument to index");
          if (res < 0) {
            throw new EvalException(loc, Printer.format("substring %r not found in %r", sub, self));
          }
          return res;
        }
      };

  @SkylarkSignature(name = "splitlines", objectType = StringModule.class,
      returnType = SkylarkList.class,
      doc =
      "Splits the string at line boundaries ('\\n', '\\r\\n', '\\r') "
      + "and returns the result as a list.",
      parameters = {
          @Param(name = "self", type = String.class, doc = "This string."),
          @Param(name = "keepends", type = Boolean.class, defaultValue = "False",
              doc = "Whether the line breaks should be included in the resulting list.")})
  private static final BuiltinFunction splitLines = new BuiltinFunction("splitlines") {
    @SuppressWarnings("unused")
    public SkylarkList<String> invoke(String self, Boolean keepEnds) throws EvalException {
      List<String> result = new ArrayList<>();
      Matcher matcher = SPLIT_LINES_PATTERN.matcher(self);
      while (matcher.find()) {
        String line = matcher.group("line");
        String lineBreak = matcher.group("break");
        boolean trailingBreak = lineBreak.isEmpty();
        if (line.isEmpty() && trailingBreak) {
          break;
        }
        if (keepEnds && !trailingBreak) {
          result.add(line + lineBreak);
        } else {
          result.add(line);
        }
      }
      return SkylarkList.createImmutable(result);
    }
  };

  private static final Pattern SPLIT_LINES_PATTERN =
      Pattern.compile("(?<line>.*)(?<break>(\\r\\n|\\r|\\n)?)");

  @SkylarkSignature(name = "isalpha", objectType = StringModule.class, returnType = Boolean.class,
    doc = "Returns True if all characters in the string are alphabetic ([a-zA-Z]) and there is "
        + "at least one character.",
    parameters = {
        @Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isalpha = new BuiltinFunction("isalpha") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      return MethodLibrary.matches(self, MethodLibrary.ALPHA, false);
    }
  };

  @SkylarkSignature(name = "isalnum", objectType = StringModule.class, returnType = Boolean.class,
      doc =
      "Returns True if all characters in the string are alphanumeric ([a-zA-Z0-9]) and there is "
      + "at least one character.",
      parameters = {@Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isAlnum = new BuiltinFunction("isalnum") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      return MethodLibrary.matches(self, MethodLibrary.ALNUM, false);
    }
  };

  @SkylarkSignature(name = "isdigit", objectType = StringModule.class, returnType = Boolean.class,
      doc =
      "Returns True if all characters in the string are digits ([0-9]) and there is "
      + "at least one character.",
      parameters = {@Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isDigit = new BuiltinFunction("isdigit") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      return MethodLibrary.matches(self, MethodLibrary.DIGIT, false);
    }
  };

  @SkylarkSignature(name = "isspace", objectType = StringModule.class, returnType = Boolean.class,
      doc =
      "Returns True if all characters are white space characters and the string "
      + "contains at least one character.",
      parameters = {@Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isSpace = new BuiltinFunction("isspace") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      return MethodLibrary.matches(self, MethodLibrary.SPACE, false);
    }
  };

  @SkylarkSignature(name = "islower", objectType = StringModule.class, returnType = Boolean.class,
      doc =
      "Returns True if all cased characters in the string are lowercase and there is "
      + "at least one character.",
      parameters = {@Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isLower = new BuiltinFunction("islower") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      // Python also accepts non-cased characters, so we cannot use LOWER.
      return MethodLibrary.matches(self, MethodLibrary.UPPER.negate(), true);
    }
  };

  @SkylarkSignature(name = "isupper", objectType = StringModule.class, returnType = Boolean.class,
      doc =
      "Returns True if all cased characters in the string are uppercase and there is "
      + "at least one character.",
      parameters = {@Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isUpper = new BuiltinFunction("isupper") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      // Python also accepts non-cased characters, so we cannot use UPPER.
      return MethodLibrary.matches(self, MethodLibrary.LOWER.negate(), true);
    }
  };

  @SkylarkSignature(name = "istitle", objectType = StringModule.class, returnType = Boolean.class,
      doc =
      "Returns True if the string is in title case and it contains at least one character. "
      + "This means that every uppercase character must follow an uncased one (e.g. whitespace) "
      + "and every lowercase character must follow a cased one (e.g. uppercase or lowercase).",
      parameters = {@Param(name = "self", type = String.class, doc = "This string.")})
  private static final BuiltinFunction isTitle = new BuiltinFunction("istitle") {
    @SuppressWarnings("unused") // Called via Reflection
    public Boolean invoke(String self) throws EvalException {
      if (self.isEmpty()) {
        return false;
      }
      // From the Python documentation: "uppercase characters may only follow uncased characters
      // and lowercase characters only cased ones".
      char[] data = self.toCharArray();
      CharMatcher matcher = CharMatcher.any();
      char leftMostCased = ' ';
      for (int pos = data.length - 1; pos >= 0; --pos) {
        char current = data[pos];
        // 1. Check condition that was determined by the right neighbor.
        if (!matcher.matches(current)) {
          return false;
        }
        // 2. Determine condition for the left neighbor.
        if (LOWER.matches(current)) {
          matcher = CASED;
        } else if (UPPER.matches(current)) {
          matcher = CASED.negate();
        } else {
          matcher = CharMatcher.any();
        }
        // 3. Store character if it is cased.
        if (CASED.matches(current)) {
          leftMostCased = current;
        }
      }
      // The leftmost cased letter must be uppercase. If leftMostCased is not a cased letter here,
      // then the string doesn't have any cased letter, so UPPER.test will return false.
      return UPPER.matches(leftMostCased);
    }
  };

  private static boolean matches(
      String str, CharMatcher matcher, boolean requiresAtLeastOneCasedLetter) {
    if (str.isEmpty()) {
      return false;
    } else if (!requiresAtLeastOneCasedLetter) {
      return matcher.matchesAllOf(str);
    }
    int casedLetters = 0;
    for (char current : str.toCharArray()) {
      if (!matcher.matches(current)) {
        return false;
      } else if (requiresAtLeastOneCasedLetter && CASED.matches(current)) {
        ++casedLetters;
      }
    }
    return casedLetters > 0;
  }

  private static final CharMatcher DIGIT = CharMatcher.javaDigit();
  private static final CharMatcher LOWER = CharMatcher.inRange('a', 'z');
  private static final CharMatcher UPPER = CharMatcher.inRange('A', 'Z');
  private static final CharMatcher ALPHA = LOWER.or(UPPER);
  private static final CharMatcher ALNUM = ALPHA.or(DIGIT);
  private static final CharMatcher CASED = ALPHA;
  private static final CharMatcher SPACE = CharMatcher.whitespace();

  @SkylarkSignature(name = "count", objectType = StringModule.class, returnType = Integer.class,
      doc = "Returns the number of (non-overlapping) occurrences of substring <code>sub</code> in "
          + "string, optionally restricting to [<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to count."),
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position before which to restrict to search.")})
  private static final BuiltinFunction count = new BuiltinFunction("count") {
    public Integer invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      String str = pythonSubstring(self, start, end, "'end' operand of 'find'");
      if (sub.isEmpty()) {
        return str.length() + 1;
      }
      int count = 0;
      int index = -1;
      while ((index = str.indexOf(sub)) >= 0) {
        count++;
        str = str.substring(index + sub.length());
      }
      return count;
    }
  };

  @SkylarkSignature(name = "endswith", objectType = StringModule.class, returnType = Boolean.class,
      doc = "Returns True if the string ends with <code>sub</code>, "
          + "otherwise False, optionally restricting to [<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to check."),
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Test beginning at this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "optional position at which to stop comparing.")})
  private static final BuiltinFunction endswith = new BuiltinFunction("endswith") {
    public Boolean invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return pythonSubstring(self, start, end, "'end' operand of 'endswith'").endsWith(sub);
    }
  };

  // In Python, formatting is very complex.
  // We handle here the simplest case which provides most of the value of the function.
  // https://docs.python.org/3/library/string.html#formatstrings
  @SkylarkSignature(
    name = "format",
    objectType = StringModule.class,
    returnType = String.class,
    doc =
        "Perform string interpolation. Format strings contain replacement fields "
            + "surrounded by curly braces <code>{}</code>. Anything that is not contained "
            + "in braces is considered literal text, which is copied unchanged to the output."
            + "If you need to include a brace character in the literal text, it can be "
            + "escaped by doubling: <code>{{</code> and <code>}}</code>"
            + "A replacement field can be either a name, a number, or empty. Values are "
            + "converted to strings using the <a href=\"globals.html#str\">str</a> function."
            + "<pre class=\"language-python\">"
            + "# Access in order:\n"
            + "\"{} < {}\".format(4, 5) == \"4 < 5\"\n"
            + "# Access by position:\n"
            + "\"{1}, {0}\".format(2, 1) == \"1, 2\"\n"
            + "# Access by name:\n"
            + "\"x{key}x\".format(key = 2) == \"x2x\"</pre>\n",
    parameters = {
      @Param(name = "self", type = String.class, doc = "This string."),
    },
    extraPositionals =
        @Param(
          name = "args",
          type = SkylarkList.class,
          defaultValue = "()",
          doc = "List of arguments."
        ),
    extraKeywords =
        @Param(
          name = "kwargs",
          type = SkylarkDict.class,
          defaultValue = "{}",
          doc = "Dictionary of arguments."
        ),
    useLocation = true
  )
  private static final BuiltinFunction format =
      new BuiltinFunction("format") {
        @SuppressWarnings("unused")
        public String invoke(
            String self, SkylarkList<Object> args, SkylarkDict<?, ?> kwargs, Location loc)
            throws EvalException {
          return new FormatParser(loc)
              .format(
                  self,
                  args.getImmutableList(),
                  kwargs.getContents(String.class, Object.class, "kwargs"));
        }
      };

  @SkylarkSignature(name = "startswith", objectType = StringModule.class,
      returnType = Boolean.class,
      doc = "Returns True if the string starts with <code>sub</code>, "
          + "otherwise False, optionally restricting to [<code>start</code>:<code>end</code>], "
          + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", type = String.class, doc = "This string."),
        @Param(name = "sub", type = String.class, doc = "The substring to check."),
        @Param(name = "start", type = Integer.class, defaultValue = "0",
            doc = "Test beginning at this position."),
        @Param(name = "end", type = Integer.class, noneable = true, defaultValue = "None",
            doc = "Stop comparing at this position.")})
  private static final BuiltinFunction startswith = new BuiltinFunction("startswith") {
    public Boolean invoke(String self, String sub, Integer start, Object end)
        throws ConversionException {
      return pythonSubstring(self, start, end, "'end' operand of 'startswith'").startsWith(sub);
    }
  };

  @SkylarkSignature(
    name = "min",
    returnType = Object.class,
    doc =
        "Returns the smallest one of all given arguments. "
            + "If only one argument is provided, it must be a non-empty iterable.",
    extraPositionals =
      @Param(name = "args", type = SkylarkList.class, doc = "The elements to be checked."),
    useLocation = true
  )
  private static final BuiltinFunction min = new BuiltinFunction("min") {
    @SuppressWarnings("unused") // Accessed via Reflection.
    public Object invoke(SkylarkList<?> args, Location loc) throws EvalException {
      return findExtreme(args, EvalUtils.SKYLARK_COMPARATOR.reverse(), loc);
    }
  };

  @SkylarkSignature(
    name = "max",
    returnType = Object.class,
    doc =
        "Returns the largest one of all given arguments. "
            + "If only one argument is provided, it must be a non-empty iterable.",
    extraPositionals =
      @Param(name = "args", type = SkylarkList.class, doc = "The elements to be checked."),
    useLocation = true
  )
  private static final BuiltinFunction max = new BuiltinFunction("max") {
    @SuppressWarnings("unused") // Accessed via Reflection.
    public Object invoke(SkylarkList<?> args, Location loc) throws EvalException {
      return findExtreme(args, EvalUtils.SKYLARK_COMPARATOR, loc);
    }
  };

  /**
   * Returns the maximum element from this list, as determined by maxOrdering.
   */
  private static Object findExtreme(SkylarkList<?> args, Ordering<Object> maxOrdering, Location loc)
      throws EvalException {
    // Args can either be a list of items to compare, or a singleton list whose element is an
    // iterable of items to compare. In either case, there must be at least one item to compare.
    try {
      Iterable<?> items = (args.size() == 1) ? EvalUtils.toIterable(args.get(0), loc) : args;
      return maxOrdering.max(items);
    } catch (NoSuchElementException ex) {
      throw new EvalException(loc, "expected at least one item");
    }
  }

  @SkylarkSignature(
    name = "all",
    returnType = Boolean.class,
    doc = "Returns true if all elements evaluate to True or if the collection is empty.",
    parameters = {
      @Param(name = "elements", type = Object.class, doc = "A string or a collection of elements.")
    },
    useLocation = true
  )
  private static final BuiltinFunction all =
      new BuiltinFunction("all") {
        @SuppressWarnings("unused") // Accessed via Reflection.
        public Boolean invoke(Object collection, Location loc) throws EvalException {
          return !hasElementWithBooleanValue(collection, false, loc);
        }
      };

  @SkylarkSignature(
    name = "any",
    returnType = Boolean.class,
    doc = "Returns true if at least one element evaluates to True.",
    parameters = {
      @Param(name = "elements", type = Object.class, doc = "A string or a collection of elements.")
    },
    useLocation = true
  )
  private static final BuiltinFunction any =
      new BuiltinFunction("any") {
        @SuppressWarnings("unused") // Accessed via Reflection.
        public Boolean invoke(Object collection, Location loc) throws EvalException {
          return hasElementWithBooleanValue(collection, true, loc);
        }
      };

  private static boolean hasElementWithBooleanValue(Object collection, boolean value, Location loc)
      throws EvalException {
    Iterable<?> iterable = EvalUtils.toIterable(collection, loc);
    for (Object obj : iterable) {
      if (EvalUtils.toBoolean(obj) == value) {
        return true;
      }
    }
    return false;
  }

  // supported list methods
  @SkylarkSignature(
    name = "sorted",
    returnType = MutableList.class,
    doc =
        "Sort a collection. Elements are sorted first by their type, "
            + "then by their value (in ascending order).",
    parameters = {@Param(name = "self", type = Object.class, doc = "This collection.")},
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction sorted =
      new BuiltinFunction("sorted") {
        public <E> MutableList<E> invoke(Object self, Location loc, Environment env)
            throws EvalException {
          try {
            return new MutableList(
                EvalUtils.SKYLARK_COMPARATOR.sortedCopy(EvalUtils.toCollection(self, loc)), env);
          } catch (EvalUtils.ComparisonException e) {
            throw new EvalException(loc, e);
          }
        }
      };

  @SkylarkSignature(
    name = "reversed",
    returnType = MutableList.class,
    doc = "Returns a list that contains the elements of the original sequence in reversed order.",
    parameters = {
      @Param(
        name = "sequence",
        type = Object.class,
        doc = "The sequence to be reversed (string, list or tuple)."
      )
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction reversed =
      new BuiltinFunction("reversed") {
        @SuppressWarnings("unused") // Accessed via Reflection.
        public MutableList<?> invoke(Object sequence, Location loc, Environment env)
            throws EvalException {
          // We only allow lists and strings.
          if (sequence instanceof SkylarkDict) {
            throw new EvalException(
                loc, "Argument to reversed() must be a sequence, not a dictionary.");
          } else if (sequence instanceof NestedSet || sequence instanceof SkylarkNestedSet) {
            throw new EvalException(
                loc, "Argument to reversed() must be a sequence, not a depset.");
          }
          LinkedList<Object> tmpList = new LinkedList<>();
          for (Object element : EvalUtils.toIterable(sequence, loc)) {
            tmpList.addFirst(element);
          }
          return new MutableList(tmpList, env);
        }
      };

  @SkylarkSignature(
    name = "append",
    objectType = MutableList.class,
    returnType = Runtime.NoneType.class,
    doc = "Adds an item to the end of the list.",
    parameters = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "item", type = Object.class, doc = "Item to add at the end.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction append =
      new BuiltinFunction("append") {
        public Runtime.NoneType invoke(
            MutableList<Object> self, Object item, Location loc, Environment env)
            throws EvalException {
          self.add(item, loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "insert",
    objectType = MutableList.class,
    returnType = Runtime.NoneType.class,
    doc = "Inserts an item at a given position.",
    parameters = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "index", type = Integer.class, doc = "The index of the given position."),
      @Param(name = "item", type = Object.class, doc = "The item.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction insert =
      new BuiltinFunction("insert") {
        public Runtime.NoneType invoke(
            MutableList<Object> self, Integer index, Object item, Location loc, Environment env)
            throws EvalException {
          self.add(EvalUtils.clampRangeEndpoint(index, self.size()), item, loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "extend",
    objectType = MutableList.class,
    returnType = Runtime.NoneType.class,
    doc = "Adds all items to the end of the list.",
    parameters = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "items", type = SkylarkList.class, doc = "Items to add at the end.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction extend =
      new BuiltinFunction("extend") {
        public Runtime.NoneType invoke(
            MutableList<Object> self, SkylarkList<Object> items, Location loc, Environment env)
            throws EvalException {
          self.addAll(items, loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "index",
    objectType = MutableList.class,
    returnType = Integer.class,
    doc =
        "Returns the index in the list of the first item whose value is x. "
            + "It is an error if there is no such item.",
    parameters = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "x", type = Object.class, doc = "The object to search.")
    },
    useLocation = true
  )
  private static final BuiltinFunction listIndex =
      new BuiltinFunction("index") {
        public Integer invoke(MutableList<?> self, Object x, Location loc) throws EvalException {
          int i = 0;
          for (Object obj : self) {
            if (obj.equals(x)) {
              return i;
            }
            i++;
          }
          throw new EvalException(loc, Printer.format("item %r not found in list", x));
        }
      };

  @SkylarkSignature(
    name = "remove",
    objectType = MutableList.class,
    returnType = Runtime.NoneType.class,
    doc =
        "Removes the first item from the list whose value is x. "
            + "It is an error if there is no such item.",
    parameters = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(name = "x", type = Object.class, doc = "The object to remove.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction listRemove =
      new BuiltinFunction("remove") {
        public Runtime.NoneType invoke(MutableList<?> self, Object x, Location loc, Environment env)
            throws EvalException {
          for (int i = 0; i < self.size(); i++) {
            if (self.get(i).equals(x)) {
              self.remove(i, loc, env);
              return Runtime.NONE;
            }
          }
          throw new EvalException(loc, Printer.format("item %r not found in list", x));
        }
      };

  @SkylarkSignature(
    name = "pop",
    objectType = MutableList.class,
    returnType = Object.class,
    doc =
        "Removes the item at the given position in the list, and returns it. "
            + "If no <code>index</code> is specified, "
            + "it removes and returns the last item in the list.",
    parameters = {
      @Param(name = "self", type = MutableList.class, doc = "This list."),
      @Param(
        name = "i",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc = "The index of the item."
      )
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction listPop =
      new BuiltinFunction("pop") {
        public Object invoke(MutableList<?> self, Object i, Location loc, Environment env)
            throws EvalException {
          int arg = i == Runtime.NONE ? -1 : (Integer) i;
          int index = EvalUtils.getSequenceIndex(arg, self.size(), loc);
          Object result = self.get(index);
          self.remove(index, loc, env);
          return result;
        }
      };

  @SkylarkSignature(
    name = "pop",
    objectType = SkylarkDict.class,
    returnType = Object.class,
    doc =
        "Removes a <code>key</code> from the dict, and returns the associated value. "
            + "If entry with that key was found, return the specified <code>default</code> value;"
            + "if no default value was specified, fail instead.",
    parameters = {
      @Param(name = "self", type = SkylarkDict.class, doc = "This dict."),
      @Param(name = "key", type = Object.class, doc = "The key."),
      @Param(name = "default", type = Object.class, defaultValue = "unbound",
          doc = "a default value if the key is absent."),
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dictPop =
      new BuiltinFunction("pop") {
        public Object invoke(SkylarkDict<Object, Object> self, Object key, Object defaultValue,
            Location loc, Environment env)
            throws EvalException {
          Object value = self.get(key);
          if (value != null) {
            self.remove(key, loc, env);
            return value;
          }
          if (defaultValue != Runtime.UNBOUND) {
            return defaultValue;
          }
          throw new EvalException(loc, Printer.format("KeyError: %r", key));
        }
      };

  @SkylarkSignature(
    name = "popitem",
    objectType = SkylarkDict.class,
    returnType = Tuple.class,
    doc =
        "Remove and return an arbitrary <code>(key, value)</code> pair from the dictionary. "
            + "<code>popitem()</code> is useful to destructively iterate over a dictionary, "
            + "as often used in set algorithms. "
            + "If the dictionary is empty, calling <code>popitem()</code> fails. "
            + "It is deterministic which pair is returned.",
    parameters = {
      @Param(name = "self", type = SkylarkDict.class, doc = "This dict.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dictPopItem =
      new BuiltinFunction("popitem") {
        public Tuple<Object> invoke(SkylarkDict<Object, Object> self,
            Location loc, Environment env)
            throws EvalException {
          if (self.isEmpty()) {
            throw new EvalException(loc, "popitem(): dictionary is empty");
          }
          Object key = self.firstKey();
          Object value = self.get(key);
          self.remove(key, loc, env);
          return Tuple.<Object>of(key, value);
        }
      };

  @SkylarkSignature(
    name = "clear",
    objectType = SkylarkDict.class,
    returnType = Runtime.NoneType.class,
    doc = "Remove all items from the dictionary.",
    parameters = {
      @Param(name = "self", type = SkylarkDict.class, doc = "This dict.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dictClear =
      new BuiltinFunction("clear") {
        public Runtime.NoneType invoke(SkylarkDict<Object, Object> self,
            Location loc, Environment env)
            throws EvalException {
          self.clear(loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "setdefault",
    objectType = SkylarkDict.class,
    returnType = Object.class,
    doc =
        "If <code>key</code> is in the dictionary, return its value. "
            + "If not, insert key with a value of <code>default</code> "
            + "and return <code>default</code>. "
            + "<code>default</code> defaults to <code>None</code>.",
    parameters = {
      @Param(name = "self", type = SkylarkDict.class, doc = "This dict."),
      @Param(name = "key", type = Object.class, doc = "The key."),
      @Param(
        name = "default",
        type = Object.class,
        defaultValue = "None",
        doc = "a default value if the key is absent."
      ),
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dictSetDefault =
      new BuiltinFunction("setdefault") {
        public Object invoke(
            SkylarkDict<Object, Object> self,
            Object key,
            Object defaultValue,
            Location loc,
            Environment env)
            throws EvalException {
          Object value = self.get(key);
          if (value != null) {
            return value;
          }
          self.put(key, defaultValue, loc, env);
          return defaultValue;
        }
      };

  @SkylarkSignature(
    name = "update",
    objectType = SkylarkDict.class,
    returnType = Runtime.NoneType.class,
    doc = "Update the dictionary with the key/value pairs from other, overwriting existing keys.",
    parameters = {
      @Param(name = "self", type = SkylarkDict.class, doc = "This dict."),
      @Param(name = "other", type = SkylarkDict.class, doc = "The values to add."),
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dictUpdate =
      new BuiltinFunction("update") {
        public Runtime.NoneType invoke(
            SkylarkDict<Object, Object> self,
            SkylarkDict<Object, Object> other,
            Location loc,
            Environment env)
            throws EvalException {
          self.putAll(other, loc, env);
          return Runtime.NONE;
        }
      };

  @SkylarkSignature(
    name = "values",
    objectType = SkylarkDict.class,
    returnType = MutableList.class,
    doc =
        "Returns the list of values:"
            + "<pre class=\"language-python\">"
            + "{2: \"a\", 4: \"b\", 1: \"c\"}.values() == [\"a\", \"b\", \"c\"]</pre>\n",
    parameters = {@Param(name = "self", type = SkylarkDict.class, doc = "This dict.")},
    useEnvironment = true
  )
  private static final BuiltinFunction values =
      new BuiltinFunction("values") {
        public MutableList<?> invoke(SkylarkDict<?, ?> self, Environment env) throws EvalException {
          return new MutableList(self.values(), env);
        }
      };

  @SkylarkSignature(
    name = "items",
    objectType = SkylarkDict.class,
    returnType = MutableList.class,
    doc =
        "Returns the list of key-value tuples:"
            + "<pre class=\"language-python\">"
            + "{2: \"a\", 4: \"b\", 1: \"c\"}.items() == [(2, \"a\"), (4, \"b\"), (1, \"c\")]"
            + "</pre>\n",
    parameters = {@Param(name = "self", type = SkylarkDict.class, doc = "This dict.")},
    useEnvironment = true
  )
  private static final BuiltinFunction items =
      new BuiltinFunction("items") {
        public MutableList<?> invoke(SkylarkDict<?, ?> self, Environment env) throws EvalException {
          List<Object> list = Lists.newArrayListWithCapacity(self.size());
          for (Map.Entry<?, ?> entries : self.entrySet()) {
            list.add(Tuple.of(entries.getKey(), entries.getValue()));
          }
          return new MutableList(list, env);
        }
      };

  @SkylarkSignature(name = "keys", objectType = SkylarkDict.class,
      returnType = MutableList.class,
      doc = "Returns the list of keys:"
          + "<pre class=\"language-python\">{2: \"a\", 4: \"b\", 1: \"c\"}.keys() == [2, 4, 1]"
          + "</pre>\n",
      parameters = {
        @Param(name = "self", type = SkylarkDict.class, doc = "This dict.")},
      useEnvironment = true)
  private static final BuiltinFunction keys = new BuiltinFunction("keys") {
    // Skylark will only call this on a dict; and
    // allowed keys are all Comparable... if not mutually, it's OK to get a runtime exception.
    @SuppressWarnings("unchecked")
    public MutableList<?> invoke(SkylarkDict<?, ?> self,
        Environment env) throws EvalException {
      List<Object> list = Lists.newArrayListWithCapacity(self.size());
      for (Map.Entry<?, ?> entries : self.entrySet()) {
        list.add(entries.getKey());
      }
      return new MutableList(list, env);
    }
  };

  @SkylarkSignature(name = "get", objectType = SkylarkDict.class,
      doc = "Returns the value for <code>key</code> if <code>key</code> is in the dictionary, "
          + "else <code>default</code>. If <code>default</code> is not given, it defaults to "
          + "<code>None</code>, so that this method never throws an error.",
      parameters = {
        @Param(name = "self", doc = "This dict."),
        @Param(name = "key", doc = "The key to look for."),
        @Param(name = "default", defaultValue = "None",
            doc = "The default value to use (instead of None) if the key is not found.")})
  private static final BuiltinFunction get = new BuiltinFunction("get") {
    public Object invoke(SkylarkDict<?, ?> self, Object key, Object defaultValue) {
      if (self.containsKey(key)) {
        return self.get(key);
      }
      return defaultValue;
    }
  };

  // unary minus
  @SkylarkSignature(
    name = "-",
    returnType = Integer.class,
    documented = false,
    doc = "Unary minus operator.",
    parameters = {
      @Param(name = "num", type = Integer.class, doc = "The number to negate.")
    }
  )
  private static final BuiltinFunction minus =
      new BuiltinFunction("-") {
        public Integer invoke(Integer num) throws ConversionException {
          return -num;
        }
      };

  @SkylarkSignature(
    name = "tuple",
    returnType = Tuple.class,
    doc =
        "Converts a collection (e.g. list, tuple or dictionary) to a tuple."
            + "<pre class=\"language-python\">tuple([1, 2]) == (1, 2)\n"
            + "tuple((2, 3, 2)) == (2, 3, 2)\n"
            + "tuple({5: \"a\", 2: \"b\", 4: \"c\"}) == (5, 2, 4)</pre>",
    parameters = {@Param(name = "x", doc = "The object to convert.")},
    useLocation = true
  )
  private static final BuiltinFunction tuple =
      new BuiltinFunction("tuple") {
        public Tuple<?> invoke(Object x, Location loc) throws EvalException {
          return Tuple.create(ImmutableList.copyOf(EvalUtils.toCollection(x, loc)));
        }
      };

  @SkylarkSignature(
    name = "list",
    returnType = MutableList.class,
    doc =
        "Converts a collection (e.g. list, tuple or dictionary) to a list."
            + "<pre class=\"language-python\">list([1, 2]) == [1, 2]\n"
            + "list((2, 3, 2)) == [2, 3, 2]\n"
            + "list({5: \"a\", 2: \"b\", 4: \"c\"}) == [5, 2, 4]</pre>",
    parameters = {@Param(name = "x", doc = "The object to convert.")},
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction list =
      new BuiltinFunction("list") {
        public MutableList<?> invoke(Object x, Location loc, Environment env) throws EvalException {
          return new MutableList(EvalUtils.toCollection(x, loc), env);
        }
      };

  @SkylarkSignature(
    name = "len",
    returnType = Integer.class,
    doc = "Returns the length of a string, list, tuple, depset, or dictionary.",
    parameters = {@Param(name = "x", doc = "The object to check length of.")},
    useLocation = true
  )
  private static final BuiltinFunction len =
      new BuiltinFunction("len") {
        public Integer invoke(Object x, Location loc) throws EvalException {
          int l = EvalUtils.size(x);
          if (l == -1) {
            throw new EvalException(loc, EvalUtils.getDataTypeName(x) + " is not iterable");
          }
          return l;
        }
      };

  @SkylarkSignature(name = "str", returnType = String.class, doc =
      "Converts any object to string. This is useful for debugging."
      + "<pre class=\"language-python\">str(\"ab\") == \"ab\"</pre>",
      parameters = {@Param(name = "x", doc = "The object to convert.")})
  private static final BuiltinFunction str = new BuiltinFunction("str") {
    public String invoke(Object x) {
      return Printer.str(x);
    }
  };

  @SkylarkSignature(name = "repr", returnType = String.class, doc =
      "Converts any object to a string representation. This is useful for debugging.<br>"
      + "<pre class=\"language-python\">str(\"ab\") == \\\"ab\\\"</pre>",
      parameters = {@Param(name = "x", doc = "The object to convert.")})
  private static final BuiltinFunction repr = new BuiltinFunction("repr") {
    public String invoke(Object x) {
      return Printer.repr(x);
    }
  };

  @SkylarkSignature(name = "bool", returnType = Boolean.class,
      doc = "Constructor for the bool type. "
      + "It returns False if the object is None, False, an empty string, the number 0, or an "
      + "empty collection. Otherwise, it returns True.",
      parameters = {@Param(name = "x", doc = "The variable to convert.")})
  private static final BuiltinFunction bool = new BuiltinFunction("bool") {
    public Boolean invoke(Object x) throws EvalException {
      return EvalUtils.toBoolean(x);
    }
  };

  @SkylarkSignature(
    name = "int",
    returnType = Integer.class,
    doc =
        "Converts a value to int. "
            + "If the argument is a string, it is converted using the given base and raises an "
            + "error if the conversion fails. "
            + "The base can be between 2 and 36 (inclusive) and defaults to 10. "
            + "The value can be prefixed with 0b/0o/ox to represent values in base 2/8/16. "
            + "If such a prefix is present, a base of 0 can be used to automatically determine the "
            + "correct base: "
            + "<pre class=\"language-python\">int(\"0xFF\", 0) == int(\"0xFF\", 16) == 255</pre>"
            + "If the argument is a bool, it returns 0 (False) or 1 (True). "
            + "If the argument is an int, it is simply returned."
            + "<pre class=\"language-python\">int(\"123\") == 123</pre>",
    parameters = {
      @Param(name = "x", type = Object.class, doc = "The string to convert."),
      @Param(
        name = "base",
        type = Integer.class,
        defaultValue = "10",
        doc = "The base of the string."
      )
    },
    useLocation = true
  )
  private static final BuiltinFunction int_ =
      new BuiltinFunction("int") {
        private final ImmutableMap<String, Integer> intPrefixes =
            ImmutableMap.of("0b", 2, "0o", 8, "0x", 16);

        @SuppressWarnings("unused")
        public Integer invoke(Object x, Integer base, Location loc) throws EvalException {
          if (x instanceof String) {
            return fromString(x, loc, base);
          } else {
            if (base != 10) {
              throw new EvalException(loc, "int() can't convert non-string with explicit base");
            }
            if (x instanceof Boolean) {
              return ((Boolean) x).booleanValue() ? 1 : 0;
            } else if (x instanceof Integer) {
              return (Integer) x;
            }
            throw new EvalException(
                loc, Printer.format("%r is not of type string or int or bool", x));
          }
        }

        private int fromString(Object x, Location loc, int base) throws EvalException {
          String value = (String) x;
          String prefix = getIntegerPrefix(value);

          if (!prefix.isEmpty()) {
            value = value.substring(prefix.length());
            int expectedBase = intPrefixes.get(prefix);
            if (base == 0) {
              // Similar to Python, base 0 means "derive the base from the prefix".
              base = expectedBase;
            } else if (base != expectedBase) {
              throw new EvalException(
                  loc, Printer.format("invalid literal for int() with base %d: %r", base, x));
            }
          }

          if (base < 2 || base > 36) {
            throw new EvalException(loc, "int() base must be >= 2 and <= 36");
          }
          try {
            return Integer.parseInt(value, base);
          } catch (NumberFormatException e) {
            throw new EvalException(
                loc, Printer.format("invalid literal for int() with base %d: %r", base, x));
          }
        }

        private String getIntegerPrefix(String value) {
          value = value.toLowerCase();
          for (String prefix : intPrefixes.keySet()) {
            if (value.startsWith(prefix)) {
              return prefix;
            }
          }
          return "";
        }
      };

  @SkylarkSignature(
    name = "dict",
    returnType = SkylarkDict.class,
    doc =
        "Creates a <a href=\"#modules.dict\">dictionary</a> from an optional positional "
            + "argument and an optional set of keyword arguments. Values from the keyword "
            + "argument will overwrite values from the positional argument if a key appears "
            + "multiple times.",
    parameters = {
      @Param(
        name = "args",
        type = Object.class,
        defaultValue = "[]",
        doc =
            "Either a dictionary or a list of entries. Entries must be tuples or lists with "
                + "exactly two elements: key, value"
      ),
    },
    extraKeywords = @Param(name = "kwargs", doc = "Dictionary of additional entries."),
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dict =
      new BuiltinFunction("dict") {
        public SkylarkDict invoke(
            Object args, SkylarkDict<String, Object> kwargs, Location loc, Environment env)
            throws EvalException {
          SkylarkDict<Object, Object> argsDict =
              (args instanceof SkylarkDict)
                  ? (SkylarkDict<Object, Object>) args
                  : getDictFromArgs(args, loc, env);
          return SkylarkDict.plus(argsDict, kwargs, env);
        }

        private SkylarkDict<Object, Object> getDictFromArgs(
            Object args, Location loc, Environment env) throws EvalException {
          SkylarkDict<Object, Object> result = SkylarkDict.of(env);
          int pos = 0;
          for (Object element : Type.OBJECT_LIST.convert(args, "parameter args in dict()")) {
            List<Object> pair = convertToPair(element, pos, loc);
            result.put(pair.get(0), pair.get(1), loc, env);
            ++pos;
          }
          return result;
        }

        private List<Object> convertToPair(Object element, int pos, Location loc)
            throws EvalException {
          try {
            List<Object> tuple = Type.OBJECT_LIST.convert(element, "");
            int numElements = tuple.size();
            if (numElements != 2) {
              throw new EvalException(
                  location,
                  String.format(
                      "item #%d has length %d, but exactly two elements are required",
                      pos, numElements));
            }
            return tuple;
          } catch (ConversionException e) {
            throw new EvalException(
                loc, String.format("cannot convert item #%d to a sequence", pos));
          }
        }
      };

  @SkylarkSignature(
    name = "enumerate",
    returnType = MutableList.class,
    doc =
        "Returns a list of pairs (two-element tuples), with the index (int) and the item from"
            + " the input list.\n<pre class=\"language-python\">"
            + "enumerate([24, 21, 84]) == [(0, 24), (1, 21), (2, 84)]</pre>\n",
    parameters = {@Param(name = "list", type = SkylarkList.class, doc = "input list.")},
    useEnvironment = true
  )
  private static final BuiltinFunction enumerate =
      new BuiltinFunction("enumerate") {
        public MutableList<?> invoke(SkylarkList<?> input, Environment env) throws EvalException {
          int count = 0;
          List<SkylarkList<?>> result = Lists.newArrayList();
          for (Object obj : input) {
            result.add(Tuple.of(count, obj));
            count++;
          }
          return new MutableList(result, env);
        }
      };

  @SkylarkSignature(
    name = "hash",
    returnType = Integer.class,
    doc =
        "Return a hash value for a string. This is computed deterministically using the same "
            + "algorithm as Java's <code>String.hashCode()</code>, namely: "
            + "<pre class=\"language-python\">s[0] * (31^(n-1)) + s[1] * (31^(n-2)) + ... + s[0]"
            + "</pre> Hashing of values besides strings is not currently supported.",
    // Deterministic hashing is important for the consistency of builds, hence why we
    // promise a specific algorithm. This is in contrast to Java (Object.hashCode()) and
    // Python, which promise stable hashing only within a given execution of the program.
    parameters = {@Param(name = "value", type = String.class, doc = "String value to hash.")}
  )
  private static final BuiltinFunction hash =
      new BuiltinFunction("hash") {
        public Integer invoke(String value) throws EvalException {
          return value.hashCode();
        }
      };

  @SkylarkSignature(
    name = "range",
    returnType = MutableList.class,
    doc =
        "Creates a list where items go from <code>start</code> to <code>stop</code>, using a "
            + "<code>step</code> increment. If a single argument is provided, items will "
            + "range from 0 to that element."
            + "<pre class=\"language-python\">range(4) == [0, 1, 2, 3]\n"
            + "range(3, 9, 2) == [3, 5, 7]\n"
            + "range(3, 0, -1) == [3, 2, 1]</pre>",
    parameters = {
      @Param(
        name = "start_or_stop",
        type = Integer.class,
        doc =
            "Value of the start element if stop is provided, "
                + "otherwise value of stop and the actual start is 0"
      ),
      @Param(
        name = "stop_or_none",
        type = Integer.class,
        noneable = true,
        defaultValue = "None",
        doc =
            "optional index of the first item <i>not</i> to be included in the resulting "
                + "list; generation of the list stops before <code>stop</code> is reached."
      ),
      @Param(
        name = "step",
        type = Integer.class,
        defaultValue = "1",
        doc = "The increment (default is 1). It may be negative."
      )
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction range =
      new BuiltinFunction("range") {
        public MutableList<?> invoke(
            Integer startOrStop, Object stopOrNone, Integer step, Location loc, Environment env)
            throws EvalException {
          int start;
          int stop;
          if (stopOrNone == Runtime.NONE) {
            start = 0;
            stop = startOrStop;
          } else {
            start = startOrStop;
            stop = Type.INTEGER.convert(stopOrNone, "'stop' operand of 'range'");
          }
          if (step == 0) {
            throw new EvalException(loc, "step cannot be 0");
          }
          ArrayList<Integer> result = Lists.newArrayList();
          if (step > 0) {
            int size = (stop - start) / step;
            result.ensureCapacity(size);
            while (start < stop) {
              result.add(start);
              start += step;
            }
          } else {
            int size = (start - stop) / step;
            result.ensureCapacity(size);
            while (start > stop) {
              result.add(start);
              start += step;
            }
          }
          return new MutableList(result, env);
        }
      };

  /** Returns true if the object has a field of the given name, otherwise false. */
  @SkylarkSignature(
    name = "hasattr",
    returnType = Boolean.class,
    doc =
        "Returns True if the object <code>x</code> has an attribute or method of the given "
            + "<code>name</code>, otherwise False. Example:<br>"
            + "<pre class=\"language-python\">hasattr(ctx.attr, \"myattr\")</pre>",
    parameters = {
      @Param(name = "x", doc = "The object to check."),
      @Param(name = "name", type = String.class, doc = "The name of the attribute.")
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction hasattr =
      new BuiltinFunction("hasattr") {
        @SuppressWarnings("unused")
        public Boolean invoke(Object obj, String name, Location loc, Environment env)
            throws EvalException {
          if (obj instanceof ClassObject && ((ClassObject) obj).getValue(name) != null) {
            return true;
          }
          return hasMethod(obj, name, loc);
        }
      };

  @SkylarkSignature(
    name = "getattr",
    doc =
        "Returns the struct's field of the given name if it exists. If not, it either returns "
            + "<code>default</code> (if specified) or raises an error. Built-in methods cannot "
            + "currently be retrieved in this way; doing so will result in an error if a "
            + "<code>default</code> is not given. <code>getattr(x, \"foobar\")</code> is "
            + "equivalent to <code>x.foobar</code>."
            + "<pre class=\"language-python\">getattr(ctx.attr, \"myattr\")\n"
            + "getattr(ctx.attr, \"myattr\", \"mydefault\")</pre>",
    parameters = {
      @Param(name = "x", doc = "The struct whose attribute is accessed."),
      @Param(name = "name", doc = "The name of the struct attribute."),
      @Param(
        name = "default",
        defaultValue = "unbound",
        doc =
            "The default value to return in case the struct "
                + "doesn't have an attribute of the given name."
      )
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction getattr =
      new BuiltinFunction("getattr") {
        @SuppressWarnings("unused")
        public Object invoke(
            Object obj, String name, Object defaultValue, Location loc, Environment env)
            throws EvalException {
          Object result = DotExpression.eval(obj, name, loc, env);
          if (result == null) {
            // 'Real' describes methods with structField() == false. Because DotExpression.eval
            // returned null in this case, we know that structField() cannot return true.
            boolean isRealMethod = hasMethod(obj, name, loc);
            if (defaultValue != Runtime.UNBOUND) {
              return defaultValue;
            }
            throw new EvalException(
                loc,
                Printer.format(
                    "object of type '%s' has no attribute %r%s",
                    EvalUtils.getDataTypeName(obj),
                    name,
                    isRealMethod ? ", however, a method of that name exists" : ""));
          }
          return result;
        }
      };

  /**
   * Returns whether the given object has a method with the given name.
   */
  private static boolean hasMethod(Object obj, String name, Location loc) throws EvalException {
    if (Runtime.getFunctionNames(obj.getClass()).contains(name)) {
      return true;
    }

    return FuncallExpression.getMethodNames(obj.getClass()).contains(name);
  }

  @SkylarkSignature(
    name = "dir",
    returnType = MutableList.class,
    doc =
        "Returns a list strings: the names of the attributes and "
            + "methods of the parameter object.",
    parameters = {@Param(name = "x", doc = "The object to check.")},
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction dir =
      new BuiltinFunction("dir") {
        public MutableList<?> invoke(Object object, Location loc, Environment env)
            throws EvalException {
          // Order the fields alphabetically.
          Set<String> fields = new TreeSet<>();
          if (object instanceof ClassObject) {
            fields.addAll(((ClassObject) object).getKeys());
          }
          fields.addAll(Runtime.getFunctionNames(object.getClass()));
          fields.addAll(FuncallExpression.getMethodNames(object.getClass()));
          return new MutableList(fields, env);
        }
      };

  @SkylarkSignature(
    name = "fail",
    doc =
        "Raises an error that cannot be intercepted. It can be used anywhere, "
            + "both in the loading phase and in the analysis phase.",
    returnType = Runtime.NoneType.class,
    parameters = {
      @Param(
        name = "msg",
        type = Object.class,
        doc = "Error to display for the user. The object is converted to a string."
      ),
      @Param(
        name = "attr",
        type = String.class,
        noneable = true,
        defaultValue = "None",
        doc =
            "The name of the attribute that caused the error. This is used only for "
                + "error reporting."
      )
    },
    useLocation = true
  )
  private static final BuiltinFunction fail =
      new BuiltinFunction("fail") {
        public Runtime.NoneType invoke(Object msg, Object attr, Location loc) throws EvalException {
          String str = Printer.str(msg);
          if (attr != Runtime.NONE) {
            str = String.format("attribute %s: %s", attr, str);
          }
          throw new EvalException(loc, str);
        }
      };

  @SkylarkSignature(name = "print", returnType = Runtime.NoneType.class,
      doc = "Prints <code>args</code> as a warning. It can be used for debugging or "
          + "for transition (before changing to an error). In other cases, warnings are "
          + "discouraged.",
      parameters = {
        @Param(name = "sep", type = String.class, defaultValue = "' '",
            named = true, positional = false,
            doc = "The separator string between the objects, default is space (\" \").")},
      // NB: as compared to Python3, we're missing optional named-only arguments 'end' and 'file'
      extraPositionals = @Param(name = "args", doc = "The objects to print."),
      useLocation = true, useEnvironment = true)
  private static final BuiltinFunction print = new BuiltinFunction("print") {
    public Runtime.NoneType invoke(String sep, SkylarkList<?> starargs,
        Location loc, Environment env) throws EvalException {
      String msg = Joiner.on(sep).join(Iterables.transform(starargs,
              new com.google.common.base.Function<Object, String>() {
                @Override
                public String apply(Object input) {
                  return Printer.str(input);
                }}));
      env.handleEvent(Event.warn(loc, msg));
      return Runtime.NONE;
    }
  };

  @SkylarkSignature(
    name = "zip",
    doc =
        "Returns a <code>list</code> of <code>tuple</code>s, where the i-th tuple contains "
            + "the i-th element from each of the argument sequences or iterables. The list has the "
            + "size of the shortest input. With a single iterable argument, it returns a list of "
            + "1-tuples. With no arguments, it returns an empty list. Examples:"
            + "<pre class=\"language-python\">"
            + "zip()  # == []\n"
            + "zip([1, 2])  # == [(1,), (2,)]\n"
            + "zip([1, 2], [3, 4])  # == [(1, 3), (2, 4)]\n"
            + "zip([1, 2], [3, 4, 5])  # == [(1, 3), (2, 4)]</pre>",
    extraPositionals = @Param(name = "args", doc = "lists to zip."),
    returnType = MutableList.class,
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction zip =
      new BuiltinFunction("zip") {
        public MutableList<?> invoke(SkylarkList<?> args, Location loc, Environment env)
            throws EvalException {
          Iterator<?>[] iterators = new Iterator<?>[args.size()];
          for (int i = 0; i < args.size(); i++) {
            iterators[i] = EvalUtils.toIterable(args.get(i), loc).iterator();
          }
          List<Tuple<?>> result = new ArrayList<>();
          boolean allHasNext;
          do {
            allHasNext = !args.isEmpty();
            List<Object> elem = Lists.newArrayListWithExpectedSize(args.size());
            for (Iterator<?> iterator : iterators) {
              if (iterator.hasNext()) {
                elem.add(iterator.next());
              } else {
                allHasNext = false;
              }
            }
            if (allHasNext) {
              result.add(Tuple.copyOf(elem));
            }
          } while (allHasNext);
          return new MutableList(result, env);
        }
      };

  /** Skylark String module. */
  @SkylarkModule(
    name = "string",
    category = SkylarkModuleCategory.BUILTIN,
    doc =
        "A language built-in type to support strings. "
            + "Examples of string literals:<br>"
            + "<pre class=\"language-python\">a = 'abc\\ndef'\n"
            + "b = \"ab'cd\"\n"
            + "c = \"\"\"multiline string\"\"\"\n"
            + "\n"
            + "# Strings support slicing (negative index starts from the end):\n"
            + "x = \"hello\"[2:4]  # \"ll\"\n"
            + "y = \"hello\"[1:-1]  # \"ell\"\n"
            + "z = \"hello\"[:4]  # \"hell\""
            + "# Slice steps can be used, too:\n"
            + "s = \"hello\"[::2] # \"hlo\"\n"
            + "t = \"hello\"[3:0:-1] # \"lle\"\n</pre>"
            + "Strings are iterable and support the <code>in</code> operator. Examples:<br>"
            + "<pre class=\"language-python\">\"bc\" in \"abcd\"   # evaluates to True\n"
            + "x = [s for s in \"abc\"]  # x == [\"a\", \"b\", \"c\"]</pre>\n"
            + "Implicit concatenation of strings is not allowed; use the <code>+</code> "
            + "operator instead."
  )
  static final class StringModule {}


  static final List<BaseFunction> defaultGlobalFunctions =
      ImmutableList.<BaseFunction>of(
          all, any, bool, dict, dir, fail, getattr, hasattr, hash, enumerate, int_, len, list, max,
          min, minus, print, range, repr, reversed, sorted, str, tuple, zip);

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(MethodLibrary.class);
  }
}
