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

/**
 * Syntax node for a string literal.
 */
public final class StringLiteral extends Literal<String> {

  private final char quoteChar;

  public StringLiteral(String value, char quoteChar) {
    super(value);
    this.quoteChar = quoteChar;
  }

  @Override
  public String toString() {
    return quoteChar + value.replace(Character.toString(quoteChar), "\\" + quoteChar) + quoteChar;
  }

  @Override
  public void accept(SyntaxTreeVisitor visitor) {
    visitor.visit(this);
  }

  /**
   * Gets the quote character that was used for this string.  For example, if
   * the string was 'hello, world!', then this method returns '\''.
   *
   * @return the character used to quote the string.
   */
  public char getQuoteChar() {
    return quoteChar;
  }

  @Override
  void validate(ValidationEnvironment env) throws EvalException {
  }
}
