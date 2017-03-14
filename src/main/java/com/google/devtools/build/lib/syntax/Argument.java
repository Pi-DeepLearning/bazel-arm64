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

import com.google.devtools.build.lib.util.Preconditions;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Syntax node for a function argument.
 *
 * <p>Argument is a base class for arguments passed in a call (@see Argument.Passed)
 * or defined as part of a function definition (@see Parameter).
 * It is notably used by some {@link Parser} and printer functions.
 */
public abstract class Argument extends ASTNode {

  public boolean isStar() {
    return false;
  }

  public boolean isStarStar() {
    return false;
  }

  /**
   * Argument.Passed is the class of arguments passed in a function call
   * (as opposed to being used in a definition -- @see Parameter for that).
   * Argument.Passed is usually what we mean when informally say "argument".
   *
   * <p>An Argument.Passed can be Positional, Keyword, Star, or StarStar.
   */
  public abstract static class Passed extends Argument {
    /** the value to be passed by this argument */
    protected final Expression value;

    private Passed(Expression value) {
      this.value = Preconditions.checkNotNull(value);
    }

    public boolean isPositional() {
      return false;
    }
    public boolean isKeyword() {
      return false;
    }
    @Nullable public String getName() { // only for keyword arguments
      return null;
    }
    public Expression getValue() {
      return value;
    }
    @Override
    public void accept(SyntaxTreeVisitor visitor) {
      visitor.visit(this);
    }
  }

  /** positional argument: Expression */
  public static class Positional extends Passed {

    public Positional(Expression value) {
      super(value);
    }

    @Override public boolean isPositional() {
      return true;
    }
    @Override
    public String toString() {
      return String.valueOf(value);
    }
  }

  /** keyword argument: K = Expression */
  public static class Keyword extends Passed {

    final String name;

    public Keyword(String name, Expression value) {
      super(value);
      this.name = name;
    }

    @Override public String getName() {
      return name;
    }
    @Override public boolean isKeyword() {
      return true;
    }
    @Override
    public String toString() {
      return name + " = " + value;
    }
  }

  /** positional rest (starred) argument: *Expression */
  public static class Star extends Passed {

    public Star(Expression value) {
      super(value);
    }

    @Override public boolean isStar() {
      return true;
    }
    @Override
    public String toString() {
      return "*" + value;
    }
  }

  /** keyword rest (star_starred) parameter: **Expression */
  public static class StarStar extends Passed {

    public StarStar(Expression value) {
      super(value);
    }

    @Override public boolean isStarStar() {
      return true;
    }
    @Override
    public String toString() {
      return "**" + value;
    }
  }

  /** Some arguments failed to satisfy python call convention strictures */
  protected static class ArgumentException extends Exception {
    /** construct an ArgumentException from a message only */
    public ArgumentException(String message) {
      super(message);
    }
  }

  /**
   * Validate that the list of Argument's, whether gathered by the Parser or from annotations,
   * satisfies the requirements of the Python calling conventions: all Positional's first,
   * at most one Star, at most one StarStar, at the end only.
   */
  public static void validateFuncallArguments(List<Passed> arguments)
      throws ArgumentException {
    boolean hasNamed = false;
    boolean hasStar = false;
    boolean hasKwArg = false;
    for (Passed arg : arguments) {
      if (hasKwArg) {
        throw new ArgumentException("argument after **kwargs");
      }
      if (arg.isPositional()) {
        if (hasNamed) {
          throw new ArgumentException("non-keyword arg after keyword arg");
        } else if (arg.isStar()) {
          throw new ArgumentException("only named arguments may follow *expression");
        }
      } else if (arg.isKeyword()) {
        hasNamed = true;
      } else if (arg.isStar()) {
        if (hasStar) {
          throw new ArgumentException("more than one *stararg");
        }
        hasStar = true;
      } else {
        hasKwArg = true;
      }
    }
  }
}
