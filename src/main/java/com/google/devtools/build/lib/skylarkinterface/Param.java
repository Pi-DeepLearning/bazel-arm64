// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skylarkinterface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation for parameters of Skylark built-in functions.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {

  /**
   * Name of the parameter, as viewed from Skylark. Used for named parameters and for generating
   * documentation.
   */
  String name();

  /**
   * Documentation of the parameter.
   */
  String doc() default "";

  /**
   * Default value for the parameter, as a Skylark value (e.g. "False", "True", "[]", "None").
   */
  String defaultValue() default "";

  /**
   * Type of the parameter, e.g. {@link String}.class or
   * {@link com.google.devtools.build.lib.syntax.SkylarkList}.class.
   */
  Class<?> type() default Object.class;

  /**
   * List of allowed types for the parameter if multiple types are allowed, and {@link #type()} is
   * set to Object.class.
   */
  ParamType[] allowedTypes() default {};

  /**
   * When {@link #type()} is a generic type (e.g.,
   * {@link com.google.devtools.build.lib.syntax.SkylarkList}), specify the type parameter (e.g.
   * {@link String}.class} along with {@link com.google.devtools.build.lib.syntax.SkylarkList} for
   * {@link #type()} to specify a list of strings).
   */
  Class<?> generic1() default Object.class;

  /**
   * Whether the name of a callback function can be given instead of a computed value. If a
   * callback function is used then the value of this parameter will be computed only when
   * actually requested. E.g., if a parameter {@code foo} of a function {@code bar} is passed a
   * callback function, then only when the method {@code bar} actually asks for the value
   * {@code foo}, replacing it by a
   * {@link com.google.devtools.build.lib.syntax.SkylarkCallbackFunction} in between.
   */
  boolean callbackEnabled() default false;

  /**
   * If true, this parameter can be passed the "None" value.
   */
  boolean noneable() default false;

  /**
   * If true, the parameter may be specified as a named parameter. For example for an integer named
   * parameter {@code foo} of a method {@code bar}, then the method call will look like
   * {@code foo(bar=1)}.
   */
  boolean named() default false;

  /**
   * If true, the parameter may be specified as a positional parameter. For example for an integer
   * positional parameter {@code foo} of a method {@code bar}, then the method call will look like
   * {@code foo(1)}. If {@link #named()} is {@code false}, then this will be the only way to call
   * {@code foo}.
   *
   * <p>Positional arguments should come first.
   */
  boolean positional() default true;

  // TODO(bazel-team): parse the type from a single field in Skylark syntax,
  // and allow a Union as "ThisType or ThatType or NoneType":
  // String type() default "Object";
}
