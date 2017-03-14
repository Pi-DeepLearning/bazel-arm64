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
package com.google.devtools.common.options;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Some convenient converters used by blaze. Note: These are specific to
 * blaze.
 */
public final class Converters {

  /**
   * Join a list of words as in English.  Examples:
   * "nothing"
   * "one"
   * "one or two"
   * "one and two"
   * "one, two or three".
   * "one, two and three".
   * The toString method of each element is used.
   */
  static String joinEnglishList(Iterable<?> choices) {
    StringBuilder buf = new StringBuilder();
    for (Iterator<?> ii = choices.iterator(); ii.hasNext(); ) {
      Object choice = ii.next();
      if (buf.length() > 0) {
        buf.append(ii.hasNext() ? ", " : " or ");
      }
      buf.append(choice);
    }
    return buf.length() == 0 ? "nothing" : buf.toString();
  }

  public static class SeparatedOptionListConverter
      implements Converter<List<String>> {

    private final String separatorDescription;
    private final Splitter splitter;

    protected SeparatedOptionListConverter(char separator,
                                           String separatorDescription) {
      this.separatorDescription = separatorDescription;
      this.splitter = Splitter.on(separator);
    }

    @Override
    public List<String> convert(String input) {
      return input.equals("")
          ? ImmutableList.<String>of()
          : ImmutableList.copyOf(splitter.split(input));
    }

    @Override
    public String getTypeDescription() {
      return separatorDescription + "-separated list of options";
    }
  }

  public static class CommaSeparatedOptionListConverter
      extends SeparatedOptionListConverter {
    public CommaSeparatedOptionListConverter() {
      super(',', "comma");
    }
  }

  public static class ColonSeparatedOptionListConverter extends SeparatedOptionListConverter {
    public ColonSeparatedOptionListConverter() {
      super(':', "colon");
    }
  }

  public static class LogLevelConverter implements Converter<Level> {

    public static Level[] LEVELS = new Level[] {
      Level.OFF, Level.SEVERE, Level.WARNING, Level.INFO, Level.FINE,
      Level.FINER, Level.FINEST
    };

    @Override
    public Level convert(String input) throws OptionsParsingException {
      try {
        int level = Integer.parseInt(input);
        return LEVELS[level];
      } catch (NumberFormatException e) {
        throw new OptionsParsingException("Not a log level: " + input);
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new OptionsParsingException("Not a log level: " + input);
      }
    }

    @Override
    public String getTypeDescription() {
      return "0 <= an integer <= " + (LEVELS.length - 1);
    }

  }

  /**
   * Checks whether a string is part of a set of strings.
   */
  public static class StringSetConverter implements Converter<String> {

    // TODO(bazel-team): if this class never actually contains duplicates, we could s/List/Set/
    // here.
    private final List<String> values;

    public StringSetConverter(String... values) {
      this.values = ImmutableList.copyOf(values);
    }

    @Override
    public String convert(String input) throws OptionsParsingException {
      if (values.contains(input)) {
        return input;
      }

      throw new OptionsParsingException("Not one of " + values);
    }

    @Override
    public String getTypeDescription() {
      return joinEnglishList(values);
    }
  }

  /**
   * Checks whether a string is a valid regex pattern and compiles it.
   */
  public static class RegexPatternConverter implements Converter<Pattern> {

    @Override
    public Pattern convert(String input) throws OptionsParsingException {
      try {
        return Pattern.compile(input);
      } catch (PatternSyntaxException e) {
        throw new OptionsParsingException("Not a valid regular expression: " + e.getMessage());
      }
    }

    @Override
    public String getTypeDescription() {
      return "a valid Java regular expression";
    }
  }

  /**
   * Limits the length of a string argument.
   */
  public static class LengthLimitingConverter implements Converter<String> {
    private final int maxSize;

    public LengthLimitingConverter(int maxSize) {
      this.maxSize = maxSize;
    }

    @Override
    public String convert(String input) throws OptionsParsingException {
      if (input.length() > maxSize) {
        throw new OptionsParsingException("Input must be " + getTypeDescription());
      }
      return input;
    }

