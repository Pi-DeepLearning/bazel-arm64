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

package com.google.devtools.build.buildjar.javac.plugins.dependency;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.StrictJavaDeps.ERROR;
import static com.google.devtools.build.buildjar.javac.plugins.dependency.ImplicitDependencyExtractor.getPlatformJars;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;
import com.google.devtools.build.buildjar.JarOwner;
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin;
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.StrictJavaDeps;
import com.google.devtools.build.lib.view.proto.Deps;
import com.google.devtools.build.lib.view.proto.Deps.Dependency;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Log.WriterKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.annotation.Generated;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * A plugin for BlazeJavaCompiler that checks for types referenced directly in the source, but
 * included through transitive dependencies. To get this information, we hook into the type
 * attribution phase of the BlazeJavaCompiler (thus the overhead is another tree scan with the
 * classic visitor). The constructor takes a map from jar names to target names, only for the jars
 * that come from transitive dependencies (Blaze computes this information).
 */
public final class StrictJavaDepsPlugin extends BlazeJavaCompilerPlugin {

  @VisibleForTesting
  static String targetMapping =
      "com/google/devtools/build/buildjar/javac/resources/target.properties";

  private static final boolean USE_COLOR = true;
  private ImplicitDependencyExtractor implicitDependencyExtractor;
  private CheckingTreeScanner checkingTreeScanner;
  private final DependencyModule dependencyModule;

  /** Marks seen compilation toplevels and their import sections */
  private final Set<JCTree.JCCompilationUnit> toplevels;
  /** Marks seen ASTs */
  private final Set<JCTree> trees;
  /** Computed missing dependencies */
  private final Set<JarOwner> missingTargets;

  private static Properties targetMap;

  private JavaFileManager fileManager;

  private PrintWriter errWriter;

  /**
   * On top of javac, we keep Blaze-specific information in the form of two maps. Both map jars
   * (exactly as they appear on the classpath) to target names, one is used for direct dependencies,
   * the other for the transitive dependencies.
   *
   * <p>This enables the detection of dependency issues. For instance, when a type com.Foo is
   * referenced in the source and it's coming from an indirect dependency, we emit a warning
   * flagging that dependency. Also, we can check whether the direct dependencies were actually
   * necessary, i.e. if their associated jars were used at all for looking up class definitions.
   */
  public StrictJavaDepsPlugin(DependencyModule dependencyModule) {
    this.dependencyModule = dependencyModule;
    toplevels = new HashSet<>();
    trees = new HashSet<>();
    targetMap = new Properties();
    missingTargets = new HashSet<>();
  }

  @Override
  public void init(Context context, Log log, JavaCompiler compiler) {
    super.init(context, log, compiler);
    errWriter = log.getWriter(WriterKind.ERROR);
    fileManager = context.get(JavaFileManager.class);
    implicitDependencyExtractor =
        new ImplicitDependencyExtractor(
            dependencyModule.getUsedClasspath(),
            dependencyModule.getImplicitDependenciesMap(),
            fileManager);
    checkingTreeScanner = context.get(CheckingTreeScanner.class);
    if (checkingTreeScanner == null) {
      Set<String> platformJars = getPlatformJars(fileManager);
      checkingTreeScanner =
          new CheckingTreeScanner(dependencyModule, log, missingTargets, platformJars, fileManager);
      context.put(CheckingTreeScanner.class, checkingTreeScanner);
    }
    initTargetMap();
  }

