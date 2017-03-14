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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Location;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Base class for list and dict comprehension expressions.
 *
 * <p> A comprehension contains one or more clauses, e.g.
 *   [a+d for a in b if c for d in e]
 * contains three clauses: "for a in b", "if c", "for d in e".
 * For and If clauses can happen in any order, except that the first one has to be a For.
 *
 * <p> The code above can be expanded as:
 * <pre>
 *   for a in b:
 *     if c:
 *       for d in e:
 *         result.append(a+d)
 * </pre>
 * result is initialized to [] (list) or {} (dict) and is the return value of the whole expression.
 */
public abstract class AbstractComprehension extends Expression {

  /**
   * The interface implemented by ForClause and (later) IfClause.
   * A comprehension consists of one or many Clause.
   */
  public interface Clause extends Serializable {
    /**
     * The evaluation of the comprehension is based on recursion. Each clause may
     * call recursively evalStep (ForClause will call it multiple times, IfClause will
     * call it zero or one time) which will evaluate the next clause. To know which clause
     * is the next one, we pass a step argument (it represents the index in the clauses
     * list). Results are aggregated in the result argument, and are populated by
     * evalStep.
     *
     * @param env environment in which we do the evaluation.
     * @param collector the aggregated results of the comprehension.
     * @param step the index of the next clause to evaluate.
     */
    abstract void eval(Environment env, OutputCollector collector, int step)
        throws EvalException, InterruptedException;

    abstract void validate(ValidationEnvironment env) throws EvalException;

    /**
     * The LValue defined in Clause, i.e. the loop variables for ForClause and null for
     * IfClause. This is needed for SyntaxTreeVisitor.
     */
    @Nullable  // for the IfClause
    public abstract LValue getLValue();

    /**
     * The Expression defined in Clause, i.e. the collection for ForClause and the
     * condition for IfClause. This is needed for SyntaxTreeVisitor.
     */
    public abstract Expression getExpression();
  }

  /**
   * A for clause in a comprehension, e.g. "for a in b" in the example above.
   */
  public final class ForClause implements Clause {
    private final LValue variables;
    private final Expression list;

    public ForClause(LValue variables, Expression list) {
      this.variables = variables;
      this.list = list;
    }

    @Override
    public void eval(Environment env, OutputCollector collector, int step)
        throws EvalException, InterruptedException {
      Object listValueObject = list.eval(env);
      Location loc = getLocation();
      Iterable<?> listValue = EvalUtils.toIterable(listValueObject, loc);
      EvalUtils.lock(listValueObject, getLocation());
      try {
        for (Object listElement : listValue) {
          variables.assign(env, loc, listElement);
          evalStep(env, collector, step);
        }
      } finally {
        EvalUtils.unlock(listValueObject, getLocation());
      }
    }

    @Override
    public void validate(ValidationEnvironment env) throws EvalException {
      variables.validate(env, getLocation());
      list.validate(env);
    }

    @Override
    public LValue getLValue() {
      return variables;
    }

    @Override
    public Expression getExpression() {
      return list;
    }

    @Override
    public String toString() {
      return Printer.format("for %s in %r", variables.toString(), list);
    }
  }

  /**
   * A if clause in a comprehension, e.g. "if c" in the example above.
   */
  public final class IfClause implements Clause {
    private final Expression condition;

    public IfClause(Expression condition) {
      this.condition = condition;
    }

    @Override
    public void eval(Environment env, OutputCollector collector, int step)
        throws EvalException, InterruptedException {
      if (EvalUtils.toBoolean(condition.eval(env))) {
        evalStep(env, collector, step);
      }
    }

    @Override
    public void validate(ValidationEnvironment env) throws EvalException {
      condition.validate(env);
    }

    @Override
    public LValue getLValue() {
      return null;
    }

    @Override
    public Expression getExpression() {
      return condition;
    }

    @Override
    public String toString() {
      return String.format("if %s", condition);
    }
  }

  /**
   * The output expressions, e.g. "a+d" in the example above. This list has either one (list) or two
   * (dict) items.
   */
  private final ImmutableList<Expression> outputExpressions;

  private final List<Clause> clauses;
  private final char openingBracket;
  private final char closingBracket;

  public AbstractComprehension(
      char openingBracket, char closingBracket, Expression... outputExpressions) {
    clauses = new ArrayList<>();
    this.outputExpressions = ImmutableList.copyOf(outputExpressions);
    this.openingBracket = openingBracket;
    this.closingBracket = closingBracket;
  }

  public ImmutableList<Expression> getOutputExpressions() {
    return outputExpressions;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(openingBracket).append(printExpressions());
    for (Clause clause : clauses) {
      sb.append(' ').append(clause);
    }
    sb.append(closingBracket);
    return sb.toString();
  }

  /**
   * Add a new ForClause to the comprehension. This is used only by the parser and must
   * not be called once AST is complete.
   * TODO(bazel-team): Remove this side-effect. Clauses should be passed to the constructor
   * instead.
   */
  void addFor(Expression loopVar, Expression listExpression) {
    Clause forClause = new ForClause(new LValue(loopVar), listExpression);
    clauses.add(forClause);
  }

  /**
   * Add a new ForClause to the comprehension.
   * TODO(bazel-team): Remove this side-effect.
   */
  void addIf(Expression condition) {
    clauses.add(new IfClause(condition));
  }

  public List<Clause> getClauses() {
    return Collections.unmodifiableList(clauses);
  }

  @Override
  public void accept(SyntaxTreeVisitor visitor) {
    visitor.visit(this);
  }

  @Override
  Object doEval(Environment env) throws EvalException, InterruptedException {
    OutputCollector collector = createCollector(env);
    evalStep(env, collector, 0);
    return collector.getResult(env);
  }

  @Override
  void validate(ValidationEnvironment env) throws EvalException {
    for (Clause clause : clauses) {
      clause.validate(env);
    }
    // Clauses have to be validated before expressions in order to introduce the variable names.
    for (Expression expr : outputExpressions) {
      expr.validate(env);
    }
  }

  /**
   * Evaluate the clause indexed by step, or elementExpression. When we evaluate the
   * comprehension, step is 0 and we evaluate the first clause. Each clause may
   * recursively call evalStep any number of times. After the last clause,
   * the output expression(s) is/are evaluated and added to the results.
   *
   * <p> In the expanded example above, you can consider that evalStep is equivalent to
   * evaluating the line number step.
   */
  private void evalStep(Environment env, OutputCollector collector, int step)
      throws EvalException, InterruptedException {
    if (step >= clauses.size()) {
      collector.evaluateAndCollect(env);
    } else {
      clauses.get(step).eval(env, collector, step + 1);
    }
  }

  /**
   * Returns a {@link String} representation of the output expression(s).
   */
  abstract String printExpressions();

  abstract OutputCollector createCollector(Environment env);

  /**
   * Interface for collecting the intermediate output of an {@code AbstractComprehension} and for
   * providing access to the final results.
   */
  interface OutputCollector {
    /**
     * Evaluates the output expression(s) of the comprehension and collects the result.
     */
    void evaluateAndCollect(Environment env) throws EvalException, InterruptedException;

    /**
     * Returns the final result of the comprehension.
     */
    Object getResult(Environment env) throws EvalException;
  }
}