    @Override
    public String getTypeDescription() {
      return "a string <= " + maxSize + " characters";
    }
  }

  /**
   * Checks whether an integer is in the given range.
   */
  public static class RangeConverter implements Converter<Integer> {
    final int minValue;
    final int maxValue;

    public RangeConverter(int minValue, int maxValue) {
      this.minValue = minValue;
      this.maxValue = maxValue;
    }

    @Override
    public Integer convert(String input) throws OptionsParsingException {
      try {
        Integer value = Integer.parseInt(input);
        if (value < minValue) {
          throw new OptionsParsingException("'" + input + "' should be >= " + minValue);
        } else if (value < minValue || value > maxValue) {
          throw new OptionsParsingException("'" + input + "' should be <= " + maxValue);
        }
        return value;
      } catch (NumberFormatException e) {
        throw new OptionsParsingException("'" + input + "' is not an int");
      }
    }

    @Override
    public String getTypeDescription() {
      if (minValue == Integer.MIN_VALUE) {
        if (maxValue == Integer.MAX_VALUE) {
          return "an integer";
        } else {
          return "an integer, <= " + maxValue;
        }
      } else if (maxValue == Integer.MAX_VALUE) {
        return "an integer, >= " + minValue;
      } else {
        return "an integer in "
            + (minValue < 0 ? "(" + minValue + ")" : minValue) + "-" + maxValue + " range";
      }
    }
  }

  /**
   * A converter for variable assignments from the parameter list of a blaze
   * command invocation. Assignments are expected to have the form "name=value",
   * where names and values are defined to be as permissive as possible.
   */
  public static class AssignmentConverter implements Converter<Map.Entry<String, String>> {

    @Override
    public Map.Entry<String, String> convert(String input)
        throws OptionsParsingException {
      int pos = input.indexOf("=");
      if (pos <= 0) {
        throw new OptionsParsingException("Variable definitions must be in the form of a "
            + "'name=value' assignment");
      }
      String name = input.substring(0, pos);
      String value = input.substring(pos + 1);
      return Maps.immutableEntry(name, value);
    }

    @Override
    public String getTypeDescription() {
      return "a 'name=value' assignment";
    }

  }

  /**
   * A converter for variable assignments from the parameter list of a blaze
   * command invocation. Assignments are expected to have the form "name[=value]",
   * where names and values are defined to be as permissive as possible and value
   * part can be optional (in which case it is considered to be null).
   */
  public static class OptionalAssignmentConverter implements Converter<Map.Entry<String, String>> {

    @Override
    public Map.Entry<String, String> convert(String input)
        throws OptionsParsingException {
      int pos = input.indexOf("=");
      if (pos == 0 || input.length() == 0) {
        throw new OptionsParsingException("Variable definitions must be in the form of a "
            + "'name=value' or 'name' assignment");
      } else if (pos < 0) {
        return Maps.immutableEntry(input, null);
      }
      String name = input.substring(0, pos);
      String value = input.substring(pos + 1);
      return Maps.immutableEntry(name, value);
    }

    @Override
    public String getTypeDescription() {
      return "a 'name=value' assignment with an optional value part";
    }

  }

  public static class HelpVerbosityConverter extends EnumConverter<OptionsParser.HelpVerbosity> {
    public HelpVerbosityConverter() {
      super(OptionsParser.HelpVerbosity.class, "--help_verbosity setting");
    }
  }

  /**
   * A converter for boolean values. This is already one of the defaults, so clients
   * should not typically need to add this.
   */
  public static class BooleanConverter implements Converter<Boolean> {
    @Override
    public Boolean convert(String input) throws OptionsParsingException {
      if (input == null) {
        return false;
      }
      input = input.toLowerCase();
      if (input.equals("true") || input.equals("1") || input.equals("yes") ||
          input.equals("t") || input.equals("y")) {
        return true;
      }
      if (input.equals("false") || input.equals("0") || input.equals("no") ||
          input.equals("f") || input.equals("n")) {
        return false;
      }
      throw new OptionsParsingException("'" + input + "' is not a boolean");
    }

    @Override
    public String getTypeDescription() {
      return "a boolean";
    }
  }

}