  private void initTargetMap() {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream(targetMapping)) {
      if (is != null) {
        targetMap.load(is);
      }
    } catch (IOException ex) {
      log.warning("Error loading Strict Java Deps mapping file: " + targetMapping, ex);
    }
  }

  /**
   * We want to make another pass over the AST and "type-check" the usage of direct/transitive
   * dependencies after the type attribution phase.
   */
  @Override
  public void postAttribute(Env<AttrContext> env) {
    JavaFileObject previousSource =
        log.useSource(
            env.enclClass.sym.sourcefile != null
                ? env.enclClass.sym.sourcefile
                : env.toplevel.sourcefile);
    boolean previousExemption = checkingTreeScanner.isStrictDepsExempt;
    try {
      ProcessorDependencyMode mode = isAnnotationProcessorExempt(env.toplevel);
      if (mode == ProcessorDependencyMode.EXEMPT_NORECORD) {
        return;
      }
      checkingTreeScanner.isStrictDepsExempt |= mode == ProcessorDependencyMode.EXEMPT_RECORD;
      if (trees.add(env.tree)) {
        checkingTreeScanner.scan(env.tree);
      }
      if (toplevels.add(env.toplevel)) {
        checkingTreeScanner.scan(env.toplevel.getImports());
        dependencyModule.addPackage(env.toplevel.packge);
      }
    } finally {
      checkingTreeScanner.isStrictDepsExempt = previousExemption;
      log.useSource(previousSource);
    }
  }

  @Override
  public void finish() {
    implicitDependencyExtractor.accumulate(context, checkingTreeScanner.getSeenClasses());

    if (!missingTargets.isEmpty()) {
      String canonicalizedLabel =
          dependencyModule.getTargetLabel() == null
              ? null
              : canonicalizeTarget(dependencyModule.getTargetLabel());
      List<JarOwner> canonicalizedMissing = new ArrayList<>();
      for (JarOwner owner :
          Ordering.natural().onResultOf(JarOwner.LABEL).immutableSortedCopy(missingTargets)) {
        canonicalizedMissing.add(
            JarOwner.create(canonicalizeTarget(owner.label()), owner.aspect()));
      }
      errWriter.print(
          dependencyModule
              .getFixMessage()
              .get(canonicalizedMissing, canonicalizedLabel, USE_COLOR));
    }
  }

  /**
   * An AST visitor that implements our strict_java_deps checks. For now, it only emits warnings for
   * types loaded from jar files provided by transitive (indirect) dependencies. Each type is
   * considered only once, so at most one warning is generated for it.
   */
  private static class CheckingTreeScanner extends TreeScanner {

    private static final String TRANSITIVE_DEP_MESSAGE =
        "[strict] Using type {0} from an indirect dependency (TOOL_INFO: \"{1}\"). "
            + "See command below **";

    /** Lookup for jars coming from transitive dependencies */
    private final Map<String, JarOwner> indirectJarsToTargets;

    /** All error reporting is done through javac's log, */
    private final Log log;

    /** The compilation's file manager. */
    private final JavaFileManager fileManager;

    /** The strict_java_deps mode */
    private final StrictJavaDeps strictJavaDepsMode;

    /** Missing targets */
    private final Set<JarOwner> missingTargets;

    /** Collect seen direct dependencies and their associated information */
    private final Map<String, Deps.Dependency> directDependenciesMap;

    /** We only emit one warning/error per class symbol */
    private final Set<ClassSymbol> seenClasses = new HashSet<>();

    private final Set<JarOwner> seenTargets = new HashSet<>();

    /** The set of jars on the compilation bootclasspath. */
    private final Set<String> platformJars;

    /** Was the node being visited generated by an exempt annotation processor? */
    private boolean isStrictDepsExempt = false;

    public CheckingTreeScanner(
        DependencyModule dependencyModule,
        Log log,
        Set<JarOwner> missingTargets,
        Set<String> platformJars,
        JavaFileManager fileManager) {
      this.indirectJarsToTargets = dependencyModule.getIndirectMapping();
      this.strictJavaDepsMode = dependencyModule.getStrictJavaDeps();
      this.log = log;
      this.missingTargets = missingTargets;
      this.directDependenciesMap = dependencyModule.getExplicitDependenciesMap();
      this.platformJars = platformJars;
      this.fileManager = fileManager;
    }

    Set<ClassSymbol> getSeenClasses() {
      return seenClasses;
    }

    /** Checks an AST node denoting a class type against direct/transitive dependencies. */
    private void checkTypeLiteral(JCTree node) {
      if (node == null || node.type.tsym == null) {
        return;
      }

      Symbol.TypeSymbol sym = node.type.tsym;
      String jarName = getJarName(fileManager, sym.enclClass(), platformJars);

      // If this type symbol comes from a class file loaded from a jar, check
      // whether that jar was a direct dependency and error out otherwise.
      if (jarName != null && seenClasses.add(sym.enclClass())) {
        collectExplicitDependency(jarName, node, sym);
      }
    }

    /**
     * Marks the provided dependency as a direct/explicit dependency. Additionally, if
     * strict_java_deps is enabled, it emits a [strict] compiler warning/error (behavior to be soon
     * replaced by the more complete Blaze implementation).
     */
    private void collectExplicitDependency(String jarName, JCTree node, Symbol.TypeSymbol sym) {
      if (strictJavaDepsMode.isEnabled() && !isStrictDepsExempt) {
        // Does it make sense to emit a warning/error for this pair of (type, owner)?
        // We want to emit only one error/warning per owner.
        JarOwner owner = indirectJarsToTargets.get(jarName);
        if (owner != null && seenTargets.add(owner)) {
          // owner is of the form "//label/of:rule <Aspect name>" where <Aspect name> is optional.
          String canonicalTargetName = canonicalizeTarget(owner.label());
          missingTargets.add(owner);
          String toolInfo =
              owner.aspect() == null
                  ? canonicalTargetName
                  : String.format("%s wrapped in %s", canonicalTargetName, owner.aspect());
          if (strictJavaDepsMode == ERROR) {
            log.error(
                node.pos,
                "proc.messager",
                MessageFormat.format(TRANSITIVE_DEP_MESSAGE, sym, toolInfo));
          } else {
            log.warning(
                node.pos,
                "proc.messager",
                MessageFormat.format(TRANSITIVE_DEP_MESSAGE, sym, toolInfo));
          }
        }
      }

      if (!directDependenciesMap.containsKey(jarName)) {
        // Also update the dependency proto
        Dependency dep =
            Dependency.newBuilder().setPath(jarName).setKind(Dependency.Kind.EXPLICIT).build();
        directDependenciesMap.put(jarName, dep);
      }
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl method) {
      if ((method.mods.flags & Flags.GENERATEDCONSTR) != 0) {
        // If this is the constructor for an anonymous inner class, refrain from checking the
        // compiler-generated method signature. Don't skip scanning the method body though, there
        // might have been an anonymous initializer which still needs to be checked.
        scan(method.body);
      } else {
        super.visitMethodDef(method);
      }
    }

    /** Visits an identifier in the AST. We only care about type symbols. */
    @Override
    public void visitIdent(JCTree.JCIdent tree) {
      if (tree.sym != null && tree.sym.kind == Kinds.Kind.TYP) {
        checkTypeLiteral(tree);
      }
    }

    /**
     * Visits a field selection in the AST. We care because in some cases types may appear fully
     * qualified and only inside a field selection (e.g., "com.foo.Bar.X", we want to catch the
     * reference to Bar).
     */
    @Override
    public void visitSelect(JCTree.JCFieldAccess tree) {
      scan(tree.selected);
      if (tree.sym != null && tree.sym.kind == Kinds.Kind.TYP) {
        checkTypeLiteral(tree);
      }
    }

    @Override
    public void visitLambda(JCTree.JCLambda tree) {
      if (tree.paramKind != JCTree.JCLambda.ParameterKind.IMPLICIT) {
        // don't record type uses for implicitly typed lambda parameters
        scan(tree.params);
      }
      scan(tree.body);
    }
  }

  private static final String DAGGER_PROCESSOR_PREFIX = "dagger.";

  enum ProcessorDependencyMode {
    DEFAULT,
    EXEMPT_RECORD,
    EXEMPT_NORECORD;
  }

  /**
   * Returns true if the compilation unit contains a single top-level class generated by an exempt
   * annotation processor (according to its {@link @Generated} annotation).
   *
   * <p>Annotation processors are expected to never generate more than one top level class, as
   * required by the style guide.
   */
  public ProcessorDependencyMode isAnnotationProcessorExempt(JCTree.JCCompilationUnit unit) {
    if (unit.getTypeDecls().size() != 1) {
      return ProcessorDependencyMode.DEFAULT;
    }
    Symbol sym = TreeInfo.symbolFor(getOnlyElement(unit.getTypeDecls()));
    if (sym == null) {
      return ProcessorDependencyMode.DEFAULT;
    }
    Generated generated = sym.getAnnotation(Generated.class);
    if (generated == null) {
      return ProcessorDependencyMode.DEFAULT;
    }
    for (String value : generated.value()) {
      // Relax strict deps for dagger-generated code (b/17979436).
      if (value.startsWith(DAGGER_PROCESSOR_PREFIX)) {
        return ProcessorDependencyMode.EXEMPT_NORECORD;
      }
      if (dependencyModule.getExemptGenerators().contains(value)) {
        return ProcessorDependencyMode.EXEMPT_RECORD;
      }
    }
    return ProcessorDependencyMode.DEFAULT;
  }

  /** Returns the canonical version of the target name. Package private for testing. */
  static String canonicalizeTarget(String target) {
    String replacement = targetMap.getProperty(target);
    if (replacement != null) {
      return replacement;
    }
    int atIndex = target.indexOf('@');
    if (atIndex != -1) {
      // target starts with @@repo ('@' is escaped for the params file parsing) so one @ needs to
      // be stripped.
      target = target.substring(1);
    }
    int colonIndex = target.indexOf(':');
    if (colonIndex == -1) {
      // No ':' in target, nothing to do.
      return target;
    }
    int lastSlash = target.lastIndexOf('/', colonIndex);
    if (lastSlash == -1) {
      // No '/' or target is actually a filename in label format, return unmodified.
      return target;
    }
    String packageName = target.substring(lastSlash + 1, colonIndex);
    String suffix = target.substring(colonIndex + 1);
    if (packageName.equals(suffix)) {
      // target ends in "/something:something", canonicalize.
      return target.substring(0, colonIndex);
    }
    return target;
  }

  /**
   * Returns the name of the jar file from which the given class symbol was loaded, if available,
   * and null otherwise. Implicitly filters out jars from the compilation bootclasspath.
   *
   * @param platformJars jars on javac's bootclasspath
   */
  static String getJarName(
      JavaFileManager fileManager, ClassSymbol classSymbol, Set<String> platformJars) {
    if (classSymbol == null) {
      return null;
    }

    // Ignore symbols that appear in the sourcepath:
    if (haveSourceForSymbol(classSymbol)) {
      return null;
    }

    JavaFileObject classfile = classSymbol.classfile;

    String name = ImplicitDependencyExtractor.getJarName(classfile);
    if (name == null) {
      return null;
    }

    // Filter out classes on bootclasspath
    if (platformJars.contains(name)) {
      return null;
    }

    return name;
  }

  /** Returns true if the given classSymbol corresponds to one of the sources being compiled. */
  private static boolean haveSourceForSymbol(ClassSymbol classSymbol) {
    if (classSymbol.sourcefile == null) {
      return false;
    }

    try {
      // The classreader uses metadata to populate the symbol's sourcefile with a fake file object.
      // Call getLastModified() to check if it's a real file:
      classSymbol.sourcefile.getLastModified();
    } catch (UnsupportedOperationException e) {
      return false;
    }

    return true;
  }
}
