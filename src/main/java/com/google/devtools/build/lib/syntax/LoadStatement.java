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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Syntax node for an import statement.
 */
public final class LoadStatement extends Statement {

  private final ImmutableMap<Identifier, String> symbols;
  private final ImmutableList<Identifier> cachedSymbols; // to save time
  private final StringLiteral imp;

  /**
   * Constructs an import statement.
   *
   * <p>{@code symbols} maps a symbol to the original name under which it was defined in
   * the bzl file that should be loaded. If aliasing is used, the value differs from its key's
   * {@code symbol.getName()}. Otherwise, both values are identical.
   */
  LoadStatement(StringLiteral imp, Map<Identifier, String> symbols) {
    this.imp = imp;
    this.symbols = ImmutableMap.copyOf(symbols);
    this.cachedSymbols = ImmutableList.copyOf(symbols.keySet());
  }

  public ImmutableList<Identifier> getSymbols() {
    return cachedSymbols;
  }

  public StringLiteral getImport() {
    return imp;
  }

  @Override
  public String toString() {
    return String.format(
        "load(\"%s\", %s)", imp.getValue(), Joiner.on(", ").join(cachedSymbols));
  }

  @Override
  void doExec(Environment env) throws EvalException, InterruptedException {
    for (Map.Entry<Identifier, String> entry : symbols.entrySet()) {
      try {
        Identifier name = entry.getKey();
        Identifier declared = new Identifier(entry.getValue());

        if (declared.isPrivate()) {
          throw new EvalException(getLocation(),
              "symbol '" + declared.getName() + "' is private and cannot be imported.");
        }
        // The key is the original name that was used to define the symbol
        // in the loaded bzl file.
        env.importSymbol(imp.getValue(), name, declared.getName());
      } catch (Environment.LoadFailedException e) {
        throw new EvalException(getLocation(), e.getMessage());
      }
    }
  }

  @Override
  public void accept(SyntaxTreeVisitor visitor) {
    visitor.visit(this);
  }

  @Override
  void validate(ValidationEnvironment env) throws EvalException {
    for (Identifier symbol : cachedSymbols) {
      env.declare(symbol.getName(), getLocation());
    }
  }
}
